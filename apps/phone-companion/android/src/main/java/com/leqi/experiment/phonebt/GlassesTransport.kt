package com.leqi.experiment.phonebt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import com.leqi.experiment.BluetoothWire
import com.leqi.experiment.PhotoWire
import com.leqi.experiment.SessionWire
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern
import kotlin.concurrent.thread

/**
 * 手机端连眼镜自有蓝牙通道的设备传输适配器。
 *
 * 只管三件事：接上/断开这条 RFCOMM 通道、把上层消息按 v1 帧写出去、把收到的帧还原成事件或整段媒体
 * 回调给 [Listener]。任务状态机、Agent 决策、导航、模型调用和界面都不在这里：上层拿到
 * [Listener.onEvent] / [Listener.onAudio] / [Listener.onPhoto] 之后自己决定怎么处理。
 *
 * 线上协议与直连实现保持一致：RFCOMM 用 [BluetoothWire.SERVICE_ID]，只认 [BluetoothWire.SESSION]
 * 会话，UTF 帧、3000 字节分块、`长度|sha256[|标签]` 头、WAV/JPEG 头校验全部照旧。
 *
 * 重连：连接断开会按有界退避自动重试（[MAX_ATTEMPTS] 次，间隔 1s 起、20s 封顶），每次尝试都先作废上一轮，
 * 所以同一时刻永远只有一条 socket。重连只在传输层发生：本类不认识任何任务，也从不因为“又连上了”去补发
 * 之前发过的消息——录音授权、拍摄授权这类状态一律不自动恢复，重新开始必须由上层重新决定。
 * 手动重试就是再调一次 [connect]。
 *
 * 所有 Bluetooth 调用都以 [connect] 里的 BLUETOOTH_CONNECT 前置检查为准；本类只报告缺少权限，
 * 不申请权限。
 */
@SuppressLint("MissingPermission")
class GlassesTransport(private val context: Context, private val listener: Listener) {

    /** 传输层回调。全部在后台线程触发，实现方自己负责切回需要的线程。 */
    interface Listener {
        /** 通道已连上，[name] 是眼镜这一侧的设备名。 */
        fun onConnected(name: String)
        /**
         * 通道断开，或本次连接没能建立，[reason] 是可以直接展示给用户的说明。
         *
         * 每一次断开最多报一次：断开的第一时间就要让上层知道，好让它裁决正在跑的任务；
         * 之后的自动重连尝试不再重复报，改用 [onReconnecting]。但“连上过又断”是新的断开，
         * 会重新报一次——否则上层会以为眼镜还在线。
         */
        fun onDisconnected(reason: String)
        /** 正在准备第 [attempt] 次尝试，还要等 [delayMs] 毫秒。只用于本地状态显示。 */
        fun onReconnecting(attempt: Int, delayMs: Long)
        /** 有界重连已经用完了，[reason] 是最后一次失败原因；之后只能靠用户手动重试。 */
        fun onReconnectExhausted(reason: String)
        /** 不属于媒体分块的普通事件，原样带上 [type]、[payload] 和发送方分配的 [sequence]。 */
        fun onEvent(type: String, payload: String, sequence: Long)
        /** 一整段眼镜录音已按长度和 sha256 校验完整，[purpose] 是录音用途。 */
        fun onAudio(wav: ByteArray, purpose: String)
        /** 一整张入口照片已按长度和 sha256 校验完整，[tag] 是随照片带来的标签。 */
        fun onPhoto(jpeg: ByteArray, tag: String)
    }

    /** 单块消息的原始字节数；base64 之后仍在单条消息的负载上限内，是拆块粒度而不是长度上限。 */
    private companion object {
        const val CHUNK_BYTES = 3000
        const val PURPOSE_DESTINATION = "dest"
        val HEADER_PATTERN: Pattern = Pattern.compile("\\|")

        /** 自动重连的尝试上限（含第一次）。用完就停手，等用户手动重试。 */
        const val MAX_ATTEMPTS = 5

        /** 退避基数：第 n 次失败后等 BASE << (n-1)，上限 [MAX_RETRY_MS]。 */
        const val BASE_RETRY_MS = 1_000L
        const val MAX_RETRY_MS = 20_000L

        /** 退避等待按小片睡，醒来就能发现 stop/新一轮连接。 */
        const val RETRY_SLICE_MS = 250L

        const val CONNECTOR_THREAD_NAME = "leqi-bt-connector"
    }

