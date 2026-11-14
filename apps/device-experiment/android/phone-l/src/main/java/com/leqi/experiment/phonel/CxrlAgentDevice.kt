package com.leqi.experiment.phonel

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.rokid.cxr.Caps
import com.rokid.cxr.session.*
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** CXR-L 只搬运设备事实；所有回调都绑定当前连接，旧连接不能向新任务交付。 */
internal class CxrlAgentDevice(
    private val manager: CxrSessionManager,
    private val event: (JSONObject) -> Unit,
    private val disconnected: (String) -> Unit,
    private val sdk: ExecutorService,
) : AutoCloseable {
    constructor(context: Context, event: (JSONObject) -> Unit, disconnected: (String) -> Unit) :
        this(CxrSessionManager.getInstance(context.applicationContext), event, disconnected,
            Executors.newSingleThreadExecutor())

    private val main = Handler(Looper.getMainLooper())
    private var session: CxrSession? = null
    private var closingSession: CxrSession? = null
    private var pendingToken: String? = null
    private var closeDeadline = 0L
    private var disposed = false
    @Volatile private var generation = 0L
    private var nonce = ""
    private var lastSequence = 0L
    var ready = false; private set

    fun connect(token: String) {
        if (disposed) return
        closeSession()
        pendingToken = token
        if (closingSession == null) {
            connectPending()
        } else if (closeDeadline == 0L) {
            val deadline = SystemClock.uptimeMillis() + 10_000L
            closeDeadline = deadline
            main.postAtTime({
                if (!disposed && closeDeadline == deadline) {
                    closeDeadline = 0L
                    pendingToken = null
                    disconnected("旧 CXR-L 会话关闭超时，请重新连接")
                }
            }, deadline)
            awaitClose()
        }
    }

    private fun connectPending() {
        val token = pendingToken ?: return
        pendingToken = null
        val attempt = ++generation
        nonce = UUID.randomUUID().toString()
        lastSequence = 0
        val created = try {
            manager.create(SessionConfig(sessionType = SessionType.CUSTOM_APP,
                glassesPackageName = "com.leqi.experiment.glasses",
                glassesActivityName = "com.leqi.experiment.glasses.GlassesActivity",
                aiInterceptMode = AiInterceptMode.ALLOW_WITH_PAUSE))
        } catch (_: Exception) {
            disconnected("CXR-L 无法创建会话，请重新连接")
            return
        }
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
        sdk.execute { runCatching { if (attempt == generation) created.connect(token) }.onFailure {
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
    fun fail(reason: String) {
        pendingToken = null
        closeSession()
        disconnected(reason)
    }

    private fun closeSession() {
        ready = false
        generation++
        val old = session ?: return
        session = null
        closingSession = old
        sdk.execute { runCatching { old.close() } }
    }

    private fun awaitClose() {
        val old = closingSession ?: return
        val deadline = closeDeadline
        if (disposed || deadline == 0L) return
        sdk.execute {
            val idle = runCatching { old.getState() == SessionState.Idle }.getOrDefault(false)
            main.post {
                if (disposed || closingSession !== old || closeDeadline != deadline) return@post
                if (idle) {
                    closingSession = null
                    closeDeadline = 0L
                    connectPending()
                } else {
                    main.postDelayed({ awaitClose() }, 100L)
                }
            }
        }
    }

    override fun close() {
        disposed = true
        pendingToken = null
        closeSession()
        sdk.shutdown()
    }
}
