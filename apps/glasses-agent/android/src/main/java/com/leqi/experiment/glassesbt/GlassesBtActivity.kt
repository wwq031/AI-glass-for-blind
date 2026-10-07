package com.leqi.experiment.glassesbt

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.leqi.experiment.AudioWire
import com.leqi.experiment.BluetoothWire
import com.leqi.experiment.PhotoWire
import com.leqi.experiment.SessionWire
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern
import kotlin.concurrent.thread

/**
 * Temporary self-owned Bluetooth server for the device demo.
 *
 * 协议（v1，手机 → 眼镜）：
 * - `voice.arm`：payload 为录音用途（`dest` / `confirm`），空则按 `dest` 处理；
 * - `entrance.arm`：只允许在手机播报“已到达”之后发送；
 * - `location.fix` / `nav.event`：诊断与导航文字转发；
 * - `play.start|chunk|end`：header 为 `字节数|sha256[|playId]`，播放结束回 `play.done`。
 *
 * 协议（v1，眼镜 → 手机）：
 * - `voice.confirm`：payload 回带录音用途；
 * - `audio.start|chunk|end`：header 为 `字节数|sha256|用途`；
 * - `entrance.confirm`：payload 回带按键那一刻的入口标记（手机只认当前窗口的标记）；
 * - `media.start|chunk|end`：入口照片；
 * - `play.done` / `play.failed`：payload 为 playId。
 *
 * 实体键只在授权窗口内生效：语音授权 90 秒，入口拍摄授权 120 秒；过期按键不再触发采集。
 */
