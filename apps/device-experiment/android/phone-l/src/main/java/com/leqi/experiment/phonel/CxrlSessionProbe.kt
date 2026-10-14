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
import com.rokid.cxr.session.PausedReason
import com.rokid.cxr.session.RokidAppStatus
import com.rokid.cxr.session.SessionConfig
import com.rokid.cxr.session.SessionErrorCode
import com.rokid.cxr.session.SessionType
import com.rokid.cxr.session.TerminatingReason
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Standalone communication diagnostic; capture requires explicit per-request consent. */
internal class CxrlSessionProbe(
    private val activity: Activity,
    private val manager: CxrSessionManager,
    private val report: (String) -> Unit,
    private val preview: (DecodedPhoto?) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val origin = SystemClock.elapsedRealtime()
    private var generation = 0L
    private var authPending = false
    private var authorizationAttempt: Long? = null
    private var token: String? = null
    private var session: CxrSession? = null
    private var lifecycle: ISessionLifecycleCbk? = null
    private var started = false
    private var deadline: Runnable? = null
    private var destroyed = false
    private val photos = PhotoGate()
    private val decoder = Executors.newSingleThreadExecutor()
    private var decodeJob: Future<*>? = null
    private var imageCallback: IImageCallback? = null
    private var photoDeadline: Runnable? = null
    private var decodedPhoto: DecodedPhoto? = null

    fun authorize() {
        if (destroyed || authPending || session != null) return
        if (authorizationAttempt != null) {
            record("等待上一次官方授权界面返回，再重新请求授权")
            return
        }
        try {
            if (!manager.isRokidAppInstalled(activity) ||
                manager.checkRokidAppCompatibility(activity) !is RokidAppStatus.Compatible) {
                record("授权拒绝：请先安装兼容官方 App")
                return
            }
            token = null
            val attempt = ++generation
            authorizationAttempt = attempt
            authPending = true
            record("请求用户授权：CAMERA + MEDIA；Token 仅保存在内存")
            armDeadline("用户授权超时", attempt, 60_000L)
            manager.requestAuthorization(activity, listOf(GlassPermission.CAMERA, GlassPermission.MEDIA)) {
                result -> handler.post { acceptAuthorization(attempt, result) }
            }
        } catch (error: Exception) {
            authorizationAttempt = null
            stop("授权异常：${error.javaClass.simpleName}")
        }
    }

    fun onActivityResult(resultCode: Int, data: Intent?) {
        val attempt = authorizationAttempt ?: return
        authorizationAttempt = null
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
        clearDeadline()
        token = result.token?.takeIf { result.isSuccess && it.isNotBlank() }
        record(if (token != null) "用户授权成功；等待手动创建 Session"
            else "用户授权失败/取消：${result.errorCode?.name ?: "无有效 Token"}")
    }

    fun connect() {
        if (destroyed || authPending || session != null) return
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
            record("Session 已创建，回调已注册；开始 connect，等待连接回调及 Started")
            armDeadline("连接/Started 超时", attempt, 30_000L)
            created.connect(localToken)
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

    fun confirmPhoto(id: String) {
        val currentSession = session ?: return
        val request = photos.confirm(id, SystemClock.elapsedRealtime()) ?: run {
            record("拍照确认无效或过期")
            return
        }
        clearPhotoDeadline()
        photoDeadline = Runnable { stop("request=${request.id} 图片接收/解码超时") }
        handler.postDelayed(photoDeadline!!, PhotoGate.TIMEOUT_MS)
        record("request=${request.id} 用户确认；调用 takePhoto(640,480,80)")
        try {
            val result = currentSession.takePhoto(640, 480, 80)
            if (!result.isSuccess) stop("request=${request.id} 拍照请求失败：${result.code.name}")
            else record("request=${request.id} 拍照请求被接受；等待有效图片")
        } catch (error: Exception) {
            stop("request=${request.id} 拍照异常：${error.javaClass.simpleName}")
        }
    }

    fun cancelPhoto(reason: String = "用户取消拍照") {
        if (photos.current?.confirmedAt != null) { stop(reason); return }
        clearPhotoDeadline()
        photos.cancel()
        record(reason)
    }

    private fun receiveImage(data: ByteArray?, attempt: Long) {
        val request = photos.current ?: return
        if (!photos.receive(request.id, SystemClock.elapsedRealtime())) return
        if (data == null || data.isEmpty()) { stop("request=${request.id} 图片为空或超过大小限制"); return }
        record("request=${request.id} 完整图片回调 bytes=${data.size}；开始解码校验")
        decodeJob = decoder.submit {
            val photo = try { PhotoDecoder.decode(data) } catch (_: Exception) { null }
            handler.post {
                if (destroyed || attempt != generation ||
                    !photos.validated(request.id, SystemClock.elapsedRealtime())) {
                    photo?.bitmap?.recycle()
                    return@post
                }
                if (photo == null) { stop("request=${request.id} 图片格式/尺寸/解码校验失败"); return@post }
                clearPhotoDeadline()
                decodedPhoto = photo
                preview(photo)
                val meta = photo.metadata
                record("request=${request.id} 图片已验证 ${meta.mime} ${meta.width}x${meta.height} bytes=${meta.bytes}")
            }
        }
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
    }

    fun refresh() {
        record("本地 Session：${if (session == null) "未创建" else if (started) "Started" else "等待/暂停"}")
        reportBluetooth()
    }

    private fun reportBluetooth() {
        try {
            record("官方 SDK 眼镜蓝牙状态：${manager.isGlassesBtConnected()}")
        } catch (error: Exception) {
            record("官方 SDK 眼镜蓝牙状态未知：${error.javaClass.simpleName}")
        }
    }

    fun stop(reason: String = "用户关闭 Session，撤销本地授权") {
        ++generation // Old SDK callbacks and authorization results cannot revive this attempt.
        clearDeadline()
        authPending = false
        token = null
        started = false
        photos.close()
        clearPhoto()
        val oldSession = session
        val oldCallback = lifecycle
        val oldImages = imageCallback
        session = null
        lifecycle = null
        imageCallback = null
        if (oldSession != null) {
            try {
                if (oldCallback != null) oldSession.removeLifecycleCallback(oldCallback)
                if (oldImages != null) oldSession.removeImageCallback(oldImages)
            } catch (error: Exception) {
                record("移除回调异常：${error.javaClass.simpleName}")
            }
            try { oldSession.close() } catch (error: Exception) {
                record("Session 释放异常：${error.javaClass.simpleName}")
            }
        }
        record(reason)
    }

    fun destroy() {
        destroyed = true
        stop("Activity 退出，已执行 Session 清理")
        handler.removeCallbacksAndMessages(null)
        decoder.shutdownNow()
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
