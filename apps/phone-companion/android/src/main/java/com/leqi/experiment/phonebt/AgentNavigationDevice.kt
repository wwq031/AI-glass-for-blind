package com.leqi.experiment.phonebt

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.amap.api.maps.AMapException as AmapMapException
import com.amap.api.services.core.AMapException as AmapSearchException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Agent 宿主的导航设备端口。
 *
 * 本类只做一件机械的事：把高德 SDK 的同步/回调接口包成 Agent 能用的异步端口。
 * - [searchDestination]：先取一次新鲜定位，再用 [AmapSearchAdapter] 做关键字搜索，回调真实候选点；
 * - [startWalking]：先取一次新鲜定位，再用 [AmapNaviAdapter] 算步行路线并起 GPS 导航，之后把 SDK 的
 *   全部导航回调原样转发给调用方；
 * - [stopWalking]：停掉当前导航，包括还在等定位或算路的那一次，并断开当前回调。
 *
 * 边界：
 * - 没有任务状态机、没有目的地确认策略、没有模型决策：选哪个候选、要不要继续走，都由 Agent 层决定；
 * - 隐私没同意、定位不可用、搜索或算路失败一律显式失败，绝不编造候选，也不假装导航已经启动；
 * - 所有回调都在主线程触发；公开方法可以在任意线程调用。
 *
 * 只接 [Context]：本端口归前台 Service 所有，Activity 销毁重建不能把进行中的导航带走。
 */
class AgentNavigationDevice(private val context: Context) : AutoCloseable {

    private companion object {
        const val WORKER_THREAD_NAME = "leqi-agent-nav"
    }

    /** 一次地点搜索的结果。 */
    sealed class SearchResult {
        /** 搜索真的完成了；[candidates] 为空表示高德确实没有匹配的地点，不是失败。 */
        class Success(val candidates: List<AmapSearchAdapter.Candidate>) : SearchResult()

        /** 搜索没有完成。[code] 直接进 ToolResult.error，[message] 是可以展示给用户的原因。 */
        class Failure(val code: String, val message: String, val retryable: Boolean) : SearchResult()
    }

    /** 搜索回调；主线程触发，一次请求只会触发一次。 */
    fun interface SearchCallback {
        fun onSearch(result: SearchResult)
    }

    /** 步行导航回调；与高德导航 SDK 的回调一一对应，本类不合并、不取舍、不降级。 */
    interface NavigationCallback {
        fun onRouteReady(distanceM: Int, timeSec: Int)

        fun onRouteFailed(message: String)

        fun onNavigationText(text: String)

        fun onOffRoute()

        fun onRerouted()

        fun onLocationWeak(weak: Boolean, detail: String)

        fun onProgress(remainDistanceM: Int)