    /** 序号分配和写入共用这一把锁：否则线程可能先拿到 5、6 却由 6 先落线，眼镜看到的就是错序事件。 */
    private val writeLock = Any()

    /**
     * 连接代号。每次 [connect] 或 [close] 都会加一，让上一轮的后台线程在收尾时认出自己已经过期，
     * 不去动新一轮的 socket，也不报一次假的断开。
     */
    private val generation = AtomicLong(0)

    @Volatile private var stopped = false
    @Volatile private var socket: BluetoothSocket? = null

    /** 当前写出口；由 [attachStream] / [detachStream] 在 [writeLock] 内维护。 */
    private var stream: DataOutputStream? = null

    /** 序号跨重连继续递增，不从 1 重来。只能在持有 [writeLock] 时读改写。 */
    private var nextSequence = 1L

    /**
     * 成功建立过连接的次数，用来分辨两种失败：压根没连上（一次断开只报一次），和连上之后又断了
     * （这是新的断开，必须重新报）。用原子量是因为过期线程在 [isCurrent] 判定之后仍可能走到自增那一行，
     * 丢一次计数只会多重报一次断开，永远不会漏报——方向是安全的。
     */
    private val connectedRuns = AtomicLong(0)

    /**
     * 开始连接已配对的眼镜，断了就自动按有界退避重连。
     *
     * 返回 null 表示连接流程已经启动，结果通过 [Listener] 通知；返回非 null 是当场就能说清楚的失败原因，
     * 调用方直接展示即可——权限、蓝牙开关、找不到已配对的眼镜都归这一类，它们要靠用户动作解决，
     * 不占用自动重连的次数。
     *
     * 重复调用等于手动重试：旧的一轮（含正在等待的退避和已建立的 socket）先被作废，再起新的一轮。
     * BLUETOOTH_CONNECT 没有授予时只返回提示，不会弹权限请求。
     */
    fun connect(): String? {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return "手机蓝牙连接权限未授予：请先在系统设置里允许本应用使用附近的设备"
        }
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return "手机蓝牙未开启"
        if (!adapter.isEnabled) return "手机蓝牙未开启"
        val glasses = findPairedGlasses(adapter) ?: return "未找到已配对的 Rokid 眼镜"

