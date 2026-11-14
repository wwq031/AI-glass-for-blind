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
import android.widget.CheckBox
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
    private lateinit var manager: CxrSessionManager
    private var requesting = false
    private var destroyed = false
    private var diagnosticMode = false
    private var textDiagnostics = false
    private var diagnosticDevice: CxrlAgentDevice? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = CxrSessionManager.getInstance(applicationContext)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        status = TextView(this).apply { textSize = 20f; text = "乐奇助盲\n首次连接后，在眼镜短按开始。录音中再短按结束。结束任务请在手机点停止任务与连接。" }
        layout.addView(status)
        fun button(title: String, action: () -> Unit) {
            layout.addView(Button(this).apply { text = title; setOnClickListener { action() } })
        }
        button("授权并连接眼镜") { connect() }
        button("对话模型与费用设置") { modelSettings() }
        button("停止任务与连接") {
            requesting = false
            diagnosticDevice?.close(); diagnosticDevice = null
            stopService(Intent(this, CxrlAgentService::class.java)); status.text = "已停止；再次连接不会恢复旧任务"
        }
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            layout.addView(CheckBox(this).apply {
                text = "下次连接记录识别与播报文字（诊断用）"
                isSaveEnabled = false
                setOnCheckedChangeListener { _, checked -> textDiagnostics = checked }
            })
            button("戒指输入诊断（只记录）") { connect(diagnostics = true) }
        }
        button("查看连接诊断") { startActivity(Intent(this, PhoneLActivity::class.java)) }
        setContentView(layout)
    }

    private fun modelSettings() {
        val settings = AgentModelSettings(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 16) }
        content.addView(TextView(this).apply {
            text = "仅对话模式发送文字、历史和必要任务上下文。全接入模式还会发送本轮录音、确认拍摄的照片和播报文字。两种 DeepSeek 模式均按用量收费。\n本机记录：${settings.totalTokens} tokens，转写 ${settings.asrMs / 1000.0} 秒，合成 ${settings.ttsCharacters} 字符，OCR ${settings.ocrPages} 页。以 DeepSeek 账单为准。"
        })
        val modes = RadioGroup(this)
        AgentModelSettings.Mode.entries.forEach { mode ->
            modes.addView(RadioButton(this).apply { id = 201 + mode.ordinal; text = mode.label })
        }
        modes.check(201 + settings.mode.ordinal)
        content.addView(modes)
        val models = RadioGroup(this)
        models.addView(RadioButton(this).apply { id = 101; text = "Flash：费用较低，默认推荐" })
        models.addView(RadioButton(this).apply { id = 102; text = "Pro：更强推理，费用较高" })
        models.check(if (settings.model == AgentModelSettings.PRO) 102 else 101)
        content.addView(models)
        val key = EditText(this).apply {
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
                val mode = AgentModelSettings.Mode.entries[modes.checkedRadioButtonId - 201]
                if (mode != AgentModelSettings.Mode.LOCAL && key.text.isNullOrBlank() && !settings.hasKey) {
                    feedback.text = "请先填写 API Key，或关闭 DeepSeek 使用免费模式"; return@setOnClickListener
                }
                val saved = runCatching { settings.save(mode,
                    if (models.checkedRadioButtonId == 102) AgentModelSettings.PRO else AgentModelSettings.FLASH,
                    key.text.toString()) }
                if (saved.isFailure) { feedback.text = "保存失败，请检查密钥是否包含空格，或清除旧密钥后重新填写"; return@setOnClickListener }
                stopService(Intent(this, CxrlAgentService::class.java))
                status.text = "设置已保存，请点击授权并连接眼镜。当前模式：${mode.label}"
                key.text.clear(); dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                runCatching { settings.clearKey() }.fold({
                    stopService(Intent(this, CxrlAgentService::class.java))
                    key.text.clear(); modes.check(201)
                    feedback.text = "密钥已清除，已切回免费模式；关闭此窗口后重新连接眼镜"
                    status.text = "已切回免费模式，请重新连接眼镜"
                }, { feedback.text = "密钥清除失败，请重试" })
            }
        }
        dialog.show()
    }

    private fun connect(diagnostics: Boolean = false) {
        if (requesting) return
        diagnosticMode = diagnostics && applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        diagnosticDevice?.close(); diagnosticDevice = null
        if (diagnosticMode) stopService(Intent(this, CxrlAgentService::class.java))
        val required = mutableListOf(Manifest.permission.BLUETOOTH_CONNECT)
        if (!diagnosticMode) {
            required.addAll(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            if (Build.VERSION.SDK_INT >= 33) required.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 501)
            status.text = "请完成权限授权，再点击连接；授权本身不会录音或拍照"
            return
        }
        if (!diagnosticMode && !AmapPrivacy.isAgreed(this)) {
            AlertDialog.Builder(this).setTitle("导航服务说明").setMessage(AmapPrivacy.NOTICE)
                .setPositiveButton("同意并继续") { _, _ ->
                    runCatching { AmapPrivacy.agree(this) }.fold({ connect() }, { status.text = "导航授权准备失败" })
                }.setNegativeButton("取消", null).show()
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
        try {
        startForegroundService(Intent(this, CxrlAgentService::class.java).putExtra("token", token)
            .putExtra("textDiagnostics", textDiagnostics))
        status.text = "正在准备本地模型和眼镜连接，进度见通知。\n眼镜提示就绪后：短按开始，提示后短按录音，再短按结束。结束任务请在手机点停止任务与连接。"
        } catch (_: RuntimeException) {
            status.text = "服务未能启动，请确认权限、定位开关及应用在前台后重新连接"
        }
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
