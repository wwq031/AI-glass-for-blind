package com.leqi.experiment.glasses

import android.app.Activity
import android.content.*
import android.media.*
import android.os.*
import android.view.KeyEvent
import android.widget.TextView
import android.util.Base64
import android.util.Log
import com.leqi.experiment.CxrlInputGate
import com.rokid.cxr.CXRServiceBridge
import com.rokid.cxr.Caps
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.concurrent.thread

/** 只实现设备交互，不包含模型、导航、目的地判断或任务状态机。 */
class GlassesActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var bridge: CXRServiceBridge
    private lateinit var text: TextView
    private val gate = CxrlInputGate()
    private var link = ""
    private var sequence = 0L
    @Volatile private var generation = 0L
    private var recording = false
    private var playback: ByteArrayOutputStream? = null
    private var header: JSONObject? = null
    @Volatile private var track: AudioTrack? = null
    private val playbackLock = Any()
    private var playing = false
    private var lastClick = -1000L
    private var diagnosticOnly = false
    private fun audioDiagnostic(stage: String, detail: String) {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            Log.d("LeqiAudioDiag", "$stage $detail")
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (diagnosticOnly) {
                event("rgr06.broadcast", intent.action ?: "")
                return
            }
            when (intent.action) {
                "com.android.action.ACTION_SPRITE_BUTTON_CLICK" -> click()
                "com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS" -> cancelFromUser()
            }
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        text = TextView(this).apply { textSize = 22f; text = "等待手机连接" }; setContentView(text)
        bridge = CXRServiceBridge(applicationContext)
        val subscribed = bridge.subscribe("rk_custom_client", CXRServiceBridge.MsgCallback { _, caps, stream ->
            val packet = runCatching { JSONObject(caps.at(0).string) }.getOrElse {
                runCatching { JSONObject(stream.toString(Charsets.UTF_8)) }.getOrNull() ?: return@MsgCallback
            }
            main.post { runCatching { receive(packet) }.onFailure {
                reset(); event("user.cancel", "invalid-device-command"); text.text = "设备指令异常，任务已停止"
            } }
        })
        if (subscribed != 0) text.text = "设备通道订阅失败，请重新打开"
        bridge.appLaunch()
        registerReceiver(receiver, IntentFilter().apply {
            addAction("com.android.action.ACTION_SPRITE_BUTTON_CLICK")
            addAction("com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS")
        })
    }
    private fun receive(packet: JSONObject) {
        val type = packet.optString("type")
        val payload = packet.optString("payload")
        if (type == "link.bind") {
            diagnosticOnly = false
            reset(); link = payload; sequence = 0
            event("link.ready"); text.text = "已连接，等待手机模型就绪"; return
        }
        if (link.isBlank() || packet.optString("link") != link) return
        // 诊断握手只在 debug 包中启用；关闭授权窗口后，仅上报原始输入。
        if (type == "rgr06.diagnostics" && applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            reset(); diagnosticOnly = true
            text.text = "RGR06 跨端输入诊断，等待戒指操作"
            event("rgr06.ready")
            return
        }
        if (diagnosticOnly) return
        when (type) {
            "voice.arm", "voice.disarm", "entrance.arm", "entrance.disarm" -> {
                gate.arm(type, payload, SystemClock.elapsedRealtime())
                if (type == "voice.arm" || type == "entrance.arm") {
                    main.postDelayed({
                        gate.expire(SystemClock.elapsedRealtime())?.let { event(it.first, it.second) }
                    }, if (type == "voice.arm") 90001L else 120001L)
                }
                if (type.endsWith("arm") && !type.endsWith("disarm"))
                    text.text = if (type.startsWith("voice")) "按键开始说话" else "按键确认本次拍照"
            }
            "task.cancel" -> { reset(); text.text = "任务已取消，短按可重新开始" }
            "voice.started" -> { recording = true; text.text = "正在听，请说话；短按结束" }
            "voice.stopped" -> { recording = false; text.text = "正在处理，请稍候" }
            "status" -> text.text = payload
            "play.start" -> {
                check(playback == null && !playing)
                val value = JSONObject(payload)
                require(value.getInt("bytes") in 2..8640000 && value.getInt("bytes") % 2 == 0)
                require(value.getInt("rate") in 8000..48000)
                audioDiagnostic("rx.start", "playId=${value.getString("id")} bytes=${value.getInt("bytes")} rate=${value.getInt("rate")}")
                header = value; playback = ByteArrayOutputStream()
            }
            "play.chunk" -> {
                val bytes = Base64.decode(payload, Base64.NO_WRAP)
                val output = playback ?: return
                if (output.size() + bytes.size > header!!.getInt("bytes")) { reset(); return }
                output.write(bytes)
            }
            "play.end" -> {
                val value = header ?: return
                val bytes = playback?.toByteArray() ?: return
                audioDiagnostic("rx.complete", "playId=$payload bytes=${bytes.size}")
                playback = null; header = null
                if (value.getString("id") != payload || value.getInt("bytes") != bytes.size ||
                    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) } != value.getString("sha256")) {
                    event("play.failed", payload); return
                }
                play(payload, bytes, value.getInt("rate"))
            }
        }
    }
    private fun click() {
        if (diagnosticOnly) return
        val now = SystemClock.elapsedRealtime()
        if (link.isBlank() || now - lastClick < 300) return
        lastClick = now
        if (recording) { recording = false; event("voice.finish"); return }
        val confirmation = gate.press(now)
        if (confirmation == null) event("probe.glasses", "sprite-button-click")
        else event(confirmation.first, confirmation.second)
    }
    private fun cancelFromUser() { reset(); event("user.cancel", "long-press"); text.text = "已取消" }
    private fun event(type: String, payload: String = "") {
        if (link.isBlank()) return
        val json = JSONObject().put("link", link).put("sequence", ++sequence).put("type", type).put("payload", payload).toString()
        val sent = bridge.sendMessage("rk_custom_key", Caps().apply { write(json) }, json.toByteArray())
        if (diagnosticOnly) Log.d("Rgr06Cxrl", "tx sequence=$sequence type=$type result=$sent")
    }
    private fun play(id: String, pcm: ByteArray, rate: Int) {
        val attempt = generation
        playing = true
        thread(name = "cxrl-glasses-play") {
            var player: AudioTrack? = null
            val outcome = runCatching {
                check(attempt == generation)
                val created = AudioTrack(AudioManager.STREAM_MUSIC, rate, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, pcm.size, AudioTrack.MODE_STATIC)
                player = created
                synchronized(playbackLock) { check(attempt == generation); track = created }
                check(created.write(pcm, 0, pcm.size) == pcm.size)
                check(created.state == AudioTrack.STATE_INITIALIZED && attempt == generation)
                created.play()
                audioDiagnostic("play.start", "playId=$id rate=$rate frames=${pcm.size / 2}")
                val deadline = SystemClock.elapsedRealtime() + pcm.size * 1000L / (rate * 2) + 3000
                while (attempt == generation && created.playbackHeadPosition < pcm.size / 2 && SystemClock.elapsedRealtime() < deadline)
                    Thread.sleep(20)
                check(attempt == generation && created.playbackHeadPosition >= pcm.size / 2)
            }
            runCatching { player?.stop() }; runCatching { player?.release() }
            audioDiagnostic("play.complete", "playId=$id success=${outcome.isSuccess}")
            synchronized(playbackLock) { if (track === player) track = null }
            main.post {
                if (attempt == generation) { playing = false; event(if (outcome.isSuccess) "play.done" else "play.failed", id) }
            }
        }
    }
    private fun reset() {
        gate.cancel(); recording = false; playback = null; header = null; playing = false
        synchronized(playbackLock) { generation++; runCatching { track?.pause() }; track = null }
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val name = event.device?.name
        if (diagnosticOnly && name in setOf("RGR06", "RGR06 Keyboard", "RGR06 Consumer Control")) {
            val trace = "device=$name deviceId=${event.deviceId} source=${event.source}" +
                " action=${event.action} keyCode=${event.keyCode} key=${KeyEvent.keyCodeToString(event.keyCode)}" +
                " scanCode=${event.scanCode} repeat=${event.repeatCount}" +
                " downTime=${event.downTime} eventTime=${event.eventTime}" +
                " heldMs=${event.eventTime - event.downTime} canceled=${event.isCanceled}"
            Log.d("Rgr06Input", trace)
            event("rgr06.input", trace)
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onKeyDown(code: Int, event: KeyEvent): Boolean {
        if (diagnosticOnly) return super.onKeyDown(code, event)
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            event.startTracking(); return true
        }
        return super.onKeyDown(code, event)
    }
    override fun onKeyLongPress(code: Int, event: KeyEvent): Boolean {
        if (diagnosticOnly) return super.onKeyLongPress(code, event)
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) { cancelFromUser(); return true }
        return super.onKeyLongPress(code, event)
    }
    override fun onKeyUp(code: Int, event: KeyEvent): Boolean {
        if (diagnosticOnly) return super.onKeyUp(code, event)
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            if (!event.isCanceled) click(); return true
        }
        return super.onKeyUp(code, event)
    }
    override fun onPause() { reset(); if (link.isNotBlank()) event("user.cancel", "glasses-paused"); super.onPause() }
    override fun onDestroy() { reset(); link = ""; unregisterReceiver(receiver); super.onDestroy() }
}