        val epoch = beginRun()
        thread(name = CONNECTOR_THREAD_NAME) { runConnector(glasses, epoch) }
        return null
    }

    /** 发一条短消息。未连接或写失败都返回 false，调用方负责明确降级。 */
    fun send(type: String, payload: String): Boolean {
        return synchronized(writeLock) {
            val out = stream ?: return@synchronized false
            // 负载本身不合法只是丢这一条，连接还是好的；只有写失败才算断开。
            val message = encode(type, payload) ?: return@synchronized false
            try {
                out.writeUTF(message)
                out.flush()
                true
            } catch (error: IOException) {
                // 写失败说明这条通道已经坏了：关掉 socket，正在 readUTF 的读循环会因此退出并走断开处理，
                // 而不是留下一条写不进、也没人发现的通道。
                stream = null
                closeSocket()
                false
            }
        }
    }

    /**
     * 发一段字节流，拆成 `kind.start/chunk/end`；[tag] 会作为头的第三段带上，空则不占段。
     *
     * [valid] 是调用方给的“这一轮还算数吗”，只在**持有 [writeLock] 时、真正写之前**检查：写缓冲
     * 满了会一直阻塞，一段音频的发送可能跨越一次重连，所以判断必须在锁内、贴着写做——锁外判完
     * 再进来，中间正好够一次重连，一个已经被关掉的旧出口就会把上一轮的字节灌进新 socket。
     * 判断失败按“这一条没发出去”返回 false，不关 socket、也不算断开。
     */
    fun sendBinary(kind: String, bytes: ByteArray?, tag: String?, valid: (() -> Boolean)? = null): Boolean {
        if (bytes == null || bytes.isEmpty()) return false
        return synchronized(writeLock) {
            if (valid != null && !valid()) return@synchronized false
            val out = stream ?: return@synchronized false
            val chunks = try {
                PhotoWire.chunks(bytes, CHUNK_BYTES)
            } catch (ignored: IllegalArgumentException) {
                return@synchronized false
            }
            try {
                val header = StringBuilder()
                    .append(bytes.size)
                    .append('|')
                    .append(PhotoWire.sha256(bytes))
                    .apply { if (!tag.isNullOrEmpty()) append('|').append(tag) }
                    .toString()
                val start = encode("$kind.start", header) ?: return@synchronized false
                out.writeUTF(start)
                for (chunk in chunks) {
                    // 分片之间再判一次：一段音频要写很久，中途被取消就不该再往线上灌后面的分片。
                    if (valid != null && !valid()) return@synchronized false
                    val message = encode("$kind.chunk", chunk) ?: return@synchronized false
                    out.writeUTF(message)
                }
                val end = encode("$kind.end", "") ?: return@synchronized false
                out.writeUTF(end)
                out.flush()
                true
            } catch (error: IOException) {
                // 和 [send] 一样：写不动就把通道关掉，让读循环醒过来走断开流程。
                stream = null
                closeSocket()
                false
            }
        }
    }

    /**
     * 结束这一轮连接：停掉读循环、作废写出口、关掉 socket，并且**不再重连**。
     *
     * 眼镜那边的服务端这时才会从 readUTF 上退回来重新 accept，所以解析出错退出时也必须走这里。
     * 关掉之后还可以再 [connect]。
     */
    fun close() {
        stopped = true
        generation.incrementAndGet()
        // 顺序不能反：先关 socket，再摘写出口。写线程可能正卡在 writeUTF 上并持着 [writeLock]，
        // 先 detachStream 就要等那把锁——而写正是因为这条 socket 还活着才醒不过来，于是调用方
        // （收尾和手动重连都在主线程）被吊死成 ANR，socket 也永远关不掉。先关 socket 能让阻塞的写
        // 当场抛 IOException 并自己放锁，随后的 detachStream 才是短的。
        closeSocketNoWait()
        detachStream()
    }

    /** 开始新一轮：作废上一轮线程、清掉旧写出口，返回这一轮的代号。 */
    private fun beginRun(): Long {
        stopped = false
        val epoch = generation.incrementAndGet()
        // 同 [close]：先打断可能卡住的写，再拿写锁摘出口，否则手动重连会先在主线程上等锁。
        closeSocketNoWait()
        detachStream()
        return epoch
    }

    private fun isCurrent(epoch: Long): Boolean = generation.get() == epoch

    /**
     * 连接线程主体：一直试到连上或者用完 [MAX_ATTEMPTS] 次。
     *
     * 每次断开最多报一次 [Listener.onDisconnected]：断开的第一时间报，让上层裁决正在跑的任务；
     * 之后的尝试只报 [Listener.onReconnecting]，不重复把同一件事说成新的断开。
     */
    private fun runConnector(target: BluetoothDevice, epoch: Long) {
        var attempt = 0
        var reportedDisconnect = false
        var lastReason = "连接未建立"
        while (isCurrent(epoch) && !stopped) {
            attempt++
            val runsBefore = connectedRuns.get()
            val failure = tryAttempt(target, epoch)
            if (!isCurrent(epoch) || stopped) return
            if (connectedRuns.get() != runsBefore) {
                // 这一轮真的连上过：它之后的断开是另一件事，不能被上一次的“已经报过”吃掉。
                reportedDisconnect = false
            }
            if (failure != null) {
                lastReason = failure
                if (!reportedDisconnect) {
                    reportedDisconnect = true
                    listener.onDisconnected(failure)
                }
            }
            if (attempt >= MAX_ATTEMPTS) break
            val delay = retryDelayMs(attempt)
            listener.onReconnecting(attempt + 1, delay)
            if (!waitBeforeRetry(delay, epoch)) return
        }
        if (isCurrent(epoch) && !stopped && reportedDisconnect) {
            listener.onReconnectExhausted("自动重连已停止（共尝试 $MAX_ATTEMPTS 次）：$lastReason")
        }
    }

    /** 退避间隔：1s、2s、4s、8s、16s…到 [MAX_RETRY_MS] 封顶。 */
    private fun retryDelayMs(attempt: Int): Long {
        val step = attempt - 1
        if (step >= 32) return MAX_RETRY_MS
        return minOf(BASE_RETRY_MS shl step, MAX_RETRY_MS)
    }

    /** 退避等待；被打断或这一轮已作废就返回 false，让连接线程收工。 */
    private fun waitBeforeRetry(delayMs: Long, epoch: Long): Boolean {
        var remaining = delayMs
        while (remaining > 0) {
            if (stopped || !isCurrent(epoch)) return false
            val slice = minOf(remaining, RETRY_SLICE_MS)
            try {
                Thread.sleep(slice)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            remaining -= slice
        }
        return !stopped && isCurrent(epoch)
    }

    /**
     * 试一次：建链、报连上、一直读到断开。
     *
     * 返回断开原因；null 表示这一轮已经被新的 connect/close 顶掉，或者连接正常结束，不该报断开。
     */
    private fun tryAttempt(target: BluetoothDevice, epoch: Long): String? {
        val peer = try {
            target.createRfcommSocketToServiceRecord(BluetoothWire.SERVICE_ID)
        } catch (error: Exception) {
            return if (stopped || !isCurrent(epoch)) null else "手机蓝牙通道创建失败：" + describe(error)
        }
        return try {
            // 先登记再 connect：connect 会一直阻塞，只有这条 socket 已经挂在字段上，close()（停止、
            // 手动重连、收尾）才关得掉它，从而把阻塞中的 connect 唤醒成一次可处理的失败。
            if (!registerSocket(peer, epoch)) {
                closeQuietly(peer)
                return null
            }
            try {
                peer.connect()
            } catch (error: IOException) {
                closeQuietly(peer)
                throw error
            }
            // 建链期间可能已经被新的 connect/close 顶掉了，那就把这条多余的连接放掉。
            // 只清自己那一条：新一轮可能已经挂上了它自己的 socket。
            if (stopped || !isCurrent(epoch)) {
                releaseSocket(peer)
                return null
            }
            attachStream(DataOutputStream(peer.outputStream))
            connectedRuns.incrementAndGet()
            listener.onConnected(target.name ?: "")
            readFrames(peer, epoch)
            null
        } catch (error: Exception) {
            if (stopped || !isCurrent(epoch)) null else "手机直连失败或断开：" + describe(error)
        } finally {
            // 只有仍然是当前这一轮才收尾；过期线程不能把新连接的 socket 关掉。
            if (isCurrent(epoch)) {
                detachStream()
                closeSocket()
            }
        }
    }

    /**
     * 把这条还在建链的 socket 登记成当前连接；false 表示这一轮已经作废，调用方要自己放掉它。
     *
     * 和 [attachStream] / [closeSocket] 共用同一把锁，所以“哪条 socket 是当前的”永远只有一个答案：
     * close() 不会漏掉一条正在连的通道，过期线程也不会把新连接的 socket 关掉。
     */
    private fun registerSocket(peer: BluetoothSocket, epoch: Long): Boolean = synchronized(writeLock) {
        if (stopped || !isCurrent(epoch)) return@synchronized false
        socket = peer
        true
    }

    /** 只放掉指定那一条：如果当前字段已经是别人的 socket，就什么都不做。 */
    private fun releaseSocket(peer: BluetoothSocket) {
        val mine = synchronized(writeLock) {
            if (socket === peer) {
                socket = null
                true
            } else {
                false
            }
        }
        if (mine) closeQuietly(peer)
    }

    /** 一直读到通道断开；解析错误按断开处理，由 [tryAttempt] 的 catch 统一转成原因。 */
    private fun readFrames(peer: BluetoothSocket, epoch: Long) {
        val input = DataInputStream(peer.inputStream)
        var photo: PhotoWire.Collector? = null
        var photoTag = ""
        var audio: PhotoWire.Collector? = null
        var audioPurpose = PURPOSE_DESTINATION
        while (!stopped && isCurrent(epoch)) {
            val event = SessionWire.decode(input.readUTF())
            if (BluetoothWire.SESSION != event.sessionId) continue
            when (event.type) {
                "media.start" -> {
                    val header = headerOf(event.payload, "photo")
                    photo = PhotoWire.Collector(header[0].toInt(), header[1])
                    photoTag = if (header.size == 3) header[2] else ""
                }
                "media.chunk" -> {
                    val active = photo ?: throw IllegalArgumentException("Unexpected photo chunk")
                    active.add(event.payload)
                }
                "media.end" -> {
                    val active = photo ?: throw IllegalArgumentException("Unexpected photo end")
                    val jpeg = active.finish()
                    photo = null
                    if (!isJpeg(jpeg)) throw IllegalArgumentException("Photo is not JPEG")
                    listener.onPhoto(jpeg, photoTag)
                }
                "audio.start" -> {
                    val header = headerOf(event.payload, "audio")
                    audio = PhotoWire.Collector(header[0].toInt(), header[1])
                    audioPurpose = if (header.size == 3) header[2] else PURPOSE_DESTINATION
                }
                "audio.chunk" -> {
                    val active = audio ?: throw IllegalArgumentException("Unexpected audio chunk")
                    active.add(event.payload)
                }
                "audio.end" -> {
                    val active = audio ?: throw IllegalArgumentException("Unexpected audio end")
                    val wav = active.finish()
                    audio = null
                    if (!isWav(wav)) throw IllegalArgumentException("Audio is not WAV")
                    listener.onAudio(wav, audioPurpose)
                }
                else -> listener.onEvent(event.type, event.payload, event.sequence)
            }
        }
    }

    /** 拆 `长度|sha256[|标签]` 头；段数不对就按协议错误处理，和写入侧一一对应。 */
    private fun headerOf(payload: String, label: String): Array<String> {
        val header = HEADER_PATTERN.split(payload, -1)
        if (header.size != 2 && header.size != 3) {
            throw IllegalArgumentException("Invalid $label header")
        }
        return header
    }

    /** 分配序号并编码。只能在持有 [writeLock] 时调用；编码失败不动序号，返回 null 丢这一条。 */
    private fun encode(type: String, payload: String): String? {
        val message = try {
            SessionWire.encode(BluetoothWire.SESSION, nextSequence, type, payload)
        } catch (ignored: IllegalArgumentException) {
            return null
        }
        nextSequence++
        return message
    }

    private fun attachStream(out: DataOutputStream) {
        synchronized(writeLock) { stream = out }
    }

    private fun detachStream() {
        synchronized(writeLock) { stream = null }
    }

    /** 关掉当前 socket 并清空引用；新连接可以随后建立。和登记 socket 共用一把锁，所以不会漏也不会误关。 */
    private fun closeSocket() {
        val peer = synchronized(writeLock) {
            val current = socket
            socket = null
            current
        }
        closeQuietly(peer)
    }

    /**
     * 关掉当前 socket，但**不等** [writeLock]——[close] / [beginRun] 专用。
     *
     * 这两个方法要作废的正是那条可能正被写着（甚至写满发送缓冲、卡在 writeUTF 上）的通道，而
     * writeLock 此刻就握在那个写线程手里。等锁等于互等：写线程要 socket 被关才会失败，调用方要
     * 写线程放锁才会往下走。所以这里只在 @Volatile 引用上先关后清，不拿锁。
     *
     * 竞争窗口是安全的：并发登记的 socket 会由 [tryAttempt] 在建链之后的过期检查里放掉，那一轮
     * 已经作废（[stopped] 或代号对不上），不会把新连接的 socket 误关——新一轮登记前也会再走一次
     * [beginRun]，同样先关掉字段里的这一条。
     */
    private fun closeSocketNoWait() {
        val peer = socket
        socket = null
        closeQuietly(peer)
    }

    private fun closeQuietly(peer: BluetoothSocket?) {
        if (peer == null) return
        try {
            peer.close()
        } catch (ignored: IOException) {
            // 关不上也只能算了：引用已经清掉，重连不受影响。
        }
    }

    private fun findPairedGlasses(adapter: BluetoothAdapter): BluetoothDevice? {
        val paired = adapter.bondedDevices ?: return null
        for (candidate in paired) {
            val name = candidate.name ?: continue
            if (name.startsWith("Glasses_") || name.startsWith("RG_") || name.contains("Rokid")) {
                return candidate
            }
        }
        return null
    }

    private fun describe(error: Throwable): String {
        val message = error.message
        return if (message.isNullOrEmpty()) error.javaClass.simpleName
        else error.javaClass.simpleName + " " + message
    }

    private fun isJpeg(jpeg: ByteArray): Boolean =
        jpeg.size >= 3 && (jpeg[0].toInt() and 255) == 255 &&
            (jpeg[1].toInt() and 255) == 216 && (jpeg[2].toInt() and 255) == 255

    private fun isWav(wav: ByteArray): Boolean =
        wav.size >= 44 && wav[0].toInt() == 'R'.code && wav[1].toInt() == 'I'.code &&
            wav[2].toInt() == 'F'.code && wav[3].toInt() == 'F'.code
}
