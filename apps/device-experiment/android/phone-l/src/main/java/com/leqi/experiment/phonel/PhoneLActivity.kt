package com.leqi.experiment.phonel

import android.app.Activity
import android.app.AlertDialog
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.ImageView
import android.widget.EditText
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import com.rokid.cxr.session.CxrSessionManager
import com.rokid.cxr.session.RokidAppStatus

/** Standalone diagnostic entry; no Agent, model, TTS or map initialization. */
class PhoneLActivity : Activity() {
    private lateinit var manager: CxrSessionManager
    private lateinit var status: TextView
    private lateinit var probe: CxrlSessionProbe
    private lateinit var trace: TextView
    private val lines = ArrayDeque<String>()
    private val exporter = Executors.newSingleThreadExecutor()
    private var exportPending = false
    private var glassesFirmware = "unrecorded"

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
        action("手动恢复（关闭旧连接，重新授权）") { withBluetoothPermission { probe.manualRecover() } }
        action("测试口径：冷启动") { probe.setStartKind("cold") }
        action("测试口径：热运行") { probe.setStartKind("warm") }
        action("记录眼镜固件版本") {
            val value = EditText(this).apply { hint = "固件版本，不填序列号或账号"; setText(glassesFirmware) }
            AlertDialog.Builder(this).setTitle("本次设备版本记录").setView(value)
                .setPositiveButton("保存") { _, _ -> glassesFirmware = value.text.toString().trim().take(120) }
                .setNegativeButton("取消", null).show()
        }
        action("导出脱敏验收记录 JSON") {
            exportPending = true
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "cxrl-mvp-records.json")
            }, 3001)
        }
        action("拍照：打开本次确认窗口") {
            probe.preparePhoto { request -> AlertDialog.Builder(this)
                .setTitle("确认本次眼镜拍照")
                .setMessage("仅拍摄一张照片，在手机内存中预览；有效图片到达后自动发送明确标记的 Mock 文本到眼镜。请求：${request.id}")
                .setPositiveButton("确认拍照") { _, _ -> probe.confirmPhoto(request.id) }
                .setNegativeButton("取消") { _, _ -> probe.cancelPhoto() }
                .setOnCancelListener { probe.cancelPhoto() }
                .show()
            }
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
            }, { photo -> image.setImageBitmap(photo?.bitmap) }, versions = { recordVersions() })
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
    @Deprecated("CXR-L launches authorization through startActivityForResult.")
    override fun startActivityForResult(intent: Intent, requestCode: Int) {
        super.startActivityForResult(intent, requestCode)
        if (::probe.isInitialized && intent.component?.className ==
            "com.rokid.sprite.aiapp.externalapp.auth.AuthorizationActivity") {
            probe.onAuthorizationActivityLaunched(requestCode)
        }
    }

    @Suppress("DEPRECATION")
    @Deprecated("Official CXR-L authorization requires forwarding the Activity result.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 3001) {
            val uri = data?.data
            if (exportPending && resultCode == RESULT_OK && uri != null && ::probe.isInitialized) {
                val snapshot = probe.exportRecords()
                val firmware = glassesFirmware
                exporter.execute {
                    try {
                        val digest = MessageDigest.getInstance("SHA-256")
                        File(applicationInfo.sourceDir).inputStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
                        }
                        val metadata = recordVersions(firmware)
                            .put("apk_sha256", digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
                        snapshot.put("metadata", metadata)
                        contentResolver.openOutputStream(uri, "wt")?.use { output ->
                            output.write(snapshot.toString(2).toByteArray(Charsets.UTF_8))
                        } ?: error("No output stream")
                        runOnUiThread { if (!isDestroyed) status.text = "脱敏记录已导出；仍需人工复核真机证据。" }
                    } catch (error: Exception) {
                        runOnUiThread { if (!isDestroyed) status.text = "记录导出失败：${error.javaClass.simpleName}" }
                    }
                }
            }
            exportPending = false
            return
        }
        if (::probe.isInitialized) probe.onActivityResult(requestCode, resultCode, data)
    }

    private fun recordVersions(firmware: String = glassesFirmware): JSONObject {
        val metadata = JSONObject().put("app_version", BuildConfig.VERSION_NAME)
            .put("source_revision", BuildConfig.SOURCE_REVISION).put("sdk_version", "1.1.2")
            .put("phone_model", Build.MODEL).put("phone_api", Build.VERSION.SDK_INT)
            .put("glasses_firmware", firmware)
        for (name in listOf("com.rokid.sprite.aiapp", "com.rokid.sprite.global.aiapp", "com.rokid.uxr")) {
            try {
                @Suppress("DEPRECATION")
                val info = packageManager.getPackageInfo(name, 0)
                metadata.put("official_app_package", name).put("official_app_version", info.versionName)
                    .put("official_app_version_code", info.longVersionCode)
                break
            } catch (_: PackageManager.NameNotFoundException) { }
        }
        return metadata
    }

    override fun onDestroy() {
        if (::probe.isInitialized) probe.destroy()
        exporter.shutdown()
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
