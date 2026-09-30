package com.leqi.experiment.phonebt

import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * 手机到眼镜的播报出口。
 *
 * 旧的 `SpeechChannel` 挂在 [EventLink] 上；本类是它的 Agent 通道版本：合成走 [OfflineTtsBridge]，
 * 发送走 [GlassesTransport] 的 `play.start` / `play.chunk` / `play.end`，回执等 `play.done` / `play.failed`。
 * 线上帧格式完全照旧：`play.start` 的 header 是 `字节数|sha256|playId`，`play.end` payload 为空，
 * `play.done` 的 payload 是 playId，`play.failed` 的 payload 是 `playId|原因`。
 *
 * 三条硬约束：
 * 1. 串行：同一时刻只允许一段音频在眼镜上播，必须等到 `play.done` 或 `play.failed` 再发下一段；
 * 2. 去重：完全相同的文本在去重窗口内只播一次——导航文字回调会重复触发，不能重复播报；
 * 3. 有界：长文本先拆成短句，队列满了丢最旧的普通播报，不做无上限堆积。
 *
 * 边界：本类只做播报，不做 Agent 决策，也没有任务状态机。播什么、什么时候播、播完之后做什么都由
 * 调用方决定；[say] 的 `onComplete` 就是给调用方定序用的钩子——例如“提示音播完再授权眼镜录音”，
 * 否则提示音会被录进用户的回答里。[OfflineTtsBridge] 的加载与释放仍归调用方，本类只调用它合成。
 *
 * 线程约定：所有公开方法都可以在任意线程调用；[Listener] 和 `onComplete` 在播报线程（入队时就被
 * 丢弃的那一句除外，它在单独的 `leqi-speech-callback` 线程上）触发，实现方自己负责切回需要的线程。
 */
