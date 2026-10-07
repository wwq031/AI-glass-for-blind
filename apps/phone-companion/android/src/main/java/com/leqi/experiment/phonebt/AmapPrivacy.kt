package com.leqi.experiment.phonebt

import android.content.Context
import com.amap.api.location.AMapLocationClient
import com.amap.api.maps.MapsInitializer
import com.amap.api.navi.NaviSetting
import com.amap.api.services.core.ServiceSettings

/**
 * 高德 SDK 隐私合规闸门。
 *
 * 高德要求：在调用任何地图/导航/搜索/定位能力之前，先向用户展示隐私政策并取得明确同意，
 * 再调用各组件自己的 `updatePrivacyShow` / `updatePrivacyAgree`。
 *
 * 所以本类把“是否同意”存在本地；没有同意时，[ensureReady] 返回 false，
 * 调用方必须直接失败并明确播报，**不得**去碰任何高德 SDK 的其它接口。
 * 注意：隐私标记只存在于内存里，每次进程启动都要重新登记，所以同意之后也要重新调用。
 */
object AmapPrivacy {

    private const val PREFS = "leqi_amap_privacy"
    private const val KEY_AGREED = "agreed"

    /** 高德开放平台隐私政策地址，展示给用户。 */
    const val POLICY_URL = "https://lbs.amap.com/pages/privacy/"

    /** 给用户看的隐私告知。内容是 SDK 真实会处理的数据，不夸大也不隐瞒。 */
    @JvmField
    val NOTICE: String = """
        本应用使用高德开放平台 Android 导航/搜索/定位 SDK 完成地点搜索和步行导航。

        为实现这些功能，高德 SDK 会收集并处理下列信息：
        · 设备信息（设备标识、系统版本、网络状态）；
        · 位置信息（经纬度、精度、时间），用于就近搜索和步行导航。

        这些信息由高德软件有限公司按其隐私政策处理，详见：
        $POLICY_URL

        不同意将无法使用地点搜索与导航；其余本地功能（眼镜录音经蓝牙传到手机、手机本地语音与图片识别）不受影响。
        请阅读上述政策后再决定是否同意。
    """.trimIndent()

    @JvmStatic
    fun isAgreed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AGREED, false)

    /** 用户点了“同意”：先落盘，再登记到 SDK。 */
    @JvmStatic
    @Throws(AmapPrivacyException::class)
    fun agree(context: Context) {
        prefs(context).edit().putBoolean(KEY_AGREED, true).apply()
        registerWithSdk(context)
    }

    /**
     * 调用任何高德能力之前都必须先过这一关。
     *
     * @return true 表示已同意且 SDK 隐私标记已登记；false 表示用户没有同意，调用方必须停止。
     */
    @JvmStatic
    @Throws(AmapPrivacyException::class)
    fun ensureReady(context: Context): Boolean {
        if (!isAgreed(context)) return false
        registerWithSdk(context)
        return true
    }

    /** 撤销同意：本地标记清掉，本次进程内不再使用高德能力。 */
    @JvmStatic
    fun revoke(context: Context) {
        prefs(context).edit().putBoolean(KEY_AGREED, false).apply()
    }

    @Synchronized
    @Throws(AmapPrivacyException::class)
    private fun registerWithSdk(context: Context) {
        val app = context.applicationContext
        val failures = ArrayList<String>()
        // 高德把地图、导航、搜索、定位做成四套独立组件，每个都要单独登记。
        record(failures, "地图") {
            MapsInitializer.updatePrivacyShow(app, true, true)
            MapsInitializer.updatePrivacyAgree(app, true)
        }
        record(failures, "导航") {
            NaviSetting.updatePrivacyShow(app, true, true)
            NaviSetting.updatePrivacyAgree(app, true)
        }
        record(failures, "搜索") {
            ServiceSettings.updatePrivacyShow(app, true, true)
            ServiceSettings.updatePrivacyAgree(app, true)
        }
        record(failures, "定位") {
            AMapLocationClient.updatePrivacyShow(app, true, true)
            AMapLocationClient.updatePrivacyAgree(app, true)
        }
        if (failures.isNotEmpty()) {
            throw AmapPrivacyException("高德隐私登记失败：" + failures.joinToString("、"))
        }
    }

    private inline fun record(failures: MutableList<String>, label: String, block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            failures.add(label + "(" + (error.message ?: error.javaClass.simpleName) + ")")
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** 高德隐私合规失败。调用方必须把它变成明确的语音提示，不能静默继续。 */
class AmapPrivacyException(message: String, cause: Throwable? = null) : Exception(message, cause)
