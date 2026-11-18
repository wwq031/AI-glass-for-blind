package com.leqi.experiment.phonel

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.EditText
import android.widget.Button
import android.widget.TextView
import com.leqi.experiment.phonebt.AgentNavigationDevice
import com.leqi.experiment.phonebt.AmapPrivacy
import android.widget.ScrollView
import com.leqi.experiment.phonebt.AmapSearchAdapter

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
        val container = activity.column().apply { pad(20, 10) }
        container.item(activity.label("按当前位置搜索，选择地点后查看步行路线。", 16, muted))
        val place = EditText(activity).apply {
            hint = "目的地名称"; textSize = 22f; minHeight = activity.dp(72); setSingleLine(true);
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH; setText(failedQuery); isSaveEnabled = false
        }
        val result = TextView(activity).apply { accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE }
        val choices = activity.column()
        container.item(place, 10)
        val dialog = AlertDialog.Builder(activity).setTitle("附近地点").setView(ScrollView(activity).apply { addView(container) })
            .create()
        val navigation = AgentNavigationDevice(activity.applicationContext)
        dialog.setOnDismissListener { navigation.close() }
        lateinit var search: Button
        search = activity.action("搜索附近地点", forest, android.graphics.Color.WHITE) {
            val query = place.text.toString().trim()
            when {
                query.isEmpty() -> place.error = "请输入地点名称"
                !hasKey(activity) -> result.text = "请先配置当前安装包的高德 Android Key。"
                !AmapPrivacy.isAgreed(activity) -> result.text = "请先在连接眼镜时阅读并同意导航服务说明。"
                else -> {
                    activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(place.windowToken, 0)
                    search.isEnabled = false
                    choices.removeAllViews()
                    place.isEnabled = false
                    result.text = "正在定位并搜索…"
                    navigation.searchDestination(query) { found ->
                        if (!dialog.isShowing) return@searchDestination
                        search.isEnabled = true; place.isEnabled = true
                        result.text = when (found) {
                            is AgentNavigationDevice.SearchResult.Success -> {
                                for (candidate in found.candidates) {
                                    val distance = if (candidate.distanceM > 0) "约 ${candidate.distanceM} 米" else ""
                                    val detail = listOf(candidate.name, distance, candidate.address).filter { it.isNotBlank() }.joinToString("\n")
                                    choices.item(activity.action(detail, paper, ink) {
                                        openWalking(activity, candidate, result)
                                    }, 6)
                                }
                                if (found.candidates.isEmpty()) "附近没有匹配地点，请换个名称。" else "找到 ${found.candidates.size} 个地点，请选择目的地。"
                            }
                            is AgentNavigationDevice.SearchResult.Failure -> found.message
                        }
                    }
                }
            }
        }
        container.item(search, 8)
        container.item(result, 8); container.item(choices)
        place.setOnEditorActionListener { _, action, _ ->
            action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH && search.isEnabled && search.performClick()
        }
        if (canOpen(activity)) container.item(activity.action("用高德地图搜索", paper, ink) {
            val query = place.text.toString().trim()
            if (query.isEmpty()) { place.error = "请输入地点名称"; return@action }
            try {
                activity.startActivity(searchIntent(query))
            } catch (_: ActivityNotFoundException) { result.text = "手机无法打开高德地图。" }
        }, 8)
        container.item(activity.action("返回首页", paper, ink) { dialog.dismiss() }, 8)
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        dialog.show()
    }
    private fun openWalking(activity: Activity, point: AmapSearchAdapter.Candidate, result: TextView) {
        val uri = Uri.Builder().scheme("amapuri").authority("route").path("/plan/")
            .appendQueryParameter("sourceApplication", "乐奇助盲").appendQueryParameter("dlat", point.latitude.toString())
            .appendQueryParameter("dlon", point.longitude.toString()).appendQueryParameter("dname", point.name)
            .appendQueryParameter("dev", "0").appendQueryParameter("t", "2").build()
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.autonavi.minimap"))
        } catch (_: ActivityNotFoundException) {
            val web = Uri.parse("https://uri.amap.com/navigation").buildUpon()
                .appendQueryParameter("to", "${point.longitude},${point.latitude},${point.name}")
                .appendQueryParameter("mode", "walk").appendQueryParameter("coordinate", "gaode").build()
            try { activity.startActivity(Intent(Intent.ACTION_VIEW, web)) }
            catch (_: ActivityNotFoundException) { result.text = "请安装高德地图或浏览器以查看步行路线。" }
        }
    }

}
