package com.leqi.experiment.phonel

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.leqi.experiment.phonebt.GemmaLocalBridge
import org.json.JSONObject
import java.util.concurrent.Executors

/** One device inference port, no planner or cross-turn conversation. Callbacks use main. */
internal interface PhotoObservation : AutoCloseable {
    val ready: Boolean
    fun observe(jpeg: ByteArray, result: (Result<String>) -> Unit)
    fun cancel()
}
internal interface TextResponder {
    val ready: Boolean
    fun answer(prompt: String, result: (Result<String>) -> Unit)
    fun cancelText()
}

internal class GemmaObservation(context: Context, private val report: (String) -> Unit) : PhotoObservation, TextResponder {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val worker = MODEL_WORKER
    private var engine: GemmaLocalBridge? = null // Owned by worker, including close.
    @Volatile private var loaded = false
    @Volatile private var busy = false
    @Volatile private var closed = false
    private var generation = 0L // Main thread only.
    private var textGeneration = 0L
    override val ready: Boolean get() = loaded && !busy && !closed

    init { load() }

    private fun status(text: String) { handler.post { if (!closed) report(text) } }
    private fun load() {
        worker.execute {
            try {
                status("正在准备内置 Gemma，首次启动需复制约 2GB 权重，请保持应用前台")
                var last = -10
                val file = BundledModel.prepare(app, { closed }) { percent ->
                    if (percent >= last + 10) { last = percent; status("内置模型校验/准备：$percent%") }
                }
                if (closed) return@execute
                status("正在加载 Gemma 4 E2B（GPU），尚未调用相机")
                val created = GemmaLocalBridge(file, java.io.File(app.cacheDir, "gemma-gpu"), gpu = true)
                engine = created
                created.initialize()
                loaded = true
                status("Gemma 已就绪；确认拍照后在本机识别图片")
            } catch (error: Throwable) {
                engine?.close(); engine = null; loaded = false
                status("Gemma 准备失败：${error.javaClass.simpleName}；检查剩余空间与 GPU 支持，重新打开应用重试")
            }
        }
    }

    override fun observe(jpeg: ByteArray, result: (Result<String>) -> Unit) {
        if (!ready) { result(Result.failure(IllegalStateException("Model is not ready"))); return }
        val attempt = ++generation
        busy = true
        worker.execute {
            val outcome = runCatching {
                check(!closed)
                val prompt = app.assets.open("gemma-vision-prompt.txt").bufferedReader().use { it.readText() }
                parseSummary(engine!!.generateVision(jpeg, prompt))
            }
            handler.post {
                busy = false
                if (!closed && generation == attempt) result(outcome)
                else if (!closed) report("旧模型任务已结束；未恢复旧拍照或发送旧结果")
            }
        }
    }

    override fun cancel() { ++generation }
    override fun answer(prompt: String, result: (Result<String>) -> Unit) {
        if (!ready) { result(Result.failure(IllegalStateException("Model is not ready"))); return }
        val attempt = ++textGeneration
        busy = true
        worker.execute {
            val outcome = runCatching {
                check(!closed)
                val raw = engine!!.generateText(prompt, "speech-$attempt")
                SpeechDialogue.parseAnswer(raw)
            }
            handler.post {
                busy = false
                if (!closed && textGeneration == attempt) result(outcome)
                else if (!closed) report("旧对话推理已结束；未发送或播报旧回答")
            }
        }
    }
    override fun cancelText() { ++textGeneration }
    override fun close() {
        if (closed) return
        closed = true; ++generation; ++textGeneration; loaded = false
        worker.execute { engine?.close(); engine = null }
        // The process worker also serializes Activity replacements against native close.
    }

    companion object {
        private val MODEL_WORKER = Executors.newSingleThreadExecutor()
        fun parseSummary(raw: String): String {
            val text = raw.trim()
            require(text.length in 1..4096 && text.startsWith("{") && text.endsWith("}"))
            val tokens = org.json.JSONTokener(text)
            val result = tokens.nextValue() as? JSONObject ?: error("Expected JSON object")
            require(tokens.nextClean() == '\u0000')
            require(result.length() == 1 && result.get("summary") is String)
            return result.getString("summary").trim().also {
                require(it.length in 1..160 && it.none { ch -> ch.isISOControl() })
            }
        }
    }
}
