package com.leqi.experiment.phonebt

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import java.util.Locale

/**
 * 手机真机定位的 Kotlin 适配层。
 *
 * 只提供“新鲜且够准”的一次性定位：过旧、精度差、没有精度信息、坐标非法的一律当作失败，
 * 上层拿到失败就不搜索、不导航——绝不把旧位置当成当前位置用。
 * 回调发生在主线程。
 *
 * 只接 [Context] 而不是 Activity：宿主是前台 Service，定位请求不能被界面生命周期牵着走；
 * 权限仍然由 Activity 在可见时申请，本类只报告权限状态。
 *
 * 一次请求会按顺序问多个来源，谁先给出合格定位就用谁，全都拿不到才算失败：
 * 1. 高德定位 SDK——它自己走网络定位，在国内比系统的 network/fused 更有机会给出结果，
 *    返回的 GCJ-02 也和高德搜索/导航是同一个坐标系。要用户同意过隐私政策才碰，见 [AmapPrivacy]；
 * 2. 系统已启用的 fused / network / GPS，按剩下的时间平分。
 *
 * 只问第一个来源、拿不到就整单失败，会把“这个来源此刻没有结果”误判成“手机没有位置”：
 * 系统那几个来源在缓存过期后会直接回一个空结果，而不是等到下一次真正定上位。
 *
 * 合格与否只在一处判定（`Acquire.judge`），不看各来源自己的缓存和精度策略，
 * 所以本类与 [PhoneLocation] 一一对应：判定阈值、失败原因和成功数据形状都保持一致，
 * 不含任何业务状态机或 Agent 决策。
 */
object PhoneLocationAdapter {

    /** 位置超过这个时长就不再算“当前位置”。 */
    const val MAX_AGE_MS = 15_000L
    /** 精度差于这个米数就不用于搜索和导航。 */
    const val MAX_ACCURACY_M = 100f

    /** 一次合格定位；只有 [requestFresh] 判定通过才会产生。 */
    class Fix(
        val latitude: Double,
        val longitude: Double,
        val accuracyM: Float,
        val ageMs: Long,
    ) {
        /** 送给眼镜的定位事件负载。 */
        fun wirePayload(): String =
            String.format(Locale.ROOT, "%.6f,%.6f,%.1f,%d", latitude, longitude, accuracyM, ageMs)
    }

    /** 定位回调；两个方法都在主线程触发，且一次请求只会触发其中一个。 */
    interface Callback {
        fun onFix(fix: Fix)

        /** [reason] 是可以直接展示给用户的说明；调用方必须把它当作失败处理。 */
        fun onFailure(reason: String)
    }

    /** 要一次新鲜定位。可以在任意线程调用，回调仍在主线程。 */
    @JvmStatic
    fun requestFresh(context: Context, callback: Callback) {
        val handler = Handler(Looper.getMainLooper())
        // 高德客户端和系统定位回调都只认主线程，所以整台状态机都在主线程上跑。
        if (Looper.myLooper() == Looper.getMainLooper()) {
            begin(context, callback, handler)
        } else {
            handler.post { begin(context, callback, handler) }
        }
    }

    @JvmStatic
    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun begin(context: Context, callback: Callback, handler: Handler) {
        if (!hasPermission(context)) {
            callback.onFailure("手机没有定位权限")
            return
        }
        val manager = context.getSystemService(LocationManager::class.java)
        if (manager == null || !manager.isLocationEnabled) {
            callback.onFailure("手机定位未开启")
            return
        }
        val providers = enabledProviders(manager)
        // 用户同意过高德隐私政策才去碰高德定位；没同意就只用系统来源，一样是完整功能。
        val useAmap = amapAvailable(context)
        if (providers.isEmpty() && !useAmap) {
            callback.onFailure("手机没有可用定位来源")
            return
        }
        Acquire(context, callback, handler, manager, providers, useAmap).run()
    }

