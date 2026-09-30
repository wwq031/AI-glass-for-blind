package com.leqi.experiment.phonebt

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.amap.api.maps.AMapException
import com.amap.api.navi.AMapNavi
import com.amap.api.navi.SimpleNaviListener
import com.amap.api.navi.enums.NaviType
import com.amap.api.navi.model.AMapCalcRouteResult
import com.amap.api.navi.model.AMapNaviLocation
import com.amap.api.navi.model.AMapNaviPath
import com.amap.api.navi.model.NaviInfo
import com.amap.api.navi.model.NaviLatLng

/**
 * 高德导航 SDK 的步行导航封装。
 *
 * 用 [AMapNavi.calculateWalkRoute] 算步行路线，成功后用 `startNavi(NaviType.GPS)` 起 GPS 导航，
 * 把关键导航文字、偏航、定位弱和到达转成回调交给任务层。
 *
 * 约定：
 * - 所有回调都发生在主线程；
 * - 定位弱只在状态翻转时上报一次，不刷屏；
 * - 剩余距离只在跨过 500/200/50 米阈值时各报一次，避免重复播报；
 * - 本类不自己拍任何照片，路口事件只上报，是否观察入口由任务层在到达后决定。
 *
 * 本类是与 [AmapNavigator] 一一对应的 Kotlin 适配层：SDK 调用、回调、错误传播和路线数据都保持一致，
 * 不含任何业务状态机或 Agent 决策。
 *
 * 只接 [Context]：导航由前台 Service 持有，Activity 销毁重建不能把正在走的路线带走。
 */
