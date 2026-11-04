package com.leqi.experiment.phonel

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.leqi.experiment.phonebt.AmapPrivacy
import com.rokid.cxr.session.*

/** 手机只负责首次授权和故障恢复；任务开始、录音确认、拍照确认均在眼镜完成。 */
class CxrlAgentActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var manager: CxrSessionManager
    private var requesting = false
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = CxrSessionManager.getInstance(applicationContext)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        status = TextView(this).apply { textSize = 20f; text = "乐奇助盲\n首次连接后，在眼镜短按开始；长按取消。录音中再短按结束。" }
        layout.addView(status)
        fun button(title: String, action: () -> Unit) {
            layout.addView(Button(this).apply { text = title; setOnClickListener { action() } })
        }
        button("授权并连接眼镜") { connect() }
        button("停止任务与连接") {
            stopService(Intent(this, CxrlAgentService::class.java)); status.text = "已停止；再次连接不会恢复旧任务"
        }
        button("查看连接诊断") { startActivity(Intent(this, PhoneLActivity::class.java)) }
        setContentView(layout)
    }

    private fun connect() {
        if (requesting) return
        val required = mutableListOf(Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) required.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 501)
            status.text = "请完成权限授权，再点击连接；授权本身不会录音或拍照"
            return
        }
        if (!AmapPrivacy.isAgreed(this)) {
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
            manager.requestAuthorization(this, listOf(GlassPermission.CAMERA, GlassPermission.MICROPHONE, GlassPermission.MEDIA)) { result ->
                runOnUiThread { accept(result) }
            }
        } catch (_: Exception) { requesting = false; status.text = "无法发起授权，请重新打开应用后重试" }
    }

    private fun accept(result: AuthResult) {
        if (destroyed || !requesting) return
        requesting = false
        val token = result.token?.takeIf { result.isSuccess && it.isNotBlank() }
        if (token == null) { status.text = "授权未完成，请重试"; return }
        // Token 只在内存中的启动信封传递，不保存、不记录。
        startForegroundService(Intent(this, CxrlAgentService::class.java).putExtra("token", token))
        status.text = "正在准备本地模型和眼镜连接，进度见通知。\n眼镜提示就绪后：短按开始，提示后短按录音，再短按结束；长按取消。"
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requesting) runCatching { manager.parseAuthorizationResult(resultCode, data) }.onSuccess(::accept)
    }
    override fun onDestroy() { destroyed = true; super.onDestroy() }
}
