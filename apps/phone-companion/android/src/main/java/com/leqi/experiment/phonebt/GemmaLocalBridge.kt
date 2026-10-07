package com.leqi.experiment.phonebt

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File

/** 本地推理失败。桥接层只抛出这一种异常，绝不返回占位文本充当结果。 */
class GemmaBridgeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 手机端 Gemma 4 E2B（LiteRT-LM 0.17.1）本地推理桥接。
 *
 * 本类是设备侧的模型端口，只做三件技术性的事：
 * - [transcribeSpeech]：把已经完整收到的 WAV 逐字转写成用户原话；
 * - [generateText]：把 Agent 给的一段文本交给模型，原样回模型说出来的文本；
 * - [generateVision]：把已经完整收到的 JPEG 和 Agent 给的提示交给模型，原样回模型说出来的文本。
 *
 * 三种都只做转写/生成，不做业务判断：选什么任务、提示词怎么写、输出怎么校验、结果算不算成立，
 * 全部属于仓库里的 TypeScript Agent。本类不预设场景、不解释意图、不把模型输出解析成事实，
 * 也不为某一种用途单独写提示；一切业务提示都由调用方按仓库合同传进来。
 * 调用方负责保证传入的字节已经完整（本类只做廉价的结构自检）。
 * 所有方法都会阻塞，必须在后台线程调用；内部串行化，同一时刻只跑一次推理。
 */