    /** 系统自己启用的定位来源，按优先级排；高德不在这里面，它在它们之前单独问。 */
    private fun enabledProviders(manager: LocationManager): List<String> {
        val enabled = manager.getProviders(true)
        val ordered = ArrayList<String>()
        for (candidate in arrayOf(
            LocationManager.FUSED_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
        )) {
            if (enabled.contains(candidate)) ordered.add(candidate)
        }
        return ordered
    }

    /** 隐私这一关过了、且 SDK 隐私标记登记成功，才允许去问高德定位。 */
    private fun amapAvailable(context: Context): Boolean =
        try {
            AmapPrivacy.ensureReady(context)
        } catch (error: AmapPrivacyException) {
            false
        }

    /**
     * 一次“要新鲜定位”的请求。
     *
     * 所有可变状态只在主线程读写：超时、系统回调、高德回调都先回到主线程再改状态，
     * 于是“回调恰好一次”只需要一个普通的布尔量就够了。
     */
    private class Acquire(
        private val context: Context,
        private val callback: PhoneLocationAdapter.Callback,
        private val handler: Handler,
        private val manager: LocationManager,
        private val providers: List<String>,
        private val useAmap: Boolean,
    ) {

        private companion object {
            /** 整次请求的兜底上限，避免底层一直不回调。 */
            const val TIMEOUT_MS = 20_000L
            /** 高德定位独占的时间份额；到点还没结果就让位给系统定位来源。 */
            const val AMAP_BUDGET_MS = 10_000L
            /** 一个来源分到的时间少于这个数就不值得再问了，直接按已有原因失败。 */
            const val MIN_SOURCE_BUDGET_MS = 2_500L
        }

        private var settled = false
        /** 每个来源失败的原因，按尝试顺序攒着，最后一起报给用户，方便对着日志查。 */
        private val rejections = ArrayList<String>()
        /** 当前还在等的来源，超时时用它说明卡在哪一步。 */
        private var pending: String? = null
        /** 每换一个来源自增一次；上一个来源晚到的回调拿它作废。 */
        private var stage = 0
        /** 已经问过几个系统来源。 */
        private var asked = 0
        private val cancellations = ArrayList<CancellationSignal>()
        private var amapClient: AMapLocationClient? = null
        /** 本轮高德定位发起的时刻，用来给拿回来的结果定年龄。 */
        private var amapStartedAt = 0L
        /** 结果回调可能在别的线程上收到，撤计时器要跨线程读它。 */
        @Volatile
        private var timer: Runnable? = null
        private var deadline = 0L

        private val overall = Runnable {
            // 超时时把还没回话的那个来源也记上，失败原因里才看得出卡在哪。
            val label = pending
            if (label != null) reject(label, "等待超时")
            finish("等待手机定位超时" + describeRejections())
        }

        fun run() {
            deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
            handler.postDelayed(overall, TIMEOUT_MS)
            if (useAmap) askAmap() else askNextProvider()
        }

        /** 高德定位：整个请求只问一次，到点就让位给系统来源。 */
        private fun askAmap() {
            if (settled) return
            pending = "高德"
            val client = try {
                AMapLocationClient(context)
            } catch (error: Throwable) {
                reject("高德", "初始化失败")
                askNextProvider()
                return
            }
            amapClient = client
            val token = stage
            try {
                val option = AMapLocationClientOption()
                option.setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy)
                option.setOnceLocation(true)
                option.setOnceLocationLatest(true)
                option.setNeedAddress(false)
                option.setMockEnable(false)
                client.setLocationOption(option)
                client.setLocationListener(object : AMapLocationListener {
                    override fun onLocationChanged(location: AMapLocation?) {
                        handler.post { onAmapResult(token, location) }
                    }
                })
                amapStartedAt = SystemClock.elapsedRealtime()
                schedule(AMAP_BUDGET_MS) {
                    reject("高德", "等待超时")
                    askNextProvider()
                }
                client.startLocation()
            } catch (error: Throwable) {
                clearTimer()
                reject("高德", "启动失败（" + error.javaClass.simpleName + "）")
                askNextProvider()
            }
        }

