package com.leqi.experiment.phonel

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.rokid.cxr.session.CxrSessionManager
import com.rokid.cxr.session.RokidAppStatus

/** Standalone diagnostic entry; no Agent, model, TTS or map initialization. */
class PhoneLActivity : Activity() {
    private lateinit var manager: CxrSessionManager
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        status = TextView(this).apply { textSize = 18f }
        content.addView(status)
        content.addView(Button(this).apply {
            text = "重新检查官方 App"
            setOnClickListener { checkEnvironment() }
        })
        content.addView(Button(this).apply {
            text = "打开官方 App 核对连接与拍照"
            setOnClickListener { openCompanion() }
        })
        setContentView(ScrollView(this).apply { addView(content) })
        try {
            manager = CxrSessionManager.getInstance(applicationContext)
            checkEnvironment()
        } catch (error: Exception) {
            // Exception messages may contain SDK credentials or device identifiers.
            status.text = "SDK 初始化失败：${error.javaClass.simpleName}"
        }
    }

    override fun onResume() {
        super.onResume()
        if (::manager.isInitialized) checkEnvironment()
    }

    private fun checkEnvironment() {
        if (!::manager.isInitialized) return
        try {
            val installed = manager.isRokidAppInstalled(applicationContext)
            val compatibility = manager.checkRokidAppCompatibility(applicationContext)
            val compatible = compatibility is RokidAppStatus.Compatible
            status.text = "CXR-L 1.1.2 独立探针\n" +
                "官方 App 安装：$installed\n版本兼容：$compatible\n" +
                "SDK Session：未创建\n连接、拍照、显示：未验收\n" +
                "请先在官方 App 完成真实连接与拍照检查。"
        } catch (error: Exception) {
            status.text = "环境检查失败：${error.javaClass.simpleName}"
        }
    }

    private fun openCompanion() {
        for (name in listOf("com.rokid.sprite.aiapp", "com.rokid.sprite.global.aiapp", "com.rokid.uxr")) {
            val launch = packageManager.getLaunchIntentForPackage(name) ?: continue
            try {
                startActivity(launch)
            } catch (error: Exception) {
                status.text = "官方 App 启动失败：${error.javaClass.simpleName}"
            }
            return
        }
        status.text = "未找到可启动的官方 App；请安装匹配版本后重试。"
    }
}
