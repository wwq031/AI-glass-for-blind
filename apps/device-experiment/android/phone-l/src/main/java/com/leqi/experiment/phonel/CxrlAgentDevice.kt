package com.leqi.experiment.phonel

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.rokid.cxr.Caps
import com.rokid.cxr.session.*
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

/** CXR-L 只搬运设备事实；所有回调都绑定当前连接，旧连接不能向新任务交付。 */
internal class CxrlAgentDevice(context: Context, private val event: (JSONObject) -> Unit,
    private val disconnected: (String) -> Unit) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val sdk = Executors.newSingleThreadExecutor()
    private val manager = CxrSessionManager.getInstance(context.applicationContext)
    private var session: CxrSession? = null
    @Volatile private var generation = 0L
    private var nonce = ""
    private var lastSequence = 0L
    var ready = false; private set

    fun connect(token: String) {
        closeSession()
        val attempt = ++generation
        nonce = UUID.randomUUID().toString()
        lastSequence = 0
        val created = manager.create(SessionConfig(sessionType = SessionType.CUSTOM_APP,
            glassesPackageName = "com.leqi.experiment.glasses",
            glassesActivityName = "com.leqi.experiment.glasses.GlassesActivity",
            aiInterceptMode = AiInterceptMode.ALLOW_WITH_PAUSE))
        session = created
        fun dispatch(action: () -> Unit) = main.post {
            if (generation == attempt && session === created) action()
        }
        created.addLifecycleCallback(object : ISessionLifecycleCbk {
            override fun onConnectResult(success: Boolean, errorCode: SessionErrorCode?) {
                if (!success) dispatch { fail("CXR-L 连接失败：${errorCode?.name}") }
            }
            override fun onSessionStarted() { dispatch {
                command("link.bind", nonce) { result ->
                    if (result.isFailure) fail("眼镜端握手发送失败")
                }
                main.postDelayed({ if (generation == attempt && !ready) fail("眼镜端握手超时") }, 15000)
            } }
            override fun onSessionPaused(reason: PausedReason) { dispatch { fail("会话暂停：${reason.name}") } }
            override fun onSessionResumed() { dispatch { fail("会话恢复后需要重新授权连接") } }
            override fun onSessionTerminating(reason: TerminatingReason, gracePeriodMs: Long) {
                dispatch { fail("会话正在关闭：${reason.name}") }
            }
            override fun onSessionClosed(reason: CloseReason) { dispatch { fail("会话已关闭：${reason.name}") } }
        })
        created.addCustomCmdCallback(object : ICustomCmdSessionCallback {
            override fun onCustomCmdResult(channelKey: String, data: ByteArray?) {
                if (channelKey != "rk_custom_key" || data == null || data.size > 65536) return
                val packet = runCatching { JSONObject(data.toString(Charsets.UTF_8)) }.getOrElse {
                    runCatching { JSONObject(Caps.fromBytes(data).at(0).string) }.getOrNull() ?: return
                }
                dispatch {
                    if (packet.optString("link") != nonce) return@dispatch
                    val sequence = packet.optLong("sequence", -1)
                    if (sequence <= lastSequence) return@dispatch
                    lastSequence = sequence
                    if (packet.optString("type") == "link.ready") {
                        ready = true
                        event(JSONObject().put("type", "link.ready"))
                    } else if (ready) event(packet)
                }
            }
        })
        created.addGlassesEventListener(object : IGlassesEventListener {
            private fun revoke(reason: String) { dispatch { if (ready) fail(reason) } }
            override fun onGlassesAppResumed() = Unit
            override fun onGlassesAppPaused() = revoke("眼镜应用离开前台")
            override fun onWearingStatusChanged(wearing: Boolean) { if (!wearing) revoke("眼镜已摘下") }
            override fun onDeviceInfoChanged(info: GlassesInfo) = Unit
            override fun onScreenOff() = revoke("眼镜已灭屏")
            override fun onScreenOn() = Unit
            override fun onLauncherResumed() = revoke("眼镜返回桌面")
            override fun onAiWake() = revoke("官方助手已唤醒")
            override fun onAiInterruptChanged(interruptWake: Boolean) { if (interruptWake) revoke("官方助手中断任务") }
        })
        sdk.execute { runCatching { created.connect(token) }.onFailure {
            dispatch { fail("CXR-L 连接异常") }
        } }
    }

    fun command(type: String, payload: String = "", complete: (Result<Unit>) -> Unit = {}) {
        val current = session ?: run { complete(Result.failure(IllegalStateException("眼镜未连接"))); return }
        val attempt = generation
        val packet = JSONObject().put("link", nonce).put("type", type).put("payload", payload).toString()
        sdk.execute {
            val result = runCatching {
                check(attempt == generation)
                val sent = current.sendCustomCmd("rk_custom_client", Caps().apply { write(packet) }, packet.toByteArray())
                check(sent.isSuccess) { "CXR-L 指令失败：${sent.code}" }
            }
            main.post { if (attempt == generation) complete(result) }
        }
    }

    /** 调用方负责单次录音/拍照的回调注册与撤销，不能在无连接时偷偷重开。 */
    fun current(): CxrSession = session?.takeIf { ready } ?: error("眼镜尚未完成握手")
    fun fail(reason: String) { closeSession(); disconnected(reason) }
    private fun closeSession() {
        ready = false
        generation++
        val old = session; session = null
        if (old != null) sdk.execute { runCatching { old.close() } }
    }
    override fun close() { closeSession(); sdk.shutdown() }
}