        private fun onAmapResult(token: Int, location: AMapLocation?) {
            if (settled || token != stage) return
            clearTimer()
            if (location == null) {
                reject("高德", "没有返回位置")
                askNextProvider()
                return
            }
            val code = location.getErrorCode()
            if (code != 0) {
                reject("高德", "错误码 " + code + " " + text(location.getErrorInfo()))
                askNextProvider()
                return
            }
            val accuracy = location.getAccuracy()
            val fixTime = location.getTime()
            val wallAge = System.currentTimeMillis() - fixTime
            if (fixTime <= 0L || wallAge < -5_000L) {
                reject("高德", "没有可信的定位时间")
                askNextProvider()
                return
            }
            val ageMs = maxOf(wallAge, SystemClock.elapsedRealtime() - amapStartedAt)
            deliver(
                "高德",
                judge(
                    location.getLatitude(),
                    location.getLongitude(),
                    accuracy,
                    // 高德不给“有没有精度”这个开关，0 就是没有。
                    accuracy > 0f,
                    ageMs,
                ),
            )
        }

        /** 换下一个系统定位来源；没有来源可换或时间不够就按已有原因失败。 */
        private fun askNextProvider() {
            if (settled) return
            clearTimer()
            releaseAmap()
            if (asked >= providers.size) {
                finish("没有拿到可用定位" + describeRejections())
                return
            }
            val share = (deadline - SystemClock.elapsedRealtime()) / (providers.size - asked)
            if (share < MIN_SOURCE_BUDGET_MS) {
                finish("没时间再问其它定位来源了" + describeRejections())
                return
            }
            val provider = providers[asked++]
            val label = providerLabel(provider)
            pending = label
            stage++
            val token = stage
            val signal = CancellationSignal()
            cancellations.add(signal)
            schedule(share) {
                reject(label, "等待超时")
                signal.cancel()
                askNextProvider()
            }
            try {
                // 回调回到主线程：整个判定状态机只在主线程读写。
                manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                    handler.post { onProviderResult(token, label, location) }
                }
            } catch (error: SecurityException) {
                clearTimer()
                reject(label, "权限不可用")
                askNextProvider()
            } catch (error: RuntimeException) {
                clearTimer()
                reject(label, "请求失败（" + error.javaClass.simpleName + "）")
                askNextProvider()
            }
        }

        private fun onProviderResult(token: Int, label: String, location: Location?) {
            if (settled || token != stage) return
            clearTimer()
            if (location == null) {
                // 这个来源此刻给不出结果，不代表手机没有定位能力：换下一个来源继续问。
                reject(label, "没有返回位置")
                askNextProvider()
                return
            }
            val nanos = location.elapsedRealtimeNanos
            if (nanos <= 0L) {
                reject(label, "没有时间信息")
                askNextProvider()
                return
            }
            val ageMs = maxOf(0L, (SystemClock.elapsedRealtimeNanos() - nanos) / 1_000_000)
            deliver(
                label,
                judge(location.latitude, location.longitude, location.accuracy, location.hasAccuracy(), ageMs),
            )
        }

        /** 一个来源给了结果：合格就收工，不合格就记下原因继续问下一个。 */
        private fun deliver(label: String, verdict: Verdict) {
            when (verdict) {
                is Verdict.Ok -> succeed(verdict.fix)
                is Verdict.Rejected -> {
                    reject(label, verdict.reason)
                    askNextProvider()
                }
            }
        }

        /**
         * 唯一的合格判定：坐标、新鲜度、精度三项都过才算数。
         * 各来源自己的缓存和精度策略都不作数，同一个位置不会因为来源不同被判成两个结果。
         */
        private fun judge(
            latitude: Double,
            longitude: Double,
            accuracyM: Float,
            hasAccuracy: Boolean,
            ageMs: Long,
        ): Verdict {
            // (0,0)、越界和 NaN 都是“其实没有定位”的常见写法，不能拿去当搜索和导航的起点。
            if (!isUsableCoordinate(latitude, longitude)) return Verdict.Rejected("坐标不可用")
            if (ageMs > PhoneLocationAdapter.MAX_AGE_MS) {
                return Verdict.Rejected("定位过旧（" + (ageMs / 1000) + " 秒前）")
            }
            if (!hasAccuracy || !accuracyM.isFinite() || accuracyM <= 0f) return Verdict.Rejected("没有有效精度信息")
            if (accuracyM > PhoneLocationAdapter.MAX_ACCURACY_M) {
                return Verdict.Rejected("精度不足（" + Math.round(accuracyM) + " 米）")
            }
            return Verdict.Ok(PhoneLocationAdapter.Fix(latitude, longitude, accuracyM, ageMs))
        }

        /** 高德和系统都会在无坐标时给出 (0,0) 或越界值，这类结果不能拿去算路线。 */
        private fun isUsableCoordinate(latitude: Double, longitude: Double): Boolean =
            latitude > -90 && latitude < 90 && longitude > -180 && longitude < 180 &&
                !(Math.abs(latitude) < 0.000001 && Math.abs(longitude) < 0.000001)

        private fun succeed(fix: PhoneLocationAdapter.Fix) {
            if (settled) return
            settled = true
            release()
            callback.onFix(fix)
        }

        private fun finish(reason: String) {
            if (settled) return
            settled = true
            release()
            callback.onFailure(reason)
        }

        private fun reject(label: String, reason: String) {
            rejections.add(label + "失败（" + reason + "）")
        }

        private fun describeRejections(): String =
            if (rejections.isEmpty()) "" else "：" + rejections.joinToString("；")

        private fun text(value: String?): String = if (value.isNullOrEmpty()) "无说明" else value

        /** 给已经失败的原因定个名，方便用户和日志对上号。 */
        private fun providerLabel(provider: String): String = when (provider) {
            LocationManager.FUSED_PROVIDER -> "融合定位"
            LocationManager.NETWORK_PROVIDER -> "网络定位"
            LocationManager.GPS_PROVIDER -> "GPS 定位"
            else -> provider
        }

        /** 给当前来源挂一个限时；结果先到就用结果，计时先到就换下一个来源。 */
        private fun schedule(budgetMs: Long, onExpired: () -> Unit) {
            val token = stage
            val runnable = Runnable {
                timer = null
                if (!settled && token == stage) onExpired()
            }
            timer = runnable
            handler.postDelayed(runnable, budgetMs)
        }

        /**
         * 结果一到就先撤掉本轮的限时，保证“先到的那个说了算”：
         * 计时器只是排进队列还没执行时撤掉，它就不会再跳出来把已经拿到的结果判成超时。
         */
        private fun clearTimer() {
            val runnable = timer ?: return
            timer = null
            handler.removeCallbacks(runnable)
        }

        /** 取消所有还在等的请求并放掉 SDK 资源；重复调用无副作用。 */
        private fun release() {
            clearTimer()
            handler.removeCallbacks(overall)
            for (signal in cancellations) signal.cancel()
            cancellations.clear()
            releaseAmap()
        }

        /** 高德客户端只在主线程创建，也只在主线程释放。 */
        private fun releaseAmap() {
            val client = amapClient ?: return
            amapClient = null
            // 放资源时 SDK 再抛异常也不能影响结果回调，否则这次请求就永远不回调了。
            try {
                client.stopLocation()
                client.onDestroy()
            } catch (error: Throwable) {
                // 已经没有别的补救动作，忽略。
            }
        }

        /** 判定结果：合格给出 [PhoneLocationAdapter.Fix]，不合格给出可以直接播报的原因。 */
        private sealed class Verdict {
            class Ok(val fix: PhoneLocationAdapter.Fix) : Verdict()

            class Rejected(val reason: String) : Verdict()
        }
    }
}
