package com.leqi.experiment.phonebt

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.leqi.experiment.AudioWire
import java.io.File

/** 离线语音合成失败。桥接层只抛这一种异常，绝不返回静音或占位音频。 */
class TtsBridgeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 手机端中文离线 TTS（sherpa-onnx 1.13.8 + vits-melo-tts-zh_en int8）。
 *
 * 只做一件事：把一段中文文本合成成 16 位单声道 WAV，交给既有的 `play.*` 消息发给眼镜。
 * 所有方法都会阻塞，必须在后台线程调用；内部串行化，同一时刻只跑一次合成。
 *
 * 模型文件放在 `getExternalFilesDir(null)/models/tts/`：
 * `model.int8.onnx`、`lexicon.txt`、`tokens.txt` 三个是必需的。
 * 如果同目录下还放了 `dict/`（jieba 词典）和 `*-zh.fst`（数字、日期、电话归一化规则），
 * 会自动带上；没有也能跑，只是中文分词和数字读法会退化。
 */
class OfflineTtsBridge @JvmOverloads constructor(
    private val context: Context,
    private val modelDir: File = resolveModelDir(context),
    private val numThreads: Int = defaultThreads(),
) : AutoCloseable {

    private val lock = Any()
    private var tts: OfflineTts? = null

    /** 实际使用的模型目录绝对路径，用于诊断显示。 */
    val modelPath: String get() = modelDir.absolutePath

    fun isReady(): Boolean = synchronized(lock) { tts != null }

    /** 加载模型。幂等；失败会抛出带原因的异常。 */
    @Throws(TtsBridgeException::class)
    fun initialize() {
        synchronized(lock) {
            if (tts != null) return
            val model = requireFile(MODEL_FILE)
            val lexicon = requireFile(LEXICON_FILE)
            val tokens = requireFile(TOKENS_FILE)
            val dictDir = File(modelDir, DICT_DIR_NAME).takeIf { it.isDirectory }
            val ruleFsts = RULE_FST_NAMES
                .map { File(modelDir, it) }
                .filter { it.isFile && it.length() > 0L }
                .joinToString(separator = ",") { it.absolutePath }

            val vits = OfflineTtsVitsModelConfig(
                model = model.absolutePath,
                lexicon = lexicon.absolutePath,
                tokens = tokens.absolutePath,
                dictDir = dictDir?.absolutePath ?: "",
                noiseScale = 0.667f,
                noiseScaleW = 0.8f,
                lengthScale = 1.0f,
            )
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = vits,
                    numThreads = numThreads,
                    debug = false,
                    provider = "cpu",
                ),
                ruleFsts = ruleFsts,
                ruleFars = "",
                maxNumSentences = 1,
                silenceScale = 0.2f,
            )
            // assetManager 必须为 null：模型走文件系统绝对路径，传 context.assets 会让
            // 原生层改用 AAssetManager 读取而失败，且失败时原生直接 exit()，抛出的异常抓不住。
            val created = try {
                OfflineTts(null, config)
            } catch (error: Throwable) {
                throw TtsBridgeException("离线语音模型加载失败：" + describe(error), error)
            }
            if (created.sampleRate() <= 0 || created.numSpeakers() <= 0) {
                runCatching { created.release() }
                throw TtsBridgeException(
                    "离线语音模型没有正常初始化（采样率=" + created.sampleRate() +
                        "，发音人=" + created.numSpeakers() + "），请检查模型文件是否完整",
                )
            }
            tts = created
            Log.i(TAG, "离线语音已就绪：$modelPath 采样率=${created.sampleRate()} 线程=$numThreads")
        }
    }

    /**
     * 合成一段文本为 WAV。
     *
     * 文本超过 [MAX_TEXT_CHARS] 直接报错：导航播报必须短，长文本应该由调用方拆成多段，
     * 而不是合成一大段音频后在内存里搬运。
     */
    @Throws(TtsBridgeException::class)
    fun synthesizeToWav(text: String): ByteArray {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) throw TtsBridgeException("要播报的文本是空的")
        if (trimmed.length > MAX_TEXT_CHARS) {
            throw TtsBridgeException("要播报的文本超过 $MAX_TEXT_CHARS 字，请先拆成多段：" + trimmed.length)
        }
        initialize()
        val active = synchronized(lock) { tts } ?: throw TtsBridgeException("离线语音引擎未就绪")
        val audio = synchronized(lock) {
            try {
                active.generate(trimmed, SPEAKER_ID, SPEED)
            } catch (error: Throwable) {
                throw TtsBridgeException("离线语音合成失败：" + describe(error), error)
            }
        }
        val samples = audio.samples
        if (samples.isEmpty()) throw TtsBridgeException("离线语音合成没有返回音频")
        val sampleRate = audio.sampleRate
        if (sampleRate < 8000 || sampleRate > 48000) {
            throw TtsBridgeException("离线语音采样率不可用：$sampleRate")
        }
        // 比的是最终 WAV 的长度（PCM 加 44 字节头），和播报通道那一侧的口径保持一致，
        // 否则刚好卡在边界上的音频会出现“这边通过、那边拒发”。
        val wavBytes = samples.size * 2 + WAV_HEADER_BYTES
        if (wavBytes > MAX_PLAY_BYTES) {
            throw TtsBridgeException("合成音频过长（" + wavBytes + " 字节），拒绝一次性发给眼镜")
        }
        val pcm = ByteArray(samples.size * 2)
        for (index in samples.indices) {
            val value = (samples[index] * 32767f).toInt().coerceIn(-32768, 32767)
            pcm[2 * index] = (value and 0xFF).toByte()
            pcm[2 * index + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return try {
            AudioWire.pcm16MonoWav(pcm, sampleRate)
        } catch (error: IllegalArgumentException) {
            throw TtsBridgeException("合成音频无法封装成 WAV：" + error.message, error)
        }
    }

    override fun close() {
        val active = synchronized(lock) {
            val current = tts
            tts = null
            current
        }
        if (active != null) runCatching { active.release() }
    }

    @Throws(TtsBridgeException::class)
    private fun requireFile(name: String): File {
        val file = File(modelDir, name)
        if (!file.isFile) {
            throw TtsBridgeException("离线语音模型缺少文件：$name（目录 " + modelDir.absolutePath + "）")
        }
        if (file.length() <= 0L) {
            throw TtsBridgeException("离线语音模型文件是空的：$name（目录 " + modelDir.absolutePath + "）")
        }
        return file
    }

    companion object {
        private const val TAG = "LeqiTts"
        const val DIR_NAME = "tts"
        const val MODEL_FILE = "model.int8.onnx"
        const val LEXICON_FILE = "lexicon.txt"
        const val TOKENS_FILE = "tokens.txt"
        private const val DICT_DIR_NAME = "dict"
        private val RULE_FST_NAMES = listOf("date-zh.fst", "number-zh.fst", "phone-zh.fst")

        /** 单次合成的文本上限；超过就由调用方拆段。 */
        const val MAX_TEXT_CHARS = 140

        private const val SPEAKER_ID = 0
        private const val SPEED = 1.0f

        /** AudioWire 生成的规范 WAV 头长度。 */
        private const val WAV_HEADER_BYTES = 44

        /** 单条播报音频上限（PCM 加 44 字节头），写入侧和眼镜端解析都按这个口径判定。 */
        private const val MAX_PLAY_BYTES = 3 * 1024 * 1024

        private fun defaultThreads(): Int =
            Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

        /** 外部私有目录优先，其次内部私有目录；找不到时返回外部路径，让 initialize 报明确错误。 */
        @JvmStatic
        fun resolveModelDir(context: Context): File {
            val external = context.getExternalFilesDir(null)
            if (external != null) {
                val candidate = File(File(external, "models"), DIR_NAME)
                if (File(candidate, MODEL_FILE).isFile) return candidate
            }
            val internal = File(File(context.filesDir, "models"), DIR_NAME)
            if (File(internal, MODEL_FILE).isFile) return internal
            return if (external != null) File(File(external, "models"), DIR_NAME) else internal
        }

        /** 必需的三个模型文件都在的目录；缺任何一个都返回 null。启动自检用它，不抛异常。 */
        @JvmStatic
        fun findModelDir(context: Context): File? {
            val dir = resolveModelDir(context)
            return if (missingFilesIn(dir).isEmpty()) dir else null
        }

        /** 离线语音目录里缺哪些必需文件；缺失按文件名报，方便照着补文件。 */
        @JvmStatic
        fun missingModelFiles(context: Context): List<String> = missingFilesIn(resolveModelDir(context))

        private fun missingFilesIn(dir: File): List<String> =
            REQUIRED_FILES.filter { file -> !File(dir, file).isFile || File(dir, file).length() <= 0L }

        /** 必需文件清单，顺序与报错顺序一致。 */
        private val REQUIRED_FILES = listOf(MODEL_FILE, LEXICON_FILE, TOKENS_FILE)

        private fun describe(error: Throwable): String {
            val message = error.message
            val name = error.javaClass.simpleName
            return if (message.isNullOrBlank()) name else name + "：" + message
        }
    }
}