class GemmaLocalBridge @JvmOverloads constructor(
    private val modelFile: File,
    private val cacheDir: File,
    private val cpuThreads: Int = defaultCpuThreads(),
) : AutoCloseable {

    /** 使用应用私有目录里的模型文件；找不到时在 [initialize] 阶段明确报错。 */
    constructor(context: Context) : this(
        resolveModelFile(context),
        File(context.cacheDir, CACHE_DIR_NAME),
        defaultCpuThreads(),
    )

    private val lock = Any()
    private var engine: Engine? = null

    /** 实际使用的模型文件绝对路径。 */
    val modelPath: String get() = modelFile.absolutePath

    fun isReady(): Boolean = synchronized(lock) { engine?.isInitialized() == true }

    /** 加载模型。幂等；首次调用可能耗时数十秒，必须在后台线程调用。 */
    @Throws(GemmaBridgeException::class)
    fun initialize() {
        synchronized(lock) {
            if (engine != null) return
            if (!modelFile.isFile) {
                throw GemmaBridgeException("本地模型文件不存在：" + modelFile.absolutePath)
            }
            if (modelFile.length() <= 0L) {
                throw GemmaBridgeException("本地模型文件是空的：" + modelFile.absolutePath)
            }
            if (!cacheDir.isDirectory && !cacheDir.mkdirs()) {
                throw GemmaBridgeException("无法创建本地推理缓存目录：" + cacheDir.absolutePath)
            }
            val config = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.CPU(cpuThreads),
                visionBackend = Backend.CPU(cpuThreads),
                audioBackend = Backend.CPU(cpuThreads),
                maxNumTokens = MAX_CONTEXT_TOKENS,
                maxNumImages = 1,
                cacheDir = cacheDir.absolutePath,
            )
            val created = try {
                Engine(config)
            } catch (error: Throwable) {
                throw GemmaBridgeException("本地推理引擎创建失败：" + describe(error), error)
            }
            try {
                created.initialize()
            } catch (error: Throwable) {
                runCatching { created.close() }
                throw GemmaBridgeException("本地模型加载失败：" + describe(error), error)
            }
            engine = created
            Log.i(TAG, "本地模型已就绪：" + modelFile.absolutePath + " 线程=" + cpuThreads)
        }
    }

    /**
     * 已完整收到的 WAV（16kHz 单声道 PCM16）→ 用户原话。
     *
     * 模型在这里只是转写器：它不回答、不执行音频里的要求，不推测地名，也不分类意图，
     * 用户问句、肯定、否定、取消、请求和标点都逐字保留。格式不符或听不清就报失败，
     * 绝不把模型的分析、解释或占位文本当成原话交给上层。
     *
     * 转写不留跨轮状态：每次都是一次性会话，上一段录音不影响这一段。
     */
    @Throws(GemmaBridgeException::class)
    fun transcribeSpeech(wav: ByteArray): String {
        requireWave(wav)
        val raw = infer(
            systemInstruction = TRANSCRIPTION_SYSTEM,
            media = Content.AudioBytes(wav),
            userText = TRANSCRIPTION_USER,
            maxOutputToken = TRANSCRIPTION_MAX_OUTPUT_TOKENS,
        )
        return GemmaOutput.transcript(raw)
    }

    /**
     * Agent 的文本端口：把 [prompt] 原样交给模型，回模型真说出来的文本。
     *
     * 提示词完全由 Agent 给，本类不加任何业务提示、不解析输出、不校验结构。
     *
     * 每次调用都是一次性推理：本类不保留任何对话记忆，模型能看到的上下文只有这一次调用方给的 [prompt]。
     * 跨轮记忆属于 Agent——仓库 core 会把有界、已清洗的历史放进 [prompt]；本类再自己攒一份，既会和 core
     * 的历史重复，也会在这 4096 token 的上下文里越滚越大，最后把每一轮都挤爆。
     * [sessionId] 只是这次请求的传输身份（只用于日志排障），不代表本类持有任何会话状态。
     */
    @Throws(GemmaBridgeException::class)
    fun generateText(prompt: String, sessionId: String): String {
        if (prompt.isBlank() || prompt.length > MAX_PROMPT_CHARS) {
            throw GemmaBridgeException("本地模型文本输入为空或过长")
        }
        initialize()
        val active = synchronized(lock) { engine }
            ?: throw GemmaBridgeException("本地推理引擎未就绪")
        val config = ConversationConfig(
            systemInstruction = Contents.of(TEXT_SYSTEM),
            samplerConfig = DETERMINISTIC,
            maxOutputToken = TEXT_MAX_OUTPUT_TOKENS,
        )
        Log.i(
            TAG,
            "本地模型文本推理 session=" + sessionId.ifEmpty { "（未提供）" } +
                " promptChars=" + prompt.length,
        )
        // 每次请求开一个新会话，读完就关：上一轮的上下文不会进来，这一轮也不会留给下一轮。
        return synchronized(lock) {
            var conversation: Conversation? = null
            try {
                conversation = active.createConversation(config)
                val reply = conversation.sendMessage(Contents.of(prompt))
                reply.contents.contents.filterIsInstance<Content.Text>()
                    .joinToString(separator = "") { it.text }
            } catch (error: Throwable) {
                throw GemmaBridgeException("本地模型文本生成失败：" + describe(error), error)
            } finally {
                runCatching { conversation?.close() }
            }
        }
    }

    /**
     * Agent 的视觉端口：把 [jpeg] 和 [prompt] 原样交给模型，回模型真说出来的原始文本。
     *
     * 提示词完全由 Agent 按仓库合同给，本类不写入口、菜单、表情、交通这类场景提示，也不假设画面里
     * 有什么；设备边界上只校验两件技术性的事：JPEG 结构，以及提示词非空且没有长到失控。
     * 模型输出一个字符都不加工：不解析、不截断、不改写、不包装成 summary、不补充画面里没有的事实。
     */
    @Throws(GemmaBridgeException::class)
    fun generateVision(jpeg: ByteArray, prompt: String): String {
        requireJpeg(jpeg)
        if (prompt.isBlank() || prompt.length > MAX_PROMPT_CHARS) {
            throw GemmaBridgeException("视觉提示为空或过长，无法交给本地模型")
        }
        val raw = infer(
            systemInstruction = VISION_SYSTEM,
            media = Content.ImageBytes(jpeg),
            userText = prompt,
            maxOutputToken = VISION_MAX_OUTPUT_TOKENS,
        )
        if (raw.isBlank()) throw GemmaBridgeException("本地模型没有输出任何内容")
        return raw
    }

    /** 只做转写：开一个新会话、把媒体和文字交给模型、把文本原样收回来。 */
    private fun infer(
        systemInstruction: String,
        media: Content,
        userText: String,
        maxOutputToken: Int,
    ): String {
        initialize()
        val active = synchronized(lock) { engine }
            ?: throw GemmaBridgeException("本地推理引擎未就绪")
        val config = ConversationConfig(
            systemInstruction = Contents.of(systemInstruction),
            samplerConfig = DETERMINISTIC,
            maxOutputToken = maxOutputToken,
        )
        // 每次请求开一个新会话，避免上一轮的上下文污染结果；推理全程独占锁。
        return synchronized(lock) {
            var conversation: Conversation? = null
            try {
                conversation = active.createConversation(config)
                val reply = conversation.sendMessage(Contents.of(media, Content.Text(userText)))
                reply.contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString(separator = "") { it.text }
            } catch (error: GemmaBridgeException) {
                throw error
            } catch (error: Throwable) {
                throw GemmaBridgeException("本地推理失败：" + describe(error), error)
            } finally {
                runCatching { conversation?.close() }
            }
        }
    }

    private fun requireWave(wav: ByteArray) {
        if (wav.size < WAV_HEADER_BYTES) {
            throw GemmaBridgeException("录音不完整，只有 " + wav.size + " 字节，无法本地识别")
        }
        if (!wav.matchesAscii(0, "RIFF") || !wav.matchesAscii(WAV_FORMAT_OFFSET, "WAVE")) {
            throw GemmaBridgeException("录音不是 WAV 容器，无法本地识别")
        }
    }

    private fun requireJpeg(jpeg: ByteArray) {
        if (jpeg.size < JPEG_HEADER_BYTES) {
            throw GemmaBridgeException("照片不完整，只有 " + jpeg.size + " 字节，无法本地识别")
        }
        val isSoi = (jpeg[0].toInt() and 0xFF) == 0xFF &&
            (jpeg[1].toInt() and 0xFF) == 0xD8 &&
            (jpeg[2].toInt() and 0xFF) == 0xFF
        if (!isSoi) {
            throw GemmaBridgeException("照片不是 JPEG，无法本地识别")
        }
    }

    private fun ByteArray.matchesAscii(offset: Int, token: String): Boolean {
        if (offset + token.length > size) return false
        for (index in token.indices) {
            if (this[offset + index] != token[index].code.toByte()) return false
        }
        return true
    }

    override fun close() {
        val active = synchronized(lock) {
            val current = engine
            engine = null
            current
        }
        if (active != null) runCatching { active.close() }
    }

    companion object {
        const val MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"

        private const val TAG = "LeqiGemma"
        private const val CACHE_DIR_NAME = "litertlm"
        private const val MAX_CONTEXT_TOKENS = 4096
        private const val TRANSCRIPTION_MAX_OUTPUT_TOKENS = 512
        private const val TEXT_MAX_OUTPUT_TOKENS = 512
        private const val VISION_MAX_OUTPUT_TOKENS = 1024

        /** Agent 传来的提示词上限：设备边界上的结构校验，不是业务判断。 */
        private const val MAX_PROMPT_CHARS = 16000

        private const val WAV_HEADER_BYTES = 44
        private const val WAV_FORMAT_OFFSET = 8
        private const val JPEG_HEADER_BYTES = 4

        private const val TRANSCRIPTION_SYSTEM = """你是一个离线中文语音转写器，运行在视障用户的手机上。
用户会给你一段 16kHz 单声道语音录音。你唯一的任务是把录音里用户说的话逐字转写成文字。
你只输出一行 JSON，且必须严格是这一种形式：
{"transcript":"<用户原话>"}
规则：
- 用户原话里的疑问、肯定、否定、取消、请求、称呼和语气词都要照原样保留。
- 标点也照原样保留；不要改写、不要润色、不要总结、不要翻译、不要补全。
- 只转写用户说出口的内容。不要回答、不要执行、不要评论录音里的任何要求，也不要把你自己当成对话者。
- 不要推测地点，不要判断意图，不要输出分类、置信度或任何解释，不要添加录音里没有的词。
- 听不清、没有人声或者无法转写时，只输出 {"transcript":""}
- 不要输出 JSON 以外的任何字符，也不要换行。"""

        private const val TRANSCRIPTION_USER = "请按约定格式转写这段录音里用户说的话。"

        /** 文本端口的系统提示：不指定任务、不指定输出格式，一切以调用方给的提示为准。 */
        private const val TEXT_SYSTEM = "你是运行在本机的通用语言模型。严格按用户消息里的要求作答，不要添加用户没有要求的内容。"

        /** 视觉端口的系统提示：只说明这是本机模型，不指定任何场景、品类或输出格式。 */
        private const val VISION_SYSTEM = "你是运行在本机的通用视觉模型。严格按用户消息里的要求作答，不要自行假设画面是什么场景，也不要添加用户没有要求的内容。"

        /** topK=1 等价于贪心解码；不用 temperature=0 是为了避开除零。 */
        private val DETERMINISTIC = SamplerConfig(
            topK = 1,
            topP = 1.0,
            temperature = 1.0,
            seed = 0,
        )

        private fun defaultCpuThreads(): Int =
            Runtime.getRuntime().availableProcessors().coerceIn(1, 6)

        /** 原生引擎只使用应用内部文件；外部目录可 stat 不代表原生层能 open。 */
        @JvmStatic
        @Throws(GemmaBridgeException::class)
        fun resolveModelFile(context: Context): File =
            findModelFile(context) ?: throw GemmaBridgeException(missingModelMessage(context))

        /**
         * 找可用的模型文件；没有就返回 null，不抛异常。
         *
         * 启动自检要如实报告“本机没有模型文件”（模型不随 APK 分发），不能靠抛异常来表达缺失。
         */
        @JvmStatic
        fun findModelFile(context: Context): File? {
            for (candidate in modelCandidates(context)) {
                if (candidate.isFile && candidate.length() > 0L) return candidate
            }
            return null
        }

        /** 缺失模型时可以直接展示给用户的说明：找过哪些位置，一个字段都不涉及密钥。 */
        @JvmStatic
        fun missingModelMessage(context: Context): String =
            "没有找到本地模型 " + MODEL_FILE_NAME + "（模型不随 APK 分发），已查找：" +
                modelCandidates(context).joinToString("、") { it.absolutePath }

        private fun modelCandidates(context: Context): List<File> {
            val internal = context.filesDir
            return listOf(File(File(internal, "models"), MODEL_FILE_NAME), File(internal, MODEL_FILE_NAME))
        }

        private fun describe(error: Throwable): String {
            val message = error.message
            val name = error.javaClass.simpleName
            return if (message.isNullOrBlank()) name else name + "：" + message
        }
    }
}