class GlassesBtActivity : Activity() {
    private val keyReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            show("眼镜收到按键广播：$action")
            if (BUTTON_CLICK == action) onPhysicalClick()
            if (BUTTON_LONG_PRESS == action) onPhysicalCancel()
            if (DEBUG_PROBE == action) sendProbe("debug-broadcast")
        }
    }
    private var status: TextView? = null

    @Volatile
    private var stopped = false

    /** 非空表示语音采集已授权；保存本次录音用途。 */
    @Volatile
    private var voicePurpose: String? = null

    @Volatile
    private var voiceArmedUntil = 0L

    @Volatile
    private var entranceArmed = false

    @Volatile
    private var entranceArmedUntil = 0L

    /** 入口拍摄授权的任务标记，会随照片一起回带给手机。 */
    @Volatile
    private var entranceTag = ""

    private var server: BluetoothServerSocket? = null
    private var socket: BluetoothSocket? = null

    private val sendLock = Any()
    private var connectionCounter = 0L

    private val captureLock = Any()
    private var voiceGeneration = 0L
    private var entranceGeneration = 0L

    /**
     * 当前这条蓝牙连接。
     *
     * [generation] 每建立、关闭一次连接就换一个：属于旧连接的录音、拍摄和发送线程都拿着旧的
     * [Peer]，一律不再写新连接。这样断开重连后不会把上一轮的照片或录音发给新会话。
     */
    private class Peer(val generation: Long, val stream: DataOutputStream)

    @Volatile
    private var peer: Peer? = null

    /** 等待录音权限期间暂存的这次授权：用途和它所属的连接都要留住。 */
    /** 等权限对话框的那次按键：连同当时那一代语音授权一起记住，回来时对不上就不录。 */
    private class PendingVoice(val peer: Peer, val purpose: String, val window: Long)

    @Volatile
    private var pendingVoice: PendingVoice? = null

    /** 等待摄像权限期间暂存的这次授权：标记和它所属的连接都要留住。 */
    /** 等权限对话框的那次拍摄：同样记住当时那一代拍摄授权。 */
    private class PendingPhoto(val peer: Peer, val tag: String, val window: Long)

    @Volatile
    private var pendingPhoto: PendingPhoto? = null

    private var nextSequence = 1L
    private val playbackLock = Any()
    private var activePlayback: AudioTrack? = null
    private var playbackGeneration = 0

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(16, 16, 16, 16)
        val statusView = TextView(this)
        status = statusView
        layout.addView(statusView)
        val send = Button(this)
        send.text = "眼镜发送按键探针"
        send.setOnClickListener { sendProbe("screen-button") }
        layout.addView(send)
        setContentView(layout)
        val keys = IntentFilter(BUTTON_CLICK)
        keys.addAction(BUTTON_DOWN)
        keys.addAction(BUTTON_UP)
        keys.addAction(BUTTON_DOUBLE_CLICK)
        keys.addAction(BUTTON_LONG_PRESS)
        keys.addAction(DEBUG_PROBE)
        registerReceiver(keyReceiver, keys)
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        } else {
            startServer()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startServer()
            } else {
                show("眼镜蓝牙连接权限未授予")
            }
        } else if (requestCode == 2) {
            val pending = pendingPhoto
            pendingPhoto = null
            if (pending == null) {
                show("入口拍摄授权已失效，请重新等待手机准备")
                return
            }
            // 权限对话框可能开了很久：连接换了、这一代拍摄被撤销（取消/断开/重新授权），
            // 或者授权窗口过期了，都不再拍摄。这里只认按键那一刻记下的那一代。
            if (!captureStillAuthorized(pending.peer, pending.window)) {
                show("入口拍摄授权已随连接或本轮授权失效，本次不拍摄")
                return
            }
            if (SystemClock.elapsedRealtime() > entranceArmedUntil) {
                show("入口拍摄授权已过期，本次不拍摄")
                sendEvent("entrance.expired", pending.tag, "眼镜已报告入口授权过期", pending.peer)
                return
            }
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                capturePhoto(pending.peer, pending.tag, pending.window)
            } else {
                show("眼镜摄像权限未授予")
                sendPhotoFailure(pending.peer, pending.tag, "permission-denied")
            }
        } else if (requestCode == 3) {
            val pending = pendingVoice
            pendingVoice = null
            if (pending == null) {
                show("语音授权已失效，请重新等待手机准备")
                return
            }
            // 同入口拍摄：连接换了、或者这一代语音授权已被撤销（取消/断开/重新授权），
            // 这次按键就作废，不能拿旧用途去占新一代的窗口。
            if (!voiceStillAuthorized(pending.peer, pending.window)) {
                show("语音授权已随连接或本轮授权失效，本次不录音")
                return
            }
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 同上：权限对话框期间授权窗口可能已经过期。
                if (SystemClock.elapsedRealtime() > voiceArmedUntil) {
                    show("语音授权已过期，本次不录音")
                    sendEvent("voice.expired", pending.purpose, "眼镜已报告语音授权过期", pending.peer)
                    return
                }
                // 这一代的语音代要用按键那一刻存下的那个，不能重新取：对话框期间
                // 授权被撤销又重开的话，重新取会拿到新一代，旧按键就混进新窗口了。
                recordVoice(pending.purpose, pending.peer, pending.window)
            } else {
                show("眼镜录音权限未授予")
                sendEvent(
                    "audio.failed",
                    failurePayload(pending.purpose, "permission-denied"),
                    "眼镜已报告录音权限未授予",
                    pending.peer,
                )
            }
        }
    }

    private fun startServer() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            show("眼镜蓝牙未开启")
            return
        }
        thread(name = "leqi-bt-server") {
            try {
                val listener = adapter.listenUsingRfcommWithServiceRecord(
                    "Leqi Device Demo",
                    BluetoothWire.SERVICE_ID,
                )
                server = listener
                show("眼镜直连服务已启动，等待手机")
                while (!stopped) {
                    val accepted = listener.accept()
                    socket = accepted
                    val current = openPeer(accepted)
                    show("手机已通过自有蓝牙通道连接（连接代 " + current.generation + "）")
                    try {
                        val input = DataInputStream(accepted.inputStream)
                        var playback: PhotoWire.Collector? = null
                        var playbackId = ""
                        while (!stopped) {
                            val event = SessionWire.decode(input.readUTF())
                            if (BluetoothWire.SESSION != event.sessionId) continue
                            if ("entrance.arm" == event.type) {
                                // 新的一轮拍摄窗口换掉了旧窗口：正在飞的旧一代拍摄必须作废。
                                bumpEntranceGeneration()
                                entranceArmed = true
                                entranceArmedUntil = SystemClock.elapsedRealtime() + ENTRANCE_ARM_WINDOW_MS
                                entranceTag = event.payload.trim()
                                voicePurpose = null
                                show("入口观察已准备，等待实体键确认")
                            } else if ("voice.arm" == event.type) {
                                // 同理：新的录音用途换掉了旧用途，旧一代录音不能再发出去。
                                bumpVoiceGeneration()
                                val purpose = event.payload.trim()
                                voicePurpose = if (purpose.isEmpty()) "dest" else purpose
                                voiceArmedUntil = SystemClock.elapsedRealtime() + VOICE_ARM_WINDOW_MS
                                entranceArmed = false
                                show("语音采集已准备（$voicePurpose），等待实体键确认")
                            } else if ("voice.disarm" == event.type) {
                                revokeVoiceWindow()
                                show("语音采集授权已撤销")
                            } else if ("entrance.disarm" == event.type) {
                                revokeEntranceWindow()
                                show("入口拍摄授权已撤销")
                            } else if (TASK_CANCEL == event.type) {
                                // 手机在取消时先发这条：本机立刻撤销授权、停掉正在播放的语音，
                                // 并作废正在录音/拍摄的那一代，避免任何残留数据回到手机。
                                revokeAllWindows()
                                stopPlayback()
                                show("手机已取消当前任务：" + event.payload)
                            } else if ("location.fix" == event.type) {
                                show("收到手机真实定位 #" + event.sequence)
                            } else if ("nav.event" == event.type) {
                                show("导航事件：" + event.payload)
                            } else if ("play.start" == event.type) {
                                val header = HEADER_PATTERN.split(event.payload, -1)
                                if (header.size != 2 && header.size != 3) {
                                    throw IllegalArgumentException("Invalid playback header")
                                }
                                playback = PhotoWire.Collector(header[0].toInt(), header[1])
                                playbackId = if (header.size == 3) header[2] else ""
                                show("眼镜开始接收手机音频")
                            } else if ("play.chunk" == event.type) {
                                if (playback == null) {
                                    throw IllegalArgumentException("Unexpected playback chunk")
                                }
                                playback.add(event.payload)
                            } else if ("play.end" == event.type) {
                                if (playback == null) {
                                    throw IllegalArgumentException("Unexpected playback end")
                                }
                                val wav = playback.finish()
                                playback = null
                                playWav(playbackId, wav)
                            } else {
                                show("收到手机：" + event.type + " #" + event.sequence + " " + event.payload)
                            }
                        }
                    } catch (error: Exception) {
                        if (!stopped) show("手机连接结束：" + error.javaClass.simpleName)
                    } finally {
                        stopPlayback()
                        closePeer(current)
                        socket = null
                        accepted.close()
                    }
                }
            } catch (error: Exception) {
                if (!stopped) show("眼镜直连服务失败：" + error.message)
            }
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        sendProbe("keycode-$keyCode")
        return super.onKeyUp(keyCode, event)
    }

    /** 实体键是唯一授权入口；任何授权过期后都不再触发采集。 */
    private fun onPhysicalClick() {
        val current = peer
        if (current == null) {
            show("等待手机蓝牙连接")
            return
        }
        val purpose = voicePurpose
        if (purpose != null) {
            val deadline = voiceArmedUntil
            // 按键这一刻的语音代要当场记下来，不能被后面的申请权限、写确认拖后：
            // 这中间如果来了 voice.disarm 或新一轮 voice.arm，这次录音就对不上号，
            // 必须作废，绝不能拿旧用途去占新一代的窗口。
            val window = voiceGenerationNow()
            voicePurpose = null
            if (SystemClock.elapsedRealtime() > deadline) {
                show("语音授权已过期，本次按键不录音")
                sendEvent("voice.expired", purpose, "眼镜已报告语音授权过期", current)
                return
            }
            sendEvent("voice.confirm", purpose, "录音已由实体键确认：" + purpose, current)
            recordVoice(purpose, current, window)
            return
        }
        if (!entranceArmed) {
            sendProbe("sprite-button-click")
            return
        }
        val deadline = entranceArmedUntil
        val tag = entranceTag
        entranceArmed = false
        if (SystemClock.elapsedRealtime() > deadline) {
            show("入口拍摄授权已过期，本次按键不拍摄")
            sendEvent("entrance.expired", tag, "眼镜已报告入口授权过期", current)
            return
        }
        // 这一代拍摄要在写确认之前当场取好：写确认可能阻塞，期间如果来了 entrance.disarm、
        // task.cancel 或新一轮 entrance.arm，这一代拍摄就作废，回来不能再重取一代去拍。
        val window = entranceGenerationNow()
        // 先同步把确认写进这条连接，再开始拍摄：照片一定晚于它到达手机，
        // 手机才认得出这张照片属于本轮哪一次实体确认。确认回带按键那一刻的入口标记，
        // 手机只认当前窗口的那一个标记，晚到的旧确认不会被当成新一轮的授权。
        if (!sendEventBlocking("entrance.confirm", tag, current)) {
            show("入口拍摄确认未送达，本次不拍摄")
            return
        }
        capturePhoto(current, tag, window)
    }

    /** 长按取消：本机先撤销授权，再告诉手机，避免取消之后又被一次按键拍下来。 */
    private fun onPhysicalCancel() {
        revokeAllWindows()
        show("眼镜已请求取消当前任务，本机授权已撤销")
        sendEvent("user.cancel", "long-press", "眼镜已请求取消当前任务")
    }

    /** 撤销语音窗口：换一代语音采集，正在录音的线程也就作废了。 */
    private fun revokeVoiceWindow() {
        voicePurpose = null
        voiceArmedUntil = 0L
        pendingVoice = null
        bumpVoiceGeneration()
    }

    /** 撤销入口窗口：换一代拍摄，作废这一代标记，正在拍摄的那次也不再回报。 */
    private fun revokeEntranceWindow() {
        entranceArmed = false
        entranceArmedUntil = 0L
        entranceTag = ""
        pendingPhoto = null
        bumpEntranceGeneration()
    }

    private fun revokeAllWindows() {
        revokeVoiceWindow()
        revokeEntranceWindow()
    }

    /**
     * 连接建立：换一代连接。属于上一代的线程拿着旧的 [Peer]，从此写不进这条新连接。
     * 只能在持有 [sendLock] 时自增连接代，序号和连接身份才不会错位。
     */
    private fun openPeer(target: BluetoothSocket): Peer {
        synchronized(sendLock) {
            val created = Peer(++connectionCounter, DataOutputStream(target.outputStream))
            peer = created
            return created
        }
    }

    /** 连接结束：撤销全部授权、停止播报，并让这条连接的线程全部作废。 */
    private fun closePeer(target: Peer) {
        synchronized(sendLock) {
            if (peer !== target) return
            peer = null
            connectionCounter++
        }
        revokeAllWindows()
        show("手机连接已结束，本机授权已撤销")
    }

    /**
     * 采集代：每次授权窗口被撤销就自增。
     *
     * 语音和入口各算一代。正在录音或拍摄的线程记住自己那一代，撤销（voice.disarm、
     * entrance.disarm、task.cancel、断开）之后对不上号，就既不再采集也不再回报。
     */
    private fun bumpVoiceGeneration(): Long = synchronized(captureLock) { ++voiceGeneration }

    private fun bumpEntranceGeneration(): Long = synchronized(captureLock) { ++entranceGeneration }

    private fun voiceGenerationNow(): Long = synchronized(captureLock) { voiceGeneration }

    private fun entranceGenerationNow(): Long = synchronized(captureLock) { entranceGeneration }

    /** 停止当前播报并让还在写音频轨的线程作废。 */
    private fun stopPlayback() {
        synchronized(playbackLock) {
            playbackGeneration++
            val playing = activePlayback
            activePlayback = null
            if (playing != null) {
                try {
                    playing.pause()
                    playing.flush()
                    playing.stop()
                } catch (ignored: RuntimeException) {
                }
                try {
                    playing.release()
                } catch (ignored: RuntimeException) {
                }
            }
        }
    }

    private fun capturePhoto(origin: Peer, tag: String, window: Long) {
        // 先对号再申请权限、开相机：走到这里之前这一代拍摄就可能已经被撤销
        // （entrance.disarm / task.cancel / 断开 / 新一轮 entrance.arm）。
        // 撤销之后开出来的相机没有任何窗口认它，拍下去纯属白拍。
        if (!captureStillAuthorized(origin, window)) {
            show("眼镜拍摄已作废：这一轮授权已被撤销")
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingPhoto = PendingPhoto(origin, tag, window)
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 2)
            return
        }
        var camera: Camera? = null
        var texture: SurfaceTexture? = null
        try {
            var back = 0
            val info = Camera.CameraInfo()
            for (i in 0 until Camera.getNumberOfCameras()) {
                Camera.getCameraInfo(i, info)
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                    back = i
                    break
                }
            }
            val opened = Camera.open(back)
            camera = opened
            if (opened == null) {
                show("眼镜摄像头不可用")
                return
            }
            val params = opened.parameters
            var chosen: Camera.Size? = null
            for (size in params.supportedPictureSizes) {
                val current = chosen
                if (size.width <= 1280 && (current == null || size.width > current.width)) chosen = size
            }
            val pictureSize = chosen
            if (pictureSize != null) params.setPictureSize(pictureSize.width, pictureSize.height)
            params.jpegQuality = 75
            opened.parameters = params
            val preview = SurfaceTexture(10)
            texture = preview
            opened.setPreviewTexture(preview)
            // 这里的校验和 startPreview/takePicture 必须在同一把 captureLock 里：
            // 撤销（disarm / cancel / 断开 / 重新授权）走的也是这把锁，两边才有确定顺序——
            // 要么撤销先生效、这里校验不过就放手，要么这里先拍下去、撤销只作废回调里的回报；
            // 不会出现校验刚通过、撤销却已经插进来、相机还照拍的情况。
            synchronized(captureLock) {
                if (!captureStillAuthorized(origin, window)) {
                    // 相机和纹理已经建好又不能再用了，当场释放，不留给外面的 catch 兜底。
                    opened.release()
                    preview.release()
                    camera = null
                    texture = null
                    show("眼镜拍摄已作废：这一轮授权已被撤销")
                    return
                }
                opened.startPreview()
                val activeCamera = opened
                val activeTexture = preview
                opened.takePicture(null, null, Camera.PictureCallback { jpeg, _ ->
                    activeCamera.release()
                    activeTexture.release()
                    // 这一代拍摄被撤销（取消、断开、重新授权）之后，相机回来的画面一律不要。
                    if (!captureStillAuthorized(origin, window)) {
                        show("眼镜拍摄已作废：这一轮授权已被撤销")
                        return@PictureCallback
                    }
                    if (jpeg == null || jpeg.isEmpty()) {
                        sendPhotoFailure(origin, tag, "empty-frame")
                    } else {
                        sendPhoto(jpeg, origin, tag)
                    }
                })
            }
            show("眼镜正在拍摄入口照片")
        } catch (error: Exception) {
            camera?.release()
            texture?.release()
            show("眼镜拍摄失败：" + error.javaClass.simpleName)
            if (captureStillAuthorized(origin, window)) {
                sendPhotoFailure(origin, tag, error.javaClass.simpleName)
            }
        }
    }

    /** 这一代拍摄是否还有效：连接没换、这一代授权也没被撤销。 */
    private fun captureStillAuthorized(origin: Peer, window: Long): Boolean =
        peer === origin && entranceGenerationNow() == window

    /** 这一代录音是否还有效：连接没换、这一代语音授权也没被撤销。 */
    private fun voiceStillAuthorized(origin: Peer, window: Long): Boolean =
        peer === origin && voiceGenerationNow() == window

    private fun sendPhoto(jpeg: ByteArray, origin: Peer, tag: String) {
        // 回带授权那一刻的任务标记，手机才能确认这张照片属于哪一轮任务。
        sendBinary("media", jpeg, tag, "眼镜照片已发送：", origin)
    }

    /** 拍摄失败要带标记和原因回报，手机才知道是哪一代的授权没有产出照片。 */
    private fun sendPhotoFailure(origin: Peer, tag: String, reason: String) {
        if (peer !== origin) {
            show("眼镜拍摄失败（" + reason + "），手机连接已更换，不再回报旧任务")
            return
        }
        show("眼镜拍摄失败：" + reason)
        sendEvent("photo.failed", failurePayload(tag, reason), "眼镜已报告拍摄失败", origin)
    }

    /**
     * 播放手机送来的 WAV。
     *
     * 用 MODE_STREAM 分块写入，不把整段 PCM 一次性塞进音频轨；每段音频带一个 playId，
     * 播放结束或失败都会回执，避免手机误以为已经播报而不停重发。
     */
    private fun playWav(playId: String, wav: ByteArray) {
        val format = parseWav(wav)
        if (format == null) {
            show("眼镜收到的播放音频无效")
            sendEvent("play.failed", failurePayload(playId, "unsupported-wav"), "眼镜已报告播放失败")
            return
        }
        val channels = format[0]
        val sampleRate = format[1]
        val dataOffset = format[3]
        val dataLength = format[4]
        var generation = 0
        synchronized(playbackLock) {
            generation = ++playbackGeneration
            val previous = activePlayback
            if (previous != null) {
                // 正常路径上手机是串行发播报的；这里只兜底打断，避免两段音频叠在一起。
                activePlayback = null
                try {
                    previous.pause()
                    previous.flush()
                    previous.stop()
                } catch (ignored: RuntimeException) {
                }
                try {
                    previous.release()
                } catch (ignored: RuntimeException) {
                }
                show("眼镜打断了上一段尚未结束的播报")
            }
        }
        thread(name = "leqi-audio-play") {
            var track: AudioTrack? = null
            try {
                val bufferBytes = Math.max(16_384, Math.min(dataLength, sampleRate * channels * 2 / 4))
                val created = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes,
                    AudioTrack.MODE_STREAM,
                )
                track = created
                if (created.state != AudioTrack.STATE_INITIALIZED) {
                    throw IllegalStateException("音频轨未初始化")
                }
                var interrupted = false
                synchronized(playbackLock) {
                    interrupted = generation != playbackGeneration
                    if (!interrupted) activePlayback = created
                }
                if (interrupted) {
                    sendEvent("play.failed", failurePayload(playId, "interrupted"), "眼镜已报告播放被打断")
                    return@thread
                }
                created.play()
                show("眼镜开始播放手机送来的音频")
                var written = 0
                while (written < dataLength) {
                    val count = created.write(
                        wav,
                        dataOffset + written,
                        Math.min(PLAY_BLOCK_BYTES, dataLength - written),
                    )
                    if (count <= 0) throw IllegalStateException("音频轨写入失败：" + count)
                    written += count
                    synchronized(playbackLock) {
                        if (generation != playbackGeneration) {
                            sendEvent("play.failed", failurePayload(playId, "interrupted"), "眼镜已报告播放被打断")
                            return@thread
                        }
                    }
                }
                // write 返回时音频轨内部最多还剩一个缓冲区的数据，等它放完再收尾。
                val bytesPerSecond = sampleRate * channels * 2
                val tailMs = Math.round(1000.0 * bufferBytes / bytesPerSecond) + 250
                Thread.sleep(Math.max(300L, tailMs))
                created.stop()
                show("眼镜音频播放结束：" + written + " 字节")
                sendEvent("play.done", playId, "眼镜已回执播放完成")
            } catch (error: Exception) {
                if (error is InterruptedException) Thread.currentThread().interrupt()
                show("眼镜音频播放失败：" + error.javaClass.simpleName + " " + error.message)
                sendEvent(
                    "play.failed",
                    failurePayload(playId, error.javaClass.simpleName),
                    "眼镜已报告播放失败",
                )
            } finally {
                synchronized(playbackLock) {
                    if (activePlayback === track) activePlayback = null
                }
                try {
                    track?.release()
                } catch (ignored: RuntimeException) {
                }
            }
        }
    }

    /**
     * [window] 是这次录音所属的语音代，由调用方在按键那一刻取好传进来。
     * 申请权限、写确认都可能拖很久，代必须跟着这次按键走，不能在对话框回来之后重取。
     */
    private fun recordVoice(purpose: String, origin: Peer, window: Long) {
        // 申请权限之前先对号：这一代语音要是已经撤销（voice.disarm / task.cancel / 断开 /
        // 新一轮 voice.arm），连麦克风权限都不该去要，更不该拿旧用途占新一代的窗口。
        if (!voiceStillAuthorized(origin, window)) {
            show("眼镜录音已作废：手机连接或本轮授权已失效")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // 授权用途、它所属的连接、还有按键那一刻的语音代都要留住：
            // voicePurpose 在按键那一刻就已经清掉了，代也只能从这里带回来。
            pendingVoice = PendingVoice(origin, purpose, window)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 3)
            return
        }
        thread(name = "leqi-voice-record") {
            var recorder: AudioRecord? = null
            try {
                // 线程排到队可能已经晚了一步：建 AudioRecord 之前再对一次号，
                // 撤销之后连麦克风都不该打开。
                if (!voiceStillAuthorized(origin, window)) {
                    show("眼镜录音已作废：手机连接或本轮授权已失效")
                    return@thread
                }
                val sampleRate = 16000
                val minimum = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                if (minimum <= 0) throw IllegalStateException("Unsupported microphone format")
                val created = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minimum, 4096),
                )
                recorder = created
                if (created.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("Microphone not initialized")
                }
                // 起录和撤销互斥：撤销线程拿的是同一把 captureLock，
                // 「校验 → startRecording」中间插不进来，撤销之后麦克风一定不会开录。
                // 校验不过就在这里收手，recorder 由 finally 释放，不会漏掉麦克风。
                synchronized(captureLock) {
                    if (!voiceStillAuthorized(origin, window)) {
                        show("眼镜录音已作废：手机连接或本轮授权已失效")
                        return@thread
                    }
                    created.startRecording()
                }
                show("眼镜正在录音约 3 秒（$purpose）")
                val pcm = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                val targetBytes = sampleRate * 2 * VOICE_RECORD_MS / 1000
                // 连接换了或这一代语音授权被撤销时立刻停手，录到一半的音频不再发出去。
                while (pcm.size() < targetBytes && !stopped && voiceStillAuthorized(origin, window)) {
                    val count = created.read(buffer, 0, Math.min(buffer.size, targetBytes - pcm.size()))
                    if (count <= 0) throw IllegalStateException("Microphone read failed: " + count)
                    pcm.write(buffer, 0, count)
                }
                created.stop()
                if (!voiceStillAuthorized(origin, window)) {
                    show("眼镜录音已作废：手机连接或本轮授权已失效")
                    return@thread
                }
                val wav = AudioWire.pcm16MonoWav(pcm.toByteArray(), sampleRate)
                sendBinary("audio", wav, purpose, "眼镜录音已发送：", origin)
            } catch (error: RuntimeException) {
                show("眼镜录音失败：" + error.javaClass.simpleName)
                if (!voiceStillAuthorized(origin, window)) return@thread
                sendEvent(
                    "audio.failed",
                    failurePayload(purpose, error.javaClass.simpleName),
                    "眼镜已报告录音失败",
                    origin,
                )
            } finally {
                recorder?.release()
            }
        }
    }

    /**
     * 分块发送字节流；整段消息的序号在同一把锁里分配，保证线上顺序和序号一致。
     * [origin] 是采集开始时的连接：连接换了就整段丢弃，旧照片和旧录音绝不进新会话。
     */
    private fun sendBinary(kind: String, bytes: ByteArray, tag: String?, successPrefix: String, origin: Peer) {
        if (peer !== origin) {
            show("媒体未发送：手机连接已更换")
            return
        }
        thread(name = "leqi-media-send") {
            try {
                val chunks = PhotoWire.chunks(bytes, 3000)
                val tagSuffix = if (tag.isNullOrEmpty()) "" else "|" + tag
                val header = bytes.size.toString() + "|" + PhotoWire.sha256(bytes) + tagSuffix
                synchronized(sendLock) {
                    if (peer !== origin) {
                        show("眼镜媒体未发送：手机连接已更换")
                        return@thread
                    }
                    origin.stream.writeUTF(nextMessage(kind + ".start", header))
                    for (chunk in chunks) {
                        origin.stream.writeUTF(nextMessage(kind + ".chunk", chunk))
                    }
                    origin.stream.writeUTF(nextMessage(kind + ".end", ""))
                    origin.stream.flush()
                }
                show(successPrefix + bytes.size + " 字节")
            } catch (error: Exception) {
                show("眼镜媒体传输失败：" + error.javaClass.simpleName)
            }
        }
    }

    private fun sendProbe(source: String) {
        sendEvent("probe.glasses", source, "眼镜已发送：$source")
    }

    /**
     * 异步发一条事件。[origin] 非空表示这条事件属于某次采集：连接换了就不发，
     * 免得旧任务的语音过期或拍摄失败落到新会话上。
     */
    private fun sendEvent(type: String, payload: String, success: String, origin: Peer? = null) {
        val current = peer
        if (current == null) {
            show("等待手机蓝牙连接")
            return
        }
        if (origin != null && origin !== current) {
            show("眼镜事件未发送：手机连接已更换（" + type + "）")
            return
        }
        thread(name = "leqi-bt-send") {
            try {
                synchronized(sendLock) {
                    if (peer !== current) return@thread
                    current.stream.writeUTF(nextMessage(type, payload))
                    current.stream.flush()
                }
                show(success)
            } catch (error: Exception) {
                show("眼镜发送失败：" + error.message)
            }
        }
    }

    /**
     * 同步写一条事件到 [origin] 这条连接，返回是否真的送到了。
     *
     * 只给「确认必须先于采集」这类顺序约束用：入口确认必须在开始拍摄之前真的落到线上，
     * 否则照片可能先到手机，被当成没有本轮授权而丢掉。
     *
     * 确认只认按键那一刻的那条连接：期间手机重连了就当确认没送到，直接作废本次拍摄，
     * 绝不能让新连接收到属于上一轮的确认，也不能让旧确认去顶替新会话的窗口。
     */
    private fun sendEventBlocking(type: String, payload: String, origin: Peer): Boolean {
        return try {
            synchronized(sendLock) {
                if (peer !== origin) return false
                origin.stream.writeUTF(nextMessage(type, payload))
                origin.stream.flush()
            }
            show("眼镜已发送：" + type)
            true
        } catch (error: Exception) {
            show("眼镜发送失败：" + error.message)
            false
        }
    }

    /** 只能在持有 [sendLock] 时调用：分配序号和写入必须原子。 */
    private fun nextMessage(type: String, payload: String): String =
        SessionWire.encode(BluetoothWire.SESSION, nextSequence++, type, payload)

    private fun show(message: String) {
        Log.i("LeqiGlassesBt", message)
        runOnUiThread { status?.text = message }
    }

    override fun onDestroy() {
        stopped = true
        unregisterReceiver(keyReceiver)
        stopPlayback()
        revokeAllWindows()
        synchronized(sendLock) {
            connectionCounter++
            peer = null
        }
        try {
            socket?.close()
        } catch (ignored: IOException) {
        }
        try {
            server?.close()
        } catch (ignored: IOException) {
        }
        super.onDestroy()
    }

    private companion object {
        const val BUTTON_CLICK = "com.android.action.ACTION_SPRITE_BUTTON_CLICK"
        const val BUTTON_DOWN = "com.android.action.ACTION_SPRITE_BUTTON_DOWN"
        const val BUTTON_UP = "com.android.action.ACTION_SPRITE_BUTTON_UP"
        const val BUTTON_DOUBLE_CLICK = "com.android.action.ACTION_SPRITE_BUTTON_DOUBLE_CLICK"
        const val BUTTON_LONG_PRESS = "com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS"
        const val DEBUG_PROBE = "com.leqi.experiment.glassesbt.DEBUG_PROBE"

        /** 语音采集授权窗口：手机发出 voice.arm 之后，只有这段时间内的实体键才会录音。 */
        const val VOICE_ARM_WINDOW_MS = 90_000L

        /** 入口拍摄授权窗口：只有刚播报到达之后的实体键才会拍摄。 */
        const val ENTRANCE_ARM_WINDOW_MS = 120_000L

        /** 单次录音时长。 */
        const val VOICE_RECORD_MS = 3000

        /**
         * 手机要求作废当前任务：手机侧在取消/断开时先发这条，再发 voice.disarm 等。
         * 眼镜收到后立刻撤销全部授权并停掉播报，不需要手机再补第二条指令。
         */
        const val TASK_CANCEL = "task.cancel"

        /** 播放时每次写入音频轨的字节数，避免一次性写入整段音频。 */
        const val PLAY_BLOCK_BYTES = 8192

        /** 等价于 Java 的 `payload.split("\\|", -1)`：保留末尾空字段，playId 才不会被截断。 */
        val HEADER_PATTERN: Pattern = Pattern.compile("\\|")

        /** 手机 → 眼镜的播放/媒体回执失败原因。 */
        fun failurePayload(playId: String?, reason: String?): String {
            val safe = reason?.replace('|', '_') ?: "unknown"
            return if (playId.isNullOrEmpty()) "-|$safe" else "$playId|$safe"
        }

        /**
         * 只解析标准 RIFF/WAVE 头，返回 {声道数, 采样率, 位深, 数据偏移, 数据长度}；不认识就返回 null。
         * 采样率必须从文件里读，不能假定手机合成的一定是 16 kHz。
         */
        fun parseWav(wav: ByteArray?): IntArray? {
            if (wav == null || wav.size < 44 ||
                wav[0].toInt() != 'R'.code || wav[1].toInt() != 'I'.code ||
                wav[2].toInt() != 'F'.code || wav[3].toInt() != 'F'.code ||
                wav[8].toInt() != 'W'.code || wav[9].toInt() != 'A'.code ||
                wav[10].toInt() != 'V'.code || wav[11].toInt() != 'E'.code
            ) {
                return null
            }
            var channels = 0
            var sampleRate = 0
            var bits = 0
            var dataOffset = -1
            var dataLength = 0
            var offset = 12
            while (offset + 8 <= wav.size) {
                val size = readInt(wav, offset + 4)
                if (size < 0 || offset + 8 + size > wav.size) return null
                val id = String(wav, offset, 4, StandardCharsets.US_ASCII)
                if ("fmt " == id) {
                    if (size < 16) return null
                    channels = readShort(wav, offset + 10)
                    sampleRate = readInt(wav, offset + 12)
                    bits = readShort(wav, offset + 22)
                } else if ("data" == id) {
                    dataOffset = offset + 8
                    dataLength = size
                }
                offset += 8 + size + (size and 1)
            }
            if (channels <= 0 || sampleRate <= 0 || bits != 16 || dataOffset < 0 || dataLength <= 0) {
                return null
            }
            return intArrayOf(channels, sampleRate, bits, dataOffset, dataLength)
        }

        fun readInt(source: ByteArray, offset: Int): Int =
            (source[offset].toInt() and 255) or ((source[offset + 1].toInt() and 255) shl 8) or
                ((source[offset + 2].toInt() and 255) shl 16) or
                ((source[offset + 3].toInt() and 255) shl 24)

        fun readShort(source: ByteArray, offset: Int): Int =
            (source[offset].toInt() and 255) or ((source[offset + 1].toInt() and 255) shl 8)
    }
}
