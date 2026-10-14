package com.leqi.experiment.phonel

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.rokid.cxr.session.AiInterceptMode
import com.rokid.cxr.session.AuthResult
import com.rokid.cxr.session.CloseReason
import com.rokid.cxr.session.CxrSession
import com.rokid.cxr.session.CxrSessionManager
import com.rokid.cxr.session.GlassPermission
import com.rokid.cxr.session.ISessionLifecycleCbk
import com.rokid.cxr.session.IImageCallback
import com.rokid.cxr.session.IGlassesEventListener
import com.rokid.cxr.session.GlassesInfo
import com.rokid.cxr.session.PausedReason
import com.rokid.cxr.session.RokidAppStatus
import com.rokid.cxr.session.SessionConfig
import com.rokid.cxr.session.SessionErrorCode
import com.rokid.cxr.session.SessionType
import com.rokid.cxr.session.SessionState
import com.rokid.cxr.session.TerminatingReason
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ExecutorService
import org.json.JSONObject

/** Standalone communication diagnostic; capture requires explicit per-request consent. */
internal class CxrlSessionProbe(
    private val activity: Activity,
    private val manager: CxrSessionManager,
    private val report: (String) -> Unit,
    private val preview: (DecodedPhoto?) -> Unit,
    private val decoder: ExecutorService = Executors.newSingleThreadExecutor(),
    private val sdkExecutor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val versions: () -> JSONObject = { JSONObject() },
) {
    private val handler = Handler(Looper.getMainLooper())
    private val origin = SystemClock.elapsedRealtime()
    @Volatile private var generation = 0L
    private var authPending = false
    private var authorizationAttempt: Long? = null
    private var authorizationRequestCode: Int? = null
    private var token: String? = null
    private var session: CxrSession? = null
    private var lifecycle: ISessionLifecycleCbk? = null
    private var started = false
    private var deadline: Runnable? = null
    @Volatile private var destroyed = false
    private val photos = PhotoGate()
    private var decodeJob: Future<*>? = null
    private var imageCallback: IImageCallback? = null
    private var photoDeadline: Runnable? = null
    private var decodedPhoto: DecodedPhoto? = null
    private var displayRequest: String? = null
    private var visibleConfirmed = false
    private var displayAccepted = false
    private var glassesListener: IGlassesEventListener? = null
    private val runLog = MvpRunLog()
    private var startKind = "unspecified"
    private var pendingPhoto: ((PhotoGate.Request) -> Unit)? = null
    private var pendingConnect = false
    private var closingSession: CxrSession? = null
    private var closeDeadline: Runnable? = null

    fun authorize() {
        if (destroyed || authPending || session != null) return
        if (authorizationAttempt != null) {
            record("等待上一次官方授权界面返回，再重新请求授权")
            return
        }
        try {
            if (!manager.isRokidAppInstalled(activity) ||
                manager.checkRokidAppCompatibility(activity) !is RokidAppStatus.Compatible) {
                if (pendingPhoto != null) stop("授权拒绝：请先安装兼容官方 App")
                else record("授权拒绝：请先安装兼容官方 App")
                return
            }
            token = null
            val attempt = ++generation
            authorizationAttempt = attempt
            authorizationRequestCode = null
            authPending = true
            record("请求用户授权：CAMERA + MEDIA；Token 仅保存在内存")
            armDeadline("用户授权超时", attempt, 60_000L)
            manager.requestAuthorization(activity, listOf(GlassPermission.CAMERA, GlassPermission.MEDIA)) {
                result -> handler.post { acceptAuthorization(attempt, result) }
            }
        } catch (error: Exception) {
            authorizationAttempt = null
            authorizationRequestCode = null
            stop("授权异常：${error.javaClass.simpleName}")
        }
    }

    fun onAuthorizationActivityLaunched(requestCode: Int) {
        if (!destroyed && authPending && authorizationAttempt == generation) {
            authorizationRequestCode = requestCode
        }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != authorizationRequestCode) return
        val attempt = authorizationAttempt ?: return
        authorizationAttempt = null
        authorizationRequestCode = null
        if (!authPending || destroyed || attempt != generation) return
        try {
            acceptAuthorization(attempt, manager.parseAuthorizationResult(resultCode, data))
        } catch (error: Exception) {
            stop("解析授权结果异常：${error.javaClass.simpleName}")
        }
    }

    private fun acceptAuthorization(attempt: Long, result: AuthResult) {
        if (destroyed || attempt != generation || !authPending) return
        authPending = false
        // SDK 1.1.2 can return a cached grant directly, without launching an Activity.
        authorizationAttempt = null
        authorizationRequestCode = null
        clearDeadline()
        token = result.token?.takeIf { result.isSuccess && it.isNotBlank() }
        record(if (token != null) "用户授权成功；等待手动创建 Session"
            else "用户授权失败/取消：${result.errorCode?.name ?: "无有效 Token"}")
        if (pendingPhoto != null) {
            if (token != null) connect() else stop("本次拍照准备取消：授权未成功")
        }
    }

    fun connect() {
        if (destroyed || authPending || session != null) return
        if (closingSession != null) {
            pendingConnect = true
            record("等待旧 SDK Session 回收至 Idle，尚未创建新 Session")
            return
        }
        val localToken = token ?: run { record("请先获得用户授权"); return }
        val attempt = ++generation
        try {
            // CUSTOM_VIEW is evaluated first for text feedback, without a glasses APK.
            val created = manager.create(SessionConfig(
                sessionType = SessionType.CUSTOM_VIEW,
                aiInterceptMode = AiInterceptMode.ALLOW_WITH_PAUSE,
                viewData = VIEW_DATA.replace("CXR-L probe", "CXR-L probe #$attempt"),
            ))
            session = created
            val photoSession = UUID.randomUUID().toString()
            var photoSessionInitialized = false
            val callback = object : ISessionLifecycleCbk {
                private fun dispatch(action: () -> Unit) {
                    handler.post {
                        if (!destroyed && attempt == generation && session === created) action()
                    }
                }
                override fun onConnectResult(success: Boolean, errorCode: SessionErrorCode?) = dispatch {
                    record("连接回调：success=$success code=${errorCode?.name ?: "无"}")
                    if (!success) stop("连接失败；需重新授权后重试")
                    else reportBluetooth()
                }
                override fun onSessionStarted() = dispatch {
                    started = true
                    clearDeadline()
                    if (!photoSessionInitialized) {
                        photos.newSession(photoSession)
                        photoSessionInitialized = true
                    }
                    record("SDK Session Started；请在眼镜核对 CXR-L probe 文本")
                    record("眼镜可见反馈：尚未由佩戴者验收")
                    reportBluetooth()
                    pendingPhoto?.let { ready ->
                        pendingPhoto = null
                        armPhoto()?.let(ready)
                    }
                }
                override fun onSessionPaused(reason: PausedReason) = dispatch {
                    started = false
                    if (photos.current?.confirmedAt != null) {
                        stop("拍照期间 Session 暂停：${reason.name}；拒收旧图片")
                        return@dispatch
                    }
                    photos.pause()
                    record("SDK Session Paused：${reason.name}")
                    armDeadline("Session 暂停后未恢复", attempt, 60_000L)
                }
                override fun onSessionResumed() = dispatch {
                    started = true
                    photos.resume()
                    clearDeadline()
                    record("SDK Session Resumed；未恢复任何采集授权")
                }
                override fun onSessionTerminating(reason: TerminatingReason, gracePeriodMs: Long) = dispatch {
                    started = false
                    clearPhoto()
                    photos.pause()
                    record("SDK Session Terminating：${reason.name}")
                    armDeadline("Session 关闭回调超时", attempt, gracePeriodMs.coerceIn(0L, 30_000L) + 5_000L)
                }
                override fun onSessionClosed(reason: CloseReason) = dispatch {
                    stop("SDK Session Closed：${reason.name}")
                }
            }
            lifecycle = callback
            created.addLifecycleCallback(callback)
            val images = object : IImageCallback {
                override fun onImageReceived(data: ByteArray) {
                    val safeData = if (data.size <= PhotoDecoder.MAX_BYTES) data.copyOf() else null
                    handler.post {
                        if (!destroyed && attempt == generation && session === created) {
                            receiveImage(safeData, attempt)
                        }
                    }
                }
                override fun onImageError(errorCode: SessionErrorCode, v1ErrorCode: Int, v1ErrorMessage: String?) {
                    handler.post {
                        if (!destroyed && attempt == generation && session === created &&
                            photos.current?.confirmedAt != null) {
                            stop("图片回调失败：${errorCode.name} native=$v1ErrorCode")
                        }
                    }
                }
            }
            imageCallback = images
            created.addImageCallback(images)
            val events = object : IGlassesEventListener {
                private fun dispatch(action: () -> Unit) {
                    handler.post { if (!destroyed && attempt == generation && session === created) action() }
                }
                private fun stopActive(reason: String) {
                    // SDK events also fire in Idle; the old launcher is expected before scene start.
                    if (photoSessionInitialized) stop(reason)
                    else record("$reason；Session 尚未 Started，仅记录初始状态")
                }
                override fun onGlassesAppResumed() = dispatch { record("眼镜应用恢复；未重开采集授权") }
                override fun onGlassesAppPaused() = dispatch { stopActive("眼镜应用暂停") }
                override fun onWearingStatusChanged(wearing: Boolean) = dispatch {
                    record("眼镜佩戴状态：$wearing")
                    if (!wearing) cancelPhoto("摘下眼镜，撤销本次拍照确认")
                }
                override fun onDeviceInfoChanged(info: GlassesInfo) = dispatch { record("设备信息更新；不记录设备标识") }
                override fun onScreenOff() = dispatch { stopActive("眼镜灭屏") }
                override fun onScreenOn() = dispatch { record("眼镜亮屏；未自动授权或采集") }
                override fun onLauncherResumed() = dispatch { stopActive("眼镜返回桌面") }
                override fun onAiWake() = dispatch { cancelPhoto("官方助手唤醒，撤销本次拍照确认") }
                override fun onAiInterruptChanged(interruptWake: Boolean) = dispatch {
                    if (interruptWake) cancelPhoto("官方助手中断，撤销本次拍照确认")
                }
            }
            glassesListener = events
            created.addGlassesEventListener(events)
            record("Session 已创建，回调已注册；开始 connect，等待连接回调及 Started")
            armDeadline("连接/Started 超时", attempt, 30_000L)
            sdkExecutor.execute {
                if (!destroyed && attempt == generation) {
                    try { created.connect(localToken) } catch (error: Exception) {
                        handler.post { if (attempt == generation && !destroyed)
                            stop("连接异常：${error.javaClass.simpleName}") }
                    }
                }
            }
            // connect() returns Unit in 1.1.2; the callbacks are the evidence.
        } catch (error: Exception) {
            stop("创建/连接异常：${error.javaClass.simpleName}")
        }
    }

    fun armPhoto(): PhotoGate.Request? {
        if (destroyed || !started) { record("拍照未就绪：等待 Session Started"); return null }
        val request = photos.arm(SystemClock.elapsedRealtime())
        if (request == null) record("请先关闭并重新授权/连接；每个 SDK Session 仅拍照一次")
        else {
            record("request=${request.id} 等待用户明确确认；尚未调用相机")
            photoDeadline = Runnable { cancelPhoto("拍照确认窗口过期") }
            handler.postDelayed(photoDeadline!!, PhotoGate.TIMEOUT_MS)
        }
        return request
    }

    fun preparePhoto(ready: (PhotoGate.Request) -> Unit) {
        if (destroyed || pendingPhoto != null) return
        if (photos.current != null && !displayAccepted) {
            record("请先完成或取消当前拍照请求")
            return
        }
        if (started && !photos.consumed) {
            armPhoto()?.let(ready)
            return
        }
        if (!started || !displayAccepted) {
            record("拍照未就绪：请先授权并连接 Session")
            return
        }
        stop("准备下一次拍照：回收旧 Session，撤销旧拍照确认")
        pendingPhoto = ready
    }

    fun confirmPhoto(id: String) {
        val currentSession = session ?: return
        val request = photos.confirm(id, SystemClock.elapsedRealtime()) ?: run {
            record("拍照确认无效或过期")
            return
        }
        clearPhotoDeadline()
        runLog.start(request, startKind, versions())
        photoDeadline = Runnable { stop("request=${request.id} 图片接收/解码超时") }
        handler.postDelayed(photoDeadline!!, PhotoGate.TIMEOUT_MS)
        record("request=${request.id} 用户确认；调用 takePhoto(640,480,80)")
        val attempt = generation
        sdkExecutor.execute {
            if (!destroyed && attempt == generation) {
                try {
                    val result = currentSession.takePhoto(640, 480, 80)
                    handler.post {
                        if (destroyed || attempt != generation || photos.current?.id != request.id) return@post
                        if (!result.isSuccess) stop("request=${request.id} 拍照请求失败：${result.code.name}")
                        else record("request=${request.id} 拍照请求被接受；等待有效图片")
                    }
                } catch (error: Exception) {
                    handler.post { if (!destroyed && attempt == generation)
                        stop("request=${request.id} 拍照异常：${error.javaClass.simpleName}") }
                }
            }
        }
    }

    fun cancelPhoto(reason: String = "用户取消拍照") {
        if (pendingPhoto != null) { stop(reason); return }
        if (photos.current?.confirmedAt != null) { stop(reason); return }
        clearPhotoDeadline()
        photos.cancel()
        record(reason)
    }

    private fun receiveImage(data: ByteArray?, attempt: Long) {
        val request = photos.current ?: return
        if (!photos.receive(request.id, SystemClock.elapsedRealtime())) return
        runLog.mark(request.id, "image_callback", request.receivedAt!!)
        if (data == null || data.isEmpty()) { stop("request=${request.id} 图片为空或超过大小限制"); return }
        record("request=${request.id} 完整图片回调 bytes=${data.size}；开始解码校验")
        decodeJob = decoder.submit {
            val photo = try { PhotoDecoder.decode(data) } catch (_: Exception) { null }
            handler.post {
                if (destroyed || attempt != generation || photos.current !== request) {
                    photo?.bitmap?.recycle()
                    return@post
                }
                if (photo == null) { stop("request=${request.id} 图片格式/尺寸/解码校验失败"); return@post }
                if (!photos.validated(request.id, SystemClock.elapsedRealtime())) {
                    photo.bitmap.recycle()
                    stop("request=${request.id} 图片解码结果过期")
                    return@post
                }
                clearPhotoDeadline()
                decodedPhoto = photo
                runLog.mark(request.id, "image_validated", request.validatedAt!!)
                runLog.photo(request.id, photo.metadata)
                preview(photo)
                val meta = photo.metadata
                record("request=${request.id} 图片已验证 ${meta.mime} ${meta.width}x${meta.height} bytes=${meta.bytes}")
                runMock(request, photo)
            }
        }
    }

    private fun runMock(request: PhotoGate.Request, photo: DecodedPhoto) {
        val currentSession = session ?: return
        if (destroyed || !started || photos.current !== request || displayRequest != null) return
        try {
            record("request=${request.id} Mock 开始；无模型/TTS/地图/云端依赖")
            runLog.mark(request.id, "mock_start", SystemClock.elapsedRealtime())
            val result = MockObservation.create(request, photo.metadata)
            runLog.mark(request.id, "mock_end", SystemClock.elapsedRealtime())
            record("request=${request.id} Mock 完成：${result.getString("summary")}")
            displayRequest = request.id
            val attempt = generation
            val json = MockObservation.viewUpdate(result.getString("summary"))
            photoDeadline = Runnable { stop("request=${request.id} SDK 显示请求超时") }
            handler.postDelayed(photoDeadline!!, 20_000L)
            sdkExecutor.execute {
                if (!destroyed && attempt == generation) {
                    try {
                        val callStart = SystemClock.elapsedRealtime()
                        handler.post { if (!destroyed && attempt == generation)
                            runLog.mark(request.id, "display_call_start", callStart) }
                        val update = currentSession.customViewUpdate(json)
                        val callEnd = SystemClock.elapsedRealtime()
                        handler.post {
                            if (destroyed || attempt != generation || photos.current !== request) return@post
                            if (!update.isSuccess) { stop("request=${request.id} 显示请求失败：${update.code.name}"); return@post }
                            clearPhotoDeadline()
                            displayAccepted = true
                            runLog.mark(request.id, "display_call_end", callEnd)
                            record("request=${request.id} SDK 显示更新被接受；眼镜实际可见仍待人工核对")
                        }
                    } catch (error: Exception) {
                        handler.post { if (!destroyed && attempt == generation)
                            stop("request=${request.id} 显示异常：${error.javaClass.simpleName}") }
                    }
                }
            }
        } catch (error: Exception) {
            stop("request=${request.id} Mock/显示异常：${error.javaClass.simpleName}")
        }
    }

    fun confirmVisible() {
        val request = photos.current
        if (request == null || request.id != displayRequest || !started || !displayAccepted || visibleConfirmed) {
            record("没有可确认的本次显示请求")
            return
        }
        visibleConfirmed = true
        runLog.mark(request.id, "human_visible", SystemClock.elapsedRealtime())
        record("request=${request.id} 佩戴者人工确认看见本次 Mock 文本；非 SDK 显示回执")
    }

    private fun clearPhotoDeadline() {
        photoDeadline?.let { handler.removeCallbacks(it) }
        photoDeadline = null
    }

    private fun clearPhoto() {
        clearPhotoDeadline()
        decodeJob?.cancel(true)
        decodeJob = null
        preview(null)
        decodedPhoto?.bitmap?.recycle()
        decodedPhoto = null
        displayRequest = null
        visibleConfirmed = false
        displayAccepted = false
    }

    fun hostBackground() {
        // Opening the authorization Activity must remain possible before Session creation.
        if (session != null || (pendingPhoto != null && authorizationRequestCode == null))
            stop("手机宿主后台/灭屏：关闭 Session，返回后需手动重新授权连接")
    }

    fun manualRecover() {
        stop("用户手动恢复：清理旧 Session，不恢复旧拍照授权")
        closingSession?.let { old -> if (closeDeadline == null) {
            sdkExecutor.execute {
                try { old.close() } catch (error: Exception) {
                    handler.post { if (!destroyed) record("SDK 再次清理异常：${error.javaClass.simpleName}") }
                }
            }
            startCloseWait(old)
        } }
        authorize()
    }

    fun refresh() {
        record("本地 Session：${if (session == null) "未创建" else if (started) "Started" else "等待/暂停"}")
        reportBluetooth()
    }

    private fun reportBluetooth() {
        val attempt = generation
        sdkExecutor.execute {
            if (!destroyed && attempt == generation) {
                val text = try { "官方 SDK 眼镜蓝牙状态：${manager.isGlassesBtConnected()}" }
                    catch (error: Exception) { "官方 SDK 眼镜蓝牙状态未知：${error.javaClass.simpleName}" }
                handler.post { if (!destroyed && attempt == generation) record(text) }
            }
        }
    }

    fun stop(reason: String = "用户关闭 Session，撤销本地授权") {
        ++generation // Old SDK callbacks and authorization results cannot revive this attempt.
        clearDeadline()
        pendingPhoto = null
        pendingConnect = false
        authPending = false
        if (authorizationRequestCode == null) authorizationAttempt = null
        token = null
        started = false
        val failureStage = when {
            displayAccepted -> "visibility"
            displayRequest != null -> "display"
            photos.current?.phase == PhotoGate.Phase.DECODING -> "decode"
            photos.current?.phase == PhotoGate.Phase.RECEIVING -> "capture"
            photos.current?.phase == PhotoGate.Phase.VALIDATED -> "mock"
            else -> "lifecycle"
        }
        runLog.finish(failureStage)
        photos.close()
        clearPhoto()
        val oldSession = session
        val oldCallback = lifecycle
        val oldImages = imageCallback
        val oldEvents = glassesListener
        session = null
        lifecycle = null
        imageCallback = null
        glassesListener = null
        if (oldSession != null) {
            closingSession = oldSession
            startCloseWait(oldSession)
            sdkExecutor.execute {
                for (remove in listOf<() -> Unit>(
                    { if (oldCallback != null) oldSession.removeLifecycleCallback(oldCallback) },
                    { if (oldImages != null) oldSession.removeImageCallback(oldImages) },
                    { if (oldEvents != null) oldSession.removeGlassesEventListener(oldEvents) },
                    { oldSession.close() },
                )) {
                    try { remove() } catch (error: Exception) {
                        handler.post { if (!destroyed) record("SDK 清理异常：${error.javaClass.simpleName}") }
                    }
                }
            }
        }
        record(reason)
    }

    private fun startCloseWait(oldSession: CxrSession) {
        closeDeadline?.let { handler.removeCallbacks(it) }
        closeDeadline = Runnable {
            if (!destroyed && closingSession === oldSession) {
                closeDeadline = null
                stop("旧 SDK Session 回收超时；未创建新 Session，请手动恢复后重试")
            }
        }
        handler.postDelayed(closeDeadline!!, 10_000L)
        // Check on the SDK queue after callback removal and close have been submitted.
        handler.post { awaitClosed(oldSession) }
    }

    private fun awaitClosed(oldSession: CxrSession) {
        if (destroyed || closingSession !== oldSession || closeDeadline == null) return
        sdkExecutor.execute {
            val idle = try { oldSession.getState() == SessionState.Idle } catch (_: Exception) { false }
            handler.post {
                if (destroyed || closingSession !== oldSession || closeDeadline == null) return@post
                if (idle) {
                    closingSession = null
                    closeDeadline?.let { handler.removeCallbacks(it) }
                    closeDeadline = null
                    record("旧 SDK Session 已回收至 Idle")
                    if (pendingPhoto != null && token == null) authorize()
                    else if (pendingConnect) { pendingConnect = false; connect() }
                } else handler.postDelayed({ awaitClosed(oldSession) }, 100L)
            }
        }
    }

    fun setStartKind(kind: String) {
        if (kind !in setOf("cold", "warm") || photos.current != null) {
            record("请在本次拍照前选择冷/热启动口径")
            return
        }
        startKind = kind
        record("下一次测试启动类型：$kind（由测试者指定）")
    }

    fun exportRecords(): JSONObject = runLog.export()

    fun destroy() {
        destroyed = true
        stop("Activity 退出，本地请求已作废，SDK 清理已排队")
        handler.removeCallbacksAndMessages(null)
        decoder.shutdownNow()
        sdkExecutor.shutdown() // Let queued SDK close run; do not drop resource cleanup.
    }

    private fun armDeadline(reason: String, attempt: Long, delay: Long) {
        clearDeadline()
        deadline = Runnable { if (!destroyed && attempt == generation) stop(reason) }
        handler.postDelayed(deadline!!, delay)
    }

    private fun clearDeadline() {
        deadline?.let { handler.removeCallbacks(it) }
        deadline = null
    }

    private fun record(message: String) {
        report("+${SystemClock.elapsedRealtime() - origin}ms g=$generation $message")
    }

    companion object {
        // Compact JSON and props IDs match the official Sample's CUSTOM_VIEW layout.
        private const val VIEW_DATA = """{"type":"LinearLayout","props":{"id":"root","layout_width":"match_parent","layout_height":"match_parent","orientation":"vertical","backgroundColor":"#FF000000"},"children":[{"type":"TextView","props":{"id":"title_text","layout_width":"wrap_content","layout_height":"wrap_content","text":"CXR-L probe","textSize":"16sp","textColor":"#FF00FF00"}}]}"""
    }
}
