package com.leqi.experiment.phonel

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.leqi.experiment.phonebt.AgentNavigationDevice
import com.leqi.experiment.phonebt.AmapPrivacy
import java.security.MessageDigest

/** 只在本进程保留最近一次鉴权失败的地点，换查询或结束会话时清空。 */
internal object AmapNavigationPanel {
    var failedQuery: String? = null

    fun hasKey(context: Context): Boolean =
        !context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            .metaData?.getString("com.amap.api.v2.apikey").isNullOrBlank()

    fun searchIntent(query: String): Intent = Intent(Intent.ACTION_VIEW,
        Uri.Builder().scheme("androidamap").authority("poi")
            .appendQueryParameter("sourceApplication", "乐奇助盲")
            .appendQueryParameter("keywords", query).appendQueryParameter("dev", "0").build())
        .setPackage("com.autonavi.minimap")

    fun canOpen(context: Context): Boolean =
        searchIntent("地图").resolveActivity(context.packageManager) != null

    fun show(activity: Activity) {
        val info = activity.packageManager.getPackageInfo(activity.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signatures = info.signingInfo?.apkContentsSigners.orEmpty()
        val sha1 = signatures.joinToString("\n") { signature ->
            MessageDigest.getInstance("SHA-1").digest(signature.toByteArray()).joinToString(":") { "%02X".format(it) }
        }
        val container = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 16) }
        container.addView(TextView(activity).apply {
            text = "包名：${activity.packageName}\n签名 SHA-1：$sha1\n高德 Android Key：" +
                (if (hasKey(activity)) "已装入，可测试鉴权" else "未配置")
            setTextIsSelectable(true)
        })
        val place = EditText(activity).apply {
            hint = "输入地点名称"; setSingleLine(true); setText(failedQuery); isSaveEnabled = false
        }
        val result = TextView(activity).apply { accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE }
        container.addView(place); container.addView(result)
        val dialog = AlertDialog.Builder(activity).setTitle("高德导航设置").setView(container)
            .setPositiveButton("测试地点搜索", null).setNegativeButton("关闭", null)
            .setNeutralButton("用高德地图搜索", null).create()
        val navigation = AgentNavigationDevice(activity.applicationContext)
        dialog.setOnDismissListener { navigation.close() }
        dialog.setOnShowListener {
            val test = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            test.setOnClickListener {
                val query = place.text.toString().trim()
                when {
                    query.isEmpty() -> place.error = "请输入地点名称"
                    !hasKey(activity) -> result.text = "请先配置当前安装包的高德 Android Key。"
                    !AmapPrivacy.isAgreed(activity) -> result.text = "请先在连接眼镜时阅读并同意导航服务说明。"
                    else -> {
                        test.isEnabled = false
                        result.text = "正在定位并搜索…"
                        navigation.searchDestination(query) { found ->
                            if (!dialog.isShowing) return@searchDestination
                            test.isEnabled = true
                            result.text = when (found) {
                                is AgentNavigationDevice.SearchResult.Success -> if (found.candidates.isEmpty())
                                    "搜索请求成功，附近没有匹配地点。" else "搜索成功：" + found.candidates.joinToString("、") { it.name }
                                is AgentNavigationDevice.SearchResult.Failure -> found.message
                            }
                        }
                    }
                }
            }
            val open = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            open.isEnabled = canOpen(activity)
            open.setOnClickListener {
                val query = place.text.toString().trim()
                if (query.isEmpty()) { place.error = "请输入地点名称"; return@setOnClickListener }
                try {
                    activity.startActivity(searchIntent(query))
                } catch (_: ActivityNotFoundException) { result.text = "手机无法打开高德地图。" }
            }
        }
        dialog.show()
    }
}