        fun onArrived()
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, WORKER_THREAD_NAME).apply { isDaemon = true }
    }

    /**
     * 当前那一次步行导航用的高德适配器；只在主线程读写。
     *
     * 每一次 [startWalk] 都新建一个，[stopWalking] 和 [close] 把旧的连监听一起退休（见
     * [retireAdapter]）。**不复用同一个实例**：高德自己只有一个 `calculating` 标志，复用的话，
     * 上一次算路的迟到回执会被当成这一次的回执，把这一次的路线当成上一次的结果去点 GPS 导航。
     */
    private var navigator: AmapNaviAdapter? = null

    /**
     * 当前那一次操作；只在主线程读写。
     *
     * 判定用“是哪一次操作”，而不是“有没有回调”：算路是异步的，高德回话说路线算好的时候，这一次操作
     * 可能已经被 [stopWalking] 停掉、或者被新的一次顶掉了。只有身份还对得上的那一次才允许去起 GPS 导航，
     * 否则就会出现“取消之后导航自己又走起来”。
     */
    private var activeWalk: WalkOperation? = null

    /**
     * 一次开展中的步行导航：认领它的调用方就是唯一能收到回调的那一位。
     *
     * [adapter] 是这一次操作专属的，回调桥在构造时就记住了“我是哪一次操作的回调”。所以一条迟到的
     * 算路回执同时要过两道身份检查——[activeWalk] 还得是它（没被停掉、没被顶替），桥认的也还是它
     * （不是“当前那个适配器”）——而不是拿着当前状态去猜这条回执属于谁。
     */
    private class WalkOperation(val callback: NavigationCallback) {
        var adapter: AmapNaviAdapter? = null
    }

    /** 已经释放过就不再接受新的请求，也不重新创建高德实例。 */
    @Volatile
    private var closed = false

    /**
     * 用一次新鲜定位做关键字搜索。
     *
     * [keyword] 为空时不碰任何 SDK，直接失败。回调在主线程触发，一定恰好触发一次。
     */
    fun searchDestination(keyword: String, callback: SearchCallback) {
        main.post { startSearch(keyword, callback) }
    }

    /**
     * 从当前位置开始走到 [toLatitude]/[toLongitude]。
     *
     * 起点是调用时刻重新取的定位，不是搜索时那一次。只传坐标不传候选：走哪个地点由 Agent 层决定。
     * 回调在主线程触发；算路失败或定位拿不到都走 [NavigationCallback.onRouteFailed]。
     */
    fun startWalking(toLatitude: Double, toLongitude: Double, callback: NavigationCallback) {
        main.post { startWalk(toLatitude, toLongitude, callback) }
    }

    /**
     * 停掉当前步行导航：还在算路的那一次也一并作废，之后的 SDK 回调不再转发给任何调用方。
     *
     * [onStopped] 在主线程触发，参数说明本机是不是真的停掉了一次进行中的导航：刚发出 [startWalking]
     * 但还没拿到定位的空档里，SDK 那边什么都还没开始，这时回 false——不假装停过。重复调用无副作用；
     * 本机本来就没在导航时也一定会回调，调用方不会等不到结果。
     */
    fun stopWalking(onStopped: (Boolean) -> Unit) {
        main.post {
            val hadNavigation = activeWalk != null || navigator?.isNavigating() == true
            // 先作废这一次操作，再退休适配器：之后哪怕算路的回执才到，桥也认不出这一次操作，
            // 它那个实例的监听也已经摘掉——不会“停完之后导航自己又走起来”。
            activeWalk = null
            retireAdapter()
            onStopped(hadNavigation)
        }
    }

    /** 释放导航实例和后台线程；Activity 退出时调用。重复调用无副作用。 */
    override fun close() {
        if (closed) return
        closed = true
        worker.shutdownNow()
        main.post {
            activeWalk = null
            navigator?.close()
            navigator = null
        }
    }

    private fun startSearch(keyword: String, callback: SearchCallback) {
        if (closed) return
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) {
            callback.onSearch(SearchResult.Failure("unsupported", "目的地关键词为空，未执行地点搜索", false))
            return
        }
        val blocked = privacyFailure()
        if (blocked != null) {
            callback.onSearch(blocked)
            return
        }
        PhoneLocationAdapter.requestFresh(context, object : PhoneLocationAdapter.Callback {
            override fun onFix(fix: PhoneLocationAdapter.Fix) {
                if (closed) return
                // 地点搜索是同步 SDK 调用，必须在后台线程跑。
                worker.execute { search(trimmed, fix, callback) }
            }

            override fun onFailure(reason: String) {
                callback.onSearch(SearchResult.Failure("location_weak", reason, true))
            }
        })
    }

    private fun search(keyword: String, fix: PhoneLocationAdapter.Fix, callback: SearchCallback) {
        val result = try {
            SearchResult.Success(
                AmapSearchAdapter.search(context, keyword, fix.latitude, fix.longitude),
            )
        } catch (error: AmapSearchException) {
            SearchResult.Failure("unknown", "高德地点搜索失败：" + describe(error), true)
        } catch (error: RuntimeException) {
            SearchResult.Failure("unknown", "高德地点搜索失败：" + describe(error), true)
        }
        main.post { callback.onSearch(result) }
    }

    private fun startWalk(toLatitude: Double, toLongitude: Double, callback: NavigationCallback) {
        if (closed) return
        if (!isUsableCoordinate(toLatitude, toLongitude)) {
            callback.onRouteFailed("目的地坐标不可用，未开始步行导航")
            return
        }
        val blocked = privacyFailure()
        if (blocked != null) {
            callback.onRouteFailed(blocked.message)
            return
        }
        // 现在就认领这一次操作：等待定位期间被停掉或被顶替，回调里都对不上身份，连算路都不会发。
        activeWalk = null
        retireAdapter()
        val operation = WalkOperation(callback)
        activeWalk = operation
        PhoneLocationAdapter.requestFresh(context, object : PhoneLocationAdapter.Callback {
            override fun onFix(fix: PhoneLocationAdapter.Fix) {
                if (closed || activeWalk !== operation) return
                try {
                    navigationAdapter(operation).calculateWalk(fix.latitude, fix.longitude, toLatitude, toLongitude)
                } catch (error: AmapMapException) {
                    fail(operation, "高德步行路线计算失败：" + describe(error))
                } catch (error: RuntimeException) {
                    fail(operation, "高德步行路线计算失败：" + describe(error))
                }
            }

            override fun onFailure(reason: String) {
                fail(operation, "出发前定位不可用，未开始导航：" + reason)
            }
        })
    }

    /** 只把失败交给仍然有效的那一次操作：已经作废的调用方不会再收到任何东西。 */
    private fun fail(operation: WalkOperation, reason: String) {
        if (activeWalk !== operation) return
        activeWalk = null
        operation.callback.onRouteFailed(reason)
    }

    /** 隐私这一关没过就直接失败，绝不去碰高德其它接口。 */
    private fun privacyFailure(): SearchResult.Failure? {
        return try {
            if (AmapPrivacy.ensureReady(context)) {
                null
            } else {
                SearchResult.Failure(
                    "permission_denied",
                    "用户未同意高德隐私政策，未调用高德定位与地图能力",
                    true,
                )
            }
        } catch (error: AmapPrivacyException) {
            SearchResult.Failure("unknown", error.message ?: "高德隐私登记失败", true)
        }
    }

    /** 需要时才建高德导航实例；创建和算路都必须在主线程。 */
    private fun retireAdapter() {
        val previous = navigator
        navigator = null
        previous?.close()
    }

    private fun navigationAdapter(operation: WalkOperation): AmapNaviAdapter {
        val existing = navigator
        if (existing != null) return existing
        return AmapNaviAdapter(context, NavigationBridge(operation)).also { navigator = it }
    }

    /** 高德在无坐标时会给出 (0,0) 或越界值，这类结果不能拿去算路线。 */
    private fun isUsableCoordinate(latitude: Double, longitude: Double): Boolean =
        latitude > -90 && latitude < 90 && longitude > -180 && longitude < 180 &&
            !(Math.abs(latitude) < 0.000001 && Math.abs(longitude) < 0.000001)

    private fun describe(error: Throwable): String {
        val message = error.message
        return if (message.isNullOrEmpty()) error.javaClass.simpleName
        else error.javaClass.simpleName + "：" + message
    }

    /** 把高德导航回调原样转给当前调用方；没有调用方在听就丢掉，不缓存也不补发。 */
    private inner class NavigationBridge(private val operation: WalkOperation) : AmapNaviAdapter.Listener {

        private fun currentCallback(): NavigationCallback? =
            if (!closed && activeWalk === operation) operation.callback else null

        override fun onRouteReady(distanceM: Int, timeSec: Int) {
            // 先认操作，再起 GPS：算路回执可能属于一次已经被停掉或被顶替的请求，那就什么都不做——
            // 绝不能在没有人等结果的时候把导航重新点着。
            if (currentCallback() == null) return
            val active = navigator ?: return
            // 高德算完路线只是拿到了路线，还要再起 GPS 导航才真的开始走；这是 SDK 自己的两步调用。
            if (!active.startGpsNavi()) {
                active.stop()
                fail(operation, "高德没有启动 GPS 步行导航")
                return
            }
            operation.callback.onRouteReady(distanceM, timeSec)
        }

        override fun onRouteFailed(message: String) {
            if (currentCallback() == null) return
            fail(operation, message)
        }

        override fun onNavigationText(text: String) {
            currentCallback()?.onNavigationText(text)
        }

        override fun onOffRoute() {
            currentCallback()?.onOffRoute()
        }

        override fun onRerouted() {
            currentCallback()?.onRerouted()
        }

        override fun onLocationWeak(weak: Boolean, detail: String) {
            currentCallback()?.onLocationWeak(weak, detail)
        }

        override fun onProgress(remainDistanceM: Int) {
            currentCallback()?.onProgress(remainDistanceM)
        }

        override fun onArrived() {
            currentCallback()?.onArrived()
        }
    }
}
