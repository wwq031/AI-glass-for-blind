package com.leqi.experiment.phonel

import android.app.Activity
import android.app.AlertDialog
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.ImageView
import com.rokid.cxr.session.CxrSessionManager
import com.rokid.cxr.session.RokidAppStatus

/** Standalone diagnostic entry; no Agent, model, TTS or map initialization. */
class PhoneLActivity : Activity() {
    private lateinit var manager: CxrSessionManager
    private lateinit var status: TextView
    private lateinit var probe: CxrlSessionProbe
    private lateinit var trace: TextView
    private val lines = ArrayDeque<String>()

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
        fun action(label: String, run: () -> Unit) {
            content.addView(Button(this).apply {
                text = label
                setOnClickListener { if (::probe.isInitialized) run() }
            })
        }
        action("请求官方授权（不拍照）") { withBluetoothPermission { probe.authorize() } }
        action("创建并连接 CUSTOM_VIEW Session") { withBluetoothPermission { probe.connect() } }
        action("刷新 Session 与蓝牙状态") { withBluetoothPermission { probe.refresh() } }
        action("关闭 Session / 取消授权") { probe.stop() }
        action("手动恢复：重新请求授权") { withBluetoothPermission { probe.manualRecover() } }
        action("拍照：打开本次确认窗口") {
            val request = probe.armPhoto()
            if (request != null) AlertDialog.Builder(this)
                .setTitle("确认本次眼镜拍照")
                .setMessage("仅拍摄一张照片，在手机内存中预览；有效图片到达后自动发送明确标记的 Mock 文本到眼镜。请求：${request.id}")
                .setPositiveButton("确认拍照") { _, _ -> probe.confirmPhoto(request.id) }
                .setNegativeButton("取消") { _, _ -> probe.cancelPhoto() }
                .setOnCancelListener { probe.cancelPhoto() }
                .show()
        }
        action("取消本次图片接收") { probe.cancelPhoto() }
        action("人工确认眼镜看见本次 Mock 文本") {
            AlertDialog.Builder(this).setTitle("仅在现场实际看见后确认")
                .setMessage("请核对眼镜 Mock 文本中的请求代号与下方手机记录一致。发送成功或手机预览不能替代此项。")
                .setPositiveButton("已看见对应文本") { _, _ -> probe.confirmVisible() }
                .setNegativeButton("未确认", null).show()
        }
        val image = ImageView(this).apply { adjustViewBounds = true }
        content.addView(image, LinearLayout.LayoutParams(-1, 360))
        trace = TextView(this).apply { textSize = 14f }
        content.addView(trace)
        setContentView(ScrollView(this).apply { addView(content) })
        try {
            manager = CxrSessionManager.getInstance(applicationContext)
            probe = CxrlSessionProbe(this, manager, { line ->
                if (lines.size == 100) lines.removeFirst()
                lines.addLast(line)
                trace.text = lines.joinToString("\n")
            }, { photo -> image.setImageBitmap(photo?.bitmap) })
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
            status.text = "CXR-L 1.1.2 独立 Mock 通信调试\n" +
                "官方 App 安装：$installed\n版本兼容：$compatible\n" +
                "Session 阶段见下方脱敏记录；拍照、显示：未验收\n" +
                "请先在官方 App 完成真实连接与拍照检查。"
        } catch (error: Exception) {
            status.text = "环境检查失败：${error.javaClass.simpleName}"
        }
    }

    private fun withBluetoothPermission(action: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 2001)
            // Permission delivery does not automatically reconnect or reauthorize.
            status.text = "请允许蓝牙权限后再次点击诊断操作；拒绝时不能读取眼镜蓝牙状态。"
        }
    }

    @Suppress("DEPRECATION")
    @Deprecated("Official CXR-L authorization requires forwarding the Activity result.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (::probe.isInitialized) probe.onActivityResult(resultCode, data)
    }

    override fun onDestroy() {
        if (::probe.isInitialized) probe.destroy()
        super.onDestroy()
    }

    override fun onStop() {
        if (::probe.isInitialized) probe.hostBackground()
        super.onStop()
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