class AmapNaviAdapter(
    private val context: Context,
    private val listener: Listener,
) : SimpleNaviListener() {

    private companion object {
        /** 算路超时；超过就当失败，不无限等待。 */
        const val ROUTE_TIMEOUT_MS = 25_000L
        /** 定位精度差于这个米数就算定位弱。 */
        const val WEAK_ACCURACY_M = 60f
        val PROGRESS_THRESHOLDS_M = intArrayOf(500, 200, 50)
    }

    /** 任务层实现这一组回调；所有回调都在主线程。 */
    interface Listener {
        fun onRouteReady(distanceM: Int, timeSec: Int)

        fun onRouteFailed(message: String)

        fun onNavigationText(text: String)

        fun onOffRoute()

        fun onRerouted()

        fun onLocationWeak(weak: Boolean, detail: String)

        fun onProgress(remainDistanceM: Int)

        fun onArrived()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val announcedThresholds = HashSet<Int>()

    private var navi: AMapNavi? = null
    private var calculating = false
    private var navigating = false
    private var weakReported = false
    private var routeTimeout: Runnable? = null

    fun isNavigating(): Boolean = navigating

    /** 创建高德导航实例。必须在主线程调用，且调用前必须已经完成隐私同意。 */
    @Throws(AMapException::class)
    fun prepare() {
        if (navi != null) return
        navi = AMapNavi.getInstance(context)
        val active = navi ?: throw AMapException("高德导航实例创建失败")
        active.addAMapNaviListener(this)
    }

    /** 请求步行路线。必须在主线程调用。 */
    @Throws(AMapException::class)
    fun calculateWalk(
        fromLatitude: Double,
        fromLongitude: Double,
        toLatitude: Double,
        toLongitude: Double,
    ) {
        prepare()
        cancelRouteTimeout()
        announcedThresholds.clear()
        weakReported = false
        calculating = true
        val accepted = try {
            // prepare() 已经保证实例存在（不存在时它自己就抛了），所以这里和 Java 版一样直接调用。
            navi!!.calculateWalkRoute(
                NaviLatLng(fromLatitude, fromLongitude),
                NaviLatLng(toLatitude, toLongitude),
            )
        } catch (error: RuntimeException) {
            calculating = false
            throw AMapException("高德步行路线计算调用失败：" + error.javaClass.simpleName)
        }
        if (!accepted) {
            calculating = false
            throw AMapException("高德没有接受本次步行路线计算请求")
        }
        scheduleRouteTimeout()
    }

    /** 路线算好之后真正开始 GPS 导航。 */
    fun startGpsNavi(): Boolean {
        val active = navi ?: return false
        navigating = try {
            active.startNavi(NaviType.GPS)
        } catch (error: RuntimeException) {
            false
        }
        return navigating
    }

    /** 停止导航；不影响已创建的实例，之后还能重新算路。 */
    fun stop() {
        cancelRouteTimeout()
        calculating = false
        announcedThresholds.clear()
        weakReported = false
        val active = navi ?: return
        try {
            if (navigating) active.stopNavi()
        } catch (ignored: RuntimeException) {
            // 已经停了就算了，不能因为停导航失败把上层流程打断。
        }
        navigating = false
    }

    /** 释放监听和单例，宿主 Service 收尾（停止或销毁）时调用。 */
    fun close() {
        stop()
        val active = navi
        navi = null
        if (active == null) return
        try {
            active.removeAMapNaviListener(this)
        } catch (ignored: RuntimeException) {
            // 同上，释放失败不影响退出。
        }
        try {
            AMapNavi.destroy()
        } catch (ignored: RuntimeException) {
            // 同上。
        }
    }

    private fun scheduleRouteTimeout() {
        cancelRouteTimeout()
        val timeout = Runnable {
            if (!calculating) return@Runnable
            calculating = false
            listener.onRouteFailed("等待高德步行路线超时")
        }
        routeTimeout = timeout
        handler.postDelayed(timeout, ROUTE_TIMEOUT_MS)
    }

    private fun cancelRouteTimeout() {
        val pending = routeTimeout ?: return
        handler.removeCallbacks(pending)
        routeTimeout = null
    }

    override fun onInitNaviSuccess() {
        // 实例创建成功，真正的算路成功在 onCalculateRouteSuccess。
    }

    override fun onInitNaviFailure() {
        calculating = false
        cancelRouteTimeout()
        listener.onRouteFailed("高德导航初始化失败")
    }

    override fun onCalculateRouteSuccess(routeIds: IntArray?) {
        cancelRouteTimeout()
        if (navigating) {
            listener.onRerouted()
            return
        }
        if (!calculating) return
        calculating = false
        var distance = 0
        var time = 0
        try {
            val path: AMapNaviPath? = navi?.getNaviPath()
            if (path != null) {
                distance = path.getAllLength()
                time = path.getAllTime()
            }
        } catch (ignored: RuntimeException) {
            // 拿不到路线摘要也照样能开始导航，只是播报里少一句里程。
        }
        listener.onRouteReady(distance, time)
    }

    override fun onCalculateRouteFailure(errorCode: Int) {
        failRoute("高德步行路线计算失败（错误码 ${errorCode}）")
    }

    override fun onCalculateRouteFailure(result: AMapCalcRouteResult?) {
        if (navigating) return
        val detail = result?.getErrorDescription()
        failRoute(
            "高德步行路线计算失败（错误码 " +
                (if (result == null) "未知" else result.getErrorCode().toString()) + "）" +
                (if (detail == null || detail.isEmpty()) "" else "：$detail"),
        )
    }

    private fun failRoute(message: String) {
        if (!calculating) return
        calculating = false
        cancelRouteTimeout()
        listener.onRouteFailed(message)
    }

    override fun onStartNavi(type: Int) {
        navigating = true
    }

    override fun onGetNavigationText(type: Int, text: String?) {
        if (text != null && text.trim().isNotEmpty()) listener.onNavigationText(text.trim())
    }

    override fun onGetNavigationText(text: String?) {
        // 与带类型的回调是同一句话，去重由播报通道负责，这里照常上报。
        if (text != null && text.trim().isNotEmpty()) listener.onNavigationText(text.trim())
    }

    override fun onReCalculateRouteForYaw() {
        listener.onOffRoute()
    }

    override fun onGpsSignalWeak(weak: Boolean) {
        reportWeak(weak, if (weak) "卫星信号弱" else "卫星信号恢复")
    }

    override fun onGpsOpenStatus(enabled: Boolean) {
        if (!enabled) reportWeak(true, "系统定位已关闭")
    }

    override fun onLocationChange(location: AMapNaviLocation?) {
        if (location == null) return
        val accuracy = location.getAccuracy()
        if (accuracy <= 0) return
        if (accuracy > WEAK_ACCURACY_M) {
            reportWeak(true, "当前定位精度约 " + Math.round(accuracy) + " 米")
        } else if (accuracy <= WEAK_ACCURACY_M / 2) {
            reportWeak(false, "定位精度恢复")
        }
    }

    override fun onNaviInfoUpdate(info: NaviInfo?) {
        if (info == null || !navigating) return
        val remain = try {
            info.getPathRetainDistance()
        } catch (error: RuntimeException) {
            return
        }
        if (remain <= 0) return
        for (threshold in PROGRESS_THRESHOLDS_M) {
            if (remain <= threshold && announcedThresholds.add(threshold)) {
                listener.onProgress(remain)
                return
            }
        }
    }

    override fun onArriveDestination() {
        if (!navigating) return
        navigating = false
        cancelRouteTimeout()
        listener.onArrived()
    }

    /** 只在状态翻转时上报，避免同一条“定位弱”反复播报。 */
    private fun reportWeak(weak: Boolean, detail: String) {
        if (weakReported == weak) return
        weakReported = weak
        listener.onLocationWeak(weak, detail)
    }
}
