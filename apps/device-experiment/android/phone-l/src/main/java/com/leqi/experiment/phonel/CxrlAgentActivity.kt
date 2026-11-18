package com.leqi.experiment.phonel

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import com.leqi.experiment.phonebt.AmapPrivacy
import com.rokid.cxr.session.*

/** 手机只负责首次授权和故障恢复；任务开始、录音确认、拍照确认均在眼镜完成。 */
class CxrlAgentActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var home: GuideHome
    private val render: (GuideState) -> Unit = { home.render(it) }
    private lateinit var manager: CxrSessionManager
    private var requesting = false
    private var destroyed = false
    private var diagnosticMode = false
    private var textDiagnostics = false
    private var diagnosticDevice: CxrlAgentDevice? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = CxrSessionManager.getInstance(applicationContext)
        home = GuideHome(this, { connect() }, ::disconnect, ::settings, { read ->
            if (!AgentModelSettings(this).hasKey) modelSettings()
            else startActivity(Intent(this, PictureActivity::class.java).putExtra("read", read))
        }, ::navigationSettings)
        status = home.feedback
    }

    override fun onStart() { super.onStart(); GuideSession.watch(render) }
    override fun onStop() { GuideSession.remove(render); super.onStop() }

    private fun disconnect() {
        requesting = false
        diagnosticDevice?.close(); diagnosticDevice = null
        stopService(Intent(this, CxrlAgentService::class.java))
        GuideSession.show(GuideState())
        status.text = "任务已停止，眼镜已断开。"
    }

    private fun settings() {
        val controls = column().apply { pad(16, 8) }
        val sheet = AlertDialog.Builder(this).setTitle("设置与使用帮助")
            .setView(ScrollView(this).apply { addView(controls) }).create()
        fun entry(title: String, open: () -> Unit) {
            controls.item(action(title, paper, ink) { sheet.dismiss(); open() }, 8)
        }
        entry("眼镜使用方法") {
            AlertDialog.Builder(this).setTitle("使用眼镜助手")
                .setMessage("连接眼镜，等待就绪提示。\n\n短按开始，听到提示后再短按录音，说完短按结束。\n\n你可以说“描述眼前的场景”“读一下这张纸”或“导航到附近的地铁站”。按眼镜提示确认拍照或目的地。\n\n结束时，在手机首页选“停止任务与连接”。")
                .setPositiveButton("知道了", null).show()
        }
        entry("最近一次对话") {
            val session = GuideSession.state
            val last = listOfNotNull(session.question.takeIf { it.isNotBlank() }?.let { "你说：$it" },
                session.answer.takeIf { it.isNotBlank() }?.let { "助手说：$it" }).joinToString("\n\n")
            AlertDialog.Builder(this).setTitle("最近一次对话").setMessage(last.ifEmpty { "当前没有对话。" })
                .setPositiveButton("关闭", null).show()
        }
        entry("对话模型与费用设置", ::modelSettings)
        if (BuildConfig.DEVELOPER_TOOLS && BuildConfig.DEBUG) {
            entry("开发诊断") {
                AlertDialog.Builder(this).setTitle("开发诊断")
                    .setMultiChoiceItems(arrayOf("下次连接记录识别与播报文字"), booleanArrayOf(textDiagnostics)) { _, _, checked -> textDiagnostics = checked }
                    .setPositiveButton("完成", null).setNeutralButton("戒指输入诊断") { _, _ -> connect(true) }.show()
            }
        }
        controls.item(action("返回首页", paper, ink) { sheet.dismiss() }, 8)
        sheet.show()
    }

    private fun navigationSettings() {
        if (!AmapPrivacy.isAgreed(this)) {
            AlertDialog.Builder(this).setTitle("导航服务说明").setMessage(AmapPrivacy.NOTICE)
                .setPositiveButton("同意并继续") { _, _ ->
                    runCatching { AmapPrivacy.agree(this) }.fold({ navigationSettings() },
                        { status.text = "导航授权准备失败，请重新打开导航设置。" })
                }.setNegativeButton("取消", null).show()
            return
        }
        if (!com.leqi.experiment.phonebt.PhoneLocationAdapter.hasPermission(this)) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), 502)
            return
        }
        AmapNavigationPanel.show(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 501 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) connect(diagnosticMode)
        if (requestCode == 502) {
            if (com.leqi.experiment.phonebt.PhoneLocationAdapter.hasPermission(this)) AmapNavigationPanel.show(this)
            else status.text = "搜索附近地点需要定位权限，可在系统设置中开启。"
        }
    }

    private fun modelSettings() {
        val settings = AgentModelSettings(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 16) }
        content.addView(TextView(this).apply {
            text = "DeepSeek 提供对话、语音识别、播报和看图。录音、确认拍摄的照片、对话及播报文字会发送到 DeepSeek，按用量收费；使用时需要联网。\n本机记录：${settings.totalTokens} tokens，转写 ${settings.asrMs / 1000.0} 秒，合成 ${settings.ttsCharacters} 字符，OCR ${settings.ocrPages} 页。以 DeepSeek 账单为准。"
        })
        val models = RadioGroup(this)
        models.addView(RadioButton(this).apply { id = 101; text = "Flash：费用较低，默认推荐" })
        models.addView(RadioButton(this).apply { id = 102; text = "Pro：更强推理，费用较高" })
        models.check(if (settings.model == AgentModelSettings.PRO) 102 else 101)
        content.addView(models)
        val key = EditText(this).apply {
            contentDescription = if (settings.hasKey) "DeepSeek API 密钥，已保存，可留空" else "DeepSeek API 密钥，必填"
            hint = if (settings.hasKey) "已保存密钥；留空保留，输入则替换" else "在这里填写你的 DeepSeek API Key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            isSaveEnabled = false
        }
        content.addView(key)
        val feedback = TextView(this)
        content.addView(feedback)
        val dialog = AlertDialog.Builder(this).setTitle("对话模型与费用")
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("保存", null).setNegativeButton("取消", null)
            .setNeutralButton("清除密钥", null).create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (key.text.isNullOrBlank() && !settings.hasKey) {
                    feedback.text = "请先填写 DeepSeek API Key"; return@setOnClickListener
                }
                val saved = runCatching { settings.save(
                    if (models.checkedRadioButtonId == 102) AgentModelSettings.PRO else AgentModelSettings.FLASH,
                    key.text.toString()) }
                if (saved.isFailure) { feedback.text = "保存失败，请检查密钥是否包含空格，或清除旧密钥后重新填写"; return@setOnClickListener }
                stopService(Intent(this, CxrlAgentService::class.java))
                status.text = "设置已保存，请在首页连接眼镜。当前使用 DeepSeek 全接入"
                key.text.clear(); dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                runCatching { settings.clearKey() }.fold({
                    stopService(Intent(this, CxrlAgentService::class.java))
                    key.text.clear()
                    feedback.text = "密钥已清除；填写密钥后可重新连接眼镜"
                    status.text = "密钥已清除，请先填写 DeepSeek API Key"
                }, { feedback.text = "密钥清除失败，请重试" })
            }
        }
        dialog.show()
    }

    private fun connect(diagnostics: Boolean = false) {
        if (requesting) return
        if (!diagnostics && !AgentModelSettings(this).hasKey) {
            status.text = "请先在对话模型与费用设置中填写 DeepSeek API Key"
            modelSettings(); return
        }
        diagnosticMode = diagnostics && applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        diagnosticDevice?.close(); diagnosticDevice = null
        if (diagnosticMode) stopService(Intent(this, CxrlAgentService::class.java))
        val required = mutableListOf(Manifest.permission.BLUETOOTH_CONNECT)
        val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            val requested = if (!diagnosticMode && Build.VERSION.SDK_INT >= 33)
                missing + Manifest.permission.POST_NOTIFICATIONS else missing
            requestPermissions(requested.toTypedArray(), 501)
            status.text = "请允许蓝牙权限以连接眼镜。"
            return
        }
        if (!manager.isRokidAppInstalled(this) || manager.checkRokidAppCompatibility(this) !is RokidAppStatus.Compatible) {
            status.text = "请先安装兼容的 Rokid 官方应用，并确认眼镜已连接"; return
        }
        requesting = true
        status.text = "正在等待眼镜授权；不会自动录音或拍照"
        try {
            val permissions = if (diagnosticMode) listOf(GlassPermission.MEDIA)
                else listOf(GlassPermission.CAMERA, GlassPermission.MICROPHONE, GlassPermission.MEDIA)
            manager.requestAuthorization(this, permissions) { result ->
                runOnUiThread { accept(result) }
            }
        } catch (_: Exception) { requesting = false; status.text = "无法发起授权，请重新打开应用后重试" }
    }

    private fun accept(result: AuthResult) {
        if (destroyed || !requesting) return
        requesting = false
        val token = result.token?.takeIf { result.isSuccess && it.isNotBlank() }
        if (token == null) { status.text = "授权未完成，请重试"; return }
        if (diagnosticMode) { startInputDiagnostics(token); return }
        // Token 只在内存中的启动信封传递，不保存、不记录。
        GuideSession.show(GuideState(active = true, phase = "正在连接眼镜，请稍候。"))
        startForegroundService(Intent(this, CxrlAgentService::class.java).putExtra("token", token)
            .putExtra("textDiagnostics", textDiagnostics))
        status.text = ""
    }
    /** 诊断连接由页面持有，原始输入只写日志；不启动模型和业务服务。 */
    private fun startInputDiagnostics(token: String) {
        status.text = "正在连接戒指输入诊断，等待眼镜握手"
        diagnosticDevice = CxrlAgentDevice(this, { packet ->
            if (!destroyed && diagnosticMode) {
                val type = packet.optString("type")
                when (type) {
                    "link.ready" -> diagnosticDevice?.command("rgr06.diagnostics") { result ->
                        if (result.isFailure) status.text = "输入诊断指令发送失败"
                    }
                    "rgr06.ready" -> {
                        status.text = "戒指输入诊断已就绪，等待操作"
                        Log.d("Rgr06Cxrl", "rx type=rgr06.ready")
                    }
                    "rgr06.input", "rgr06.broadcast" -> {
                        val trace = "rx sequence=${packet.optLong("sequence")} type=$type payload=${packet.optString("payload")}"
                        Log.d("Rgr06Cxrl", trace)
                        status.text = "诊断收到：${packet.optString("payload") }"
                    }
                }
            }
        }, { reason ->
            if (!destroyed) {
                status.text = "诊断连接结束：$reason"
                Log.d("Rgr06Cxrl", "disconnected=$reason")
            }
        })
        diagnosticDevice!!.connect(token)
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requesting) runCatching { manager.parseAuthorizationResult(resultCode, data) }.onSuccess(::accept)
    }
    override fun onDestroy() { destroyed = true; diagnosticDevice?.close(); super.onDestroy() }
}