class AgentSpeechOutput(
    private val transport: GlassesTransport,
    tts: OfflineTtsBridge,
    private val listener: Listener,
    /** 合成接缝：默认走离线 TTS；允许抛异常，失败的那一句只记状态、不播。 */
    private val synthesize: (String) -> ByteArray = tts::synthesizeToWav,
) : AutoCloseable {

    /** 播报状态回调，用于诊断和界面显示。 */
    fun interface Listener {
        fun onSpeechStatus(message: String)
    }

    /** 一句待播报的文本，以及它说完（或确定不会说）之后要做的事。 */
    private class Utterance(
        val text: String,
        val urgent: Boolean,
        val deduplicate: Boolean,
        val completion: Completion?,
    )

    private class Completion(private val count: Int, private val callback: (Boolean) -> Unit) {
        private var remaining = count
        private var successful = true

        @Synchronized fun mark(played: Boolean) {
            if (remaining <= 0) return
            if (!played) successful = false
            remaining--
            if (remaining == 0) callback(successful)
        }
    }

    private val queue: ArrayDeque<Utterance> = ArrayDeque()
    private val queueLock = ReentrantLock()
    private val queueReady = queueLock.newCondition()
    private val playbackLock = ReentrantLock()
    private val playbackDone = playbackLock.newCondition()

    @Volatile private var running = true
    private var worker: Thread? = null

    /** 正在等回执的那一段；只允许在持有 [playbackLock] 时读写。 */
    private var pendingPlayId: String? = null
    private var pendingPlaybackSuccess: Boolean? = null

    /** 序号分配和去重记录共用 [queueLock]，和旧通道一样。 */
    private var nextPlayId = 1L
    private var lastSpokenKey: String? = null
    private var lastSpokenAt = 0L

    /** 启动播报线程；重复调用无效果。 */
    fun start() {
        queueLock.withLock {
            if (worker != null) return
            worker = thread(name = WORKER_THREAD_NAME, isDaemon = true) { runLoop() }
        }
    }

    /**
     * 排队播报一段文本。长文本会先按标点拆成短句，全部收到眼镜播放完成回执才报告成功。
     *
     * [urgent] 为 true 时插到队首，但仍然不会打断正在播的那一段。空文本或只有空白的文本不占用队列，
     * 直接报告失败，保证调用方不会一直等下去。
     */
    @JvmOverloads
    fun say(
        text: String, urgent: Boolean = false, deduplicate: Boolean = true,
        onComplete: ((Boolean) -> Unit)? = null,
    ) {
        val segments = split(text)
        if (segments.isEmpty()) {
            onComplete?.invoke(false)
            return
        }
        val completion = onComplete?.let { Completion(segments.size, it) }
        val dropped = ArrayList<Utterance>()
        queueLock.withLock {
            if (urgent) {
                // 逆序 addFirst，让几段之间保持原来的先后顺序。
                for (index in segments.size - 1 downTo 0) {
                    queue.addFirst(Utterance(segments[index], true, deduplicate, completion))
                }
                while (queue.size > MAX_QUEUED) {
                    // 优先丢普通播报；全是紧急播报时退回丢最旧的一条，保证上限不被突破。
                    if (dropOldestNormal(dropped)) continue
                    if (queue.isEmpty()) break
                    queue.pollLast()?.let(dropped::add)
                }
            } else {
                for (index in segments.indices) {
                    val segment = segments[index]
                    if (queue.size >= MAX_QUEUED && !dropOldestNormal(dropped)) {
                        listener.onSpeechStatus("播报队列已满，丢弃：$segment")
                        dropped.add(Utterance(segment, false, deduplicate, completion))
                        continue
                    }
                    queue.addLast(Utterance(segment, false, deduplicate, completion))
                }
            }
            queueReady.signalAll()
        }
        for (utterance in dropped) fire(utterance.completion, false)
    }

    /** 眼镜回执 `play.done`；payload 就是 playId。 */
    fun onPlaybackDone(playId: String) {
        completePlayback(playId, true)
    }

    /** 眼镜回执 `play.failed`；payload 是 `playId|原因`，由调用方拆开后传进来。 */
    fun onPlaybackFailed(playId: String, reason: String) {
        listener.onSpeechStatus("眼镜播报失败：$reason")
        completePlayback(playId, false)
    }

    /**
     * 把 [GlassesTransport.Listener.onEvent] 收到的原始事件直接转进来。
     *
     * 返回 true 表示这条事件已经由播报通道消费（`play.done` / `play.failed`），宿主不必再处理；
     * 返回 false 表示与播报无关，宿主照旧自行分派。
     */
    fun onGlassesEvent(type: String, payload: String): Boolean {
        when (type) {
            "play.done" -> {
                onPlaybackDone(payload)
                return true
            }
            "play.failed" -> {
                val separator = payload.indexOf('|')
                val playId = if (separator < 0) payload else payload.substring(0, separator)
                val reason = if (separator < 0) "未知" else payload.substring(separator + 1)
                onPlaybackFailed(playId, reason)
                return true
            }
            else -> return false
        }
    }

    /**
     * 清空尚未播报的内容；已经在播的那一段不打断。
     *
     * 同时清掉去重记录：调用方（开始新任务、取消、失败）接下来那句话必须能说出来，不能被上一条
     * 相同文本的去重窗口挡住。
     */
    fun clearQueue() {
        val discarded = queueLock.withLock {
            val copy = ArrayList(queue)
            queue.clear()
            lastSpokenKey = null
            copy
        }
        // 被丢弃的那一句也要兑现 onComplete：调用方可能在等它才会推进任务。
        for (utterance in discarded) fire(utterance.completion, false)
    }

    /**
     * 停掉播报线程、清空队列、作废等待中的回执。关闭是不可逆的：本实例之后再 [say] 不会播出去，
     * 要继续播报只能新建一个实例（和旧通道的语义一致）。[OfflineTtsBridge] 不在这里释放，仍归调用方。
     */
    override fun close() {
        running = false
        val discarded = queueLock.withLock {
            val copy = ArrayList(queue)
            queue.clear()
            val active = worker
            worker = null
            active?.interrupt()
            queueReady.signalAll()
            copy
        }
        for (utterance in discarded) fire(utterance.completion, false)
        playbackLock.withLock {
            pendingPlayId = null
            pendingPlaybackSuccess = false
            playbackDone.signalAll()
        }
    }

    /** 在播报线程上报告播放结果，只有眼镜确认播放完成才是成功。 */
    private fun runQuietly(completion: Completion?, played: Boolean) {
        if (completion == null) return
        try {
            completion.mark(played)
        } catch (error: Exception) {
            listener.onSpeechStatus("播报后续动作失败：" + describe(error))
        }
    }

    /** 在播报线程之外执行后续动作，用于入队时就被丢弃的那一句。 */
    private fun fire(completion: Completion?, played: Boolean) {
        if (completion == null) return
        thread(name = CALLBACK_THREAD_NAME, isDaemon = true) { runQuietly(completion, played) }
    }

    private fun dropOldestNormal(dropped: MutableList<Utterance>): Boolean {
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            val utterance = iterator.next()
            if (!utterance.urgent) {
                iterator.remove()
                dropped.add(utterance)
                return true
            }
        }
        return false
    }

    private fun runLoop() {
        while (running) {
            val utterance = nextUtterance() ?: return
            if (utterance.deduplicate && isDuplicate(utterance.text)) {
                runQuietly(utterance.completion, false)
                continue
            }
            speak(utterance)
        }
    }

    /** 取下一句；返回 null 表示已经停止或被要求退出。 */
    private fun nextUtterance(): Utterance? {
        queueLock.withLock {
            while (running && queue.isEmpty()) {
                try {
                    // 带超时地等：新句子由 say 唤醒，停止靠超时兜底重查 running。
                    queueReady.await(500L, TimeUnit.MILLISECONDS)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
            if (!running) return null
            return queue.pollFirst()
        }
    }

    private fun isDuplicate(text: String): Boolean = queueLock.withLock {
        val last = lastSpokenKey
        if (last != null && last == text && System.currentTimeMillis() - lastSpokenAt < DEDUP_WINDOW_MS) {
            return@withLock true
        }
        queue.any { it.text == text }
    }

    private fun completePlayback(playId: String, played: Boolean) {
        playbackLock.withLock {
            val pending = pendingPlayId ?: return
            if (pending != playId) return
            pendingPlayId = null
            pendingPlaybackSuccess = played
            playbackDone.signalAll()
        }
    }

    /** 所有路径都报告结果；失败不能伪装成眼镜已经播完。 */
    private fun speak(utterance: Utterance) {
        val played = try { speakOnce(utterance) } catch (error: Exception) {
            listener.onSpeechStatus("播报失败：" + describe(error))
            false
        }
        runQuietly(utterance.completion, played)
    }

    /**
     * 交给传输层在写锁里复查这个出口还作不作数。
     *
     * 已经 [close] 的实例记的是上一轮的通道，重连之后 socket 已经换了一条，它连一个字节都不该再写。
     */
    private fun isRunning(): Boolean = running

    private fun speakOnce(utterance: Utterance): Boolean {
        val wav = try {
            synthesize(utterance.text)
        } catch (error: Exception) {
            listener.onSpeechStatus("语音合成失败，未播报：" + describe(error))
            return false
        }
        if (wav.isEmpty()) {
            listener.onSpeechStatus("语音合成为空，未播报：" + utterance.text)
            return false
        }
        if (wav.size > MAX_PLAY_BYTES) {
            listener.onSpeechStatus("合成音频过大，未播报：" + wav.size + " 字节")
            return false
        }
        // 合成是阻塞的（离线引擎要跑几秒），回来时这一轮可能已经被取消、断开或重连：这时候既不写
        // 去重记录（否则下一轮同一句话会被这条没播出去的记录挡住），也不占回执槽，更不往外发。
        if (!running) return false
        val playId = queueLock.withLock {
            lastSpokenKey = utterance.text
            lastSpokenAt = System.currentTimeMillis()
            (nextPlayId++).toString()
        }
        playbackLock.withLock { pendingPlayId = playId; pendingPlaybackSuccess = null }
        // 发送这一侧还有一次跨重连的窗口，所以在写锁内用 [isRunning] 复查：这个出口一旦被关掉，
        // 它连一个字节都不该再落到（可能已经重连过的）socket 上。
        if (!transport.sendBinary("play", wav, playId, ::isRunning)) {
            playbackLock.withLock { pendingPlayId = null }
            // 已经停了就不是“连接不可用”，是这一轮被作废：不要再报一次假的通道故障。
            if (!running) return false
            listener.onSpeechStatus("播报未发出：与眼镜的连接不可用")
            return false
        }
        listener.onSpeechStatus("已送往眼镜播报：" + utterance.text)
        return awaitPlayback(playId, estimateDurationMs(wav) + PLAYBACK_GRACE_MS)
    }

    /** 等这一段自己的回执；超时或收到别的 playId 都直接返回，绝不无限等下去。 */
    private fun awaitPlayback(playId: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() +
            timeoutMs.coerceIn(MIN_PLAYBACK_WAIT_MS, MAX_PLAYBACK_WAIT_MS)
        playbackLock.withLock {
            while (running && pendingPlayId == playId) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) {
                    pendingPlayId = null
                    listener.onSpeechStatus("等待眼镜播报回执超时，继续下一段")
                    return false
                }
                try {
                    playbackDone.await(minOf(remaining, 1000L), TimeUnit.MILLISECONDS)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            return pendingPlaybackSuccess == true
        }
    }

    companion object {
        /** 完全相同的文本在这个窗口内只播一次。 */
        private const val DEDUP_WINDOW_MS = 30_000L

        /** 队列上限，超过就丢最旧的普通播报。 */
        private const val MAX_QUEUED = 6

        /**
         * 软拆句长度：在标点处且已有这么多字就断开。
         * 拆句是为了压低首句延迟，但每段都要走一次合成加一次眼镜往返，所以不能拆得太碎。
         */
        private const val SOFT_SPLIT_CHARS = 60

        /** 硬拆句长度：没有标点时按这个长度强断，必须小于合成的单段上限。 */
        private const val HARD_SPLIT_CHARS = 120

        /** 等待眼镜回执的兜底时长，超过就当作播报结束继续下一段。 */
        private const val PLAYBACK_GRACE_MS = 15_000L

        private const val MIN_PLAYBACK_WAIT_MS = 20_000L
        private const val MAX_PLAYBACK_WAIT_MS = 120_000L

        /**
         * 单条播报音频的上限。口径必须和写入侧（`EventLink.MAX_PLAY_BYTES` / 眼镜端解析）一致，
         * 否则刚好卡在边界上的音频会出现“这边发出去、那边拒收”。两边都改的时候要一起改。
         */
        private const val MAX_PLAY_BYTES = 3 * 1024 * 1024

        private const val WORKER_THREAD_NAME = "leqi-speech"
        private const val CALLBACK_THREAD_NAME = "leqi-speech-callback"

        /** AudioWire 生成的规范 WAV 头长度。 */
        private const val WAV_HEADER_BYTES = 44

        /** 在标点处优先断开；没有标点就硬断，保证每段都在合成上限之内。 */
        internal fun split(text: String): List<String> {
            val parts = ArrayList<String>()
            val current = StringBuilder()
            for (value in text) {
                if (value == '\n' || value == '\r' || value == '\t') {
                    flush(parts, current)
                    continue
                }
                current.append(value)
                val softBreak = value == '。' || value == '！' || value == '？' || value == '；' ||
                    value == '，' || value == '、' || value == ',' || value == ';'
                if ((softBreak && current.length >= SOFT_SPLIT_CHARS) ||
                    current.length >= HARD_SPLIT_CHARS
                ) {
                    flush(parts, current)
                }
            }
            flush(parts, current)
            return parts
        }

        private fun flush(parts: MutableList<String>, current: StringBuilder) {
            val value = current.toString().trim()
            current.setLength(0)
            if (value.isNotEmpty()) parts.add(value)
        }

        /** 从 AudioWire 生成的规范 44 字节头里读时长，用于估计等待回执的上限。 */
        private fun estimateDurationMs(wav: ByteArray): Long {
            if (wav.size <= WAV_HEADER_BYTES) return 0
            val channels = readShort(wav, 22)
            val sampleRate = readInt(wav, 24)
            val bits = readShort(wav, 34)
            val bytesPerSecond = sampleRate * channels * (bits / 8)
            if (bytesPerSecond <= 0) return 1000
            return (wav.size - WAV_HEADER_BYTES) * 1000L / bytesPerSecond
        }

        private fun readInt(source: ByteArray, offset: Int): Int =
            (source[offset].toInt() and 255) or
                ((source[offset + 1].toInt() and 255) shl 8) or
                ((source[offset + 2].toInt() and 255) shl 16) or
                ((source[offset + 3].toInt() and 255) shl 24)

        private fun readShort(source: ByteArray, offset: Int): Int =
            (source[offset].toInt() and 255) or ((source[offset + 1].toInt() and 255) shl 8)

        private fun describe(error: Throwable): String {
            val message = error.message
            val name = error.javaClass.simpleName
            return if (message.isNullOrEmpty()) name else name + "：" + message
        }
    }
}
