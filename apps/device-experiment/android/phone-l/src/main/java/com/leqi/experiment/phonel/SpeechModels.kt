package com.leqi.experiment.phonel

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import com.k2fsa.sherpa.onnx.*

internal data class SpeechAudio(val samples: FloatArray, val sampleRate: Int) {
    fun validate() {
        require(sampleRate in 8000..48000 && samples.isNotEmpty() && samples.size <= sampleRate * 90)
        require(samples.all { it.isFinite() && it in -1f..1f })
        require(samples.any { kotlin.math.abs(it) > 0.00001f }) { "Synthesized audio is silent" }
    }
}

internal interface SpeechEngines : AutoCloseable {
    val ttsThreads: Int get() = 2
    fun initialize()
    fun transcribe(samples: FloatArray): String
    fun synthesize(text: String): SpeechAudio
}

internal object SpeechAssets {
    fun prepare(context: Context, cancelled: () -> Boolean): File {
        val manifest = context.assets.open("speech/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        val root = File(context.filesDir, "models/speech")
        val files = manifest.getJSONArray("files")
        require(files.length() in 1..64)
        for (index in 0 until files.length()) {
            check(!cancelled())
            val item = files.getJSONObject(index)
            val name = item.getString("name")
            require(name.matches(Regex("(sensevoice|melo)/[A-Za-z0-9_./-]+")) && name.split('/').none { it == ".." || it.isEmpty() })
            val bytes = item.getLong("bytes")
            val expected = item.getString("sha256")
            require(bytes in 1..300_000_000L && expected.matches(Regex("[0-9a-f]{64}")))
            val target = File(root, name)
            fun hash(file: File): String = file.inputStream().use { source ->
                val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(1024 * 1024)
                while (true) {
                    check(!cancelled()); val count = source.read(buffer); if (count < 0) break
                    digest.update(buffer, 0, count)
                }
                digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            }
            if (target.isFile && target.length() == bytes && hash(target) == expected) continue
            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
            check(root.usableSpace > bytes + 128L * 1024 * 1024) { "Insufficient speech storage" }
            val temp = File.createTempFile("speech-", ".partial", target.parentFile)
            try {
                context.assets.open("speech/files/$name").use { source -> temp.outputStream().use { output ->
                    val buffer = ByteArray(1024 * 1024); var total = 0L
                    while (true) {
                        check(!cancelled()); val count = source.read(buffer); if (count < 0) break
                        total += count; check(total <= bytes); output.write(buffer, 0, count)
                    }
                    output.fd.sync(); check(total == bytes)
                } }
                check(hash(temp) == expected)
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { temp.delete() }
        }
        return root
    }
}

/** CPU ASR and TTS only. No network, no recognition text in logs, fresh ASR stream. */
internal class SherpaSpeechEngines(private val root: File,
    override val ttsThreads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)) : SpeechEngines {
    private var recognizer: OfflineRecognizer? = null
    private var tts: OfflineTts? = null
    override fun initialize() {
        require(ttsThreads in 1..4)
        check(recognizer == null && tts == null)
        try {
            recognizer = OfflineRecognizer(null, OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(model = File(root, "sensevoice/model.int8.onnx").absolutePath,
                    language = "auto", useInverseTextNormalization = true),
                tokens = File(root, "sensevoice/tokens.txt").absolutePath, numThreads = 2, provider = "cpu", debug = false)))
            val model = File(root, "melo")
            tts = OfflineTts(null, OfflineTtsConfig(model = OfflineTtsModelConfig(vits = OfflineTtsVitsModelConfig(
                model = File(model, "model.int8.onnx").absolutePath,
                lexicon = File(model, "lexicon.txt").absolutePath, tokens = File(model, "tokens.txt").absolutePath,
                dictDir = File(model, "dict").absolutePath), numThreads = ttsThreads, provider = "cpu", debug = false),
                ruleFsts = listOf("date.fst", "number.fst", "phone.fst").joinToString(",") { File(model, it).absolutePath },
                maxNumSentences = 1))
            check(tts!!.sampleRate() in 8000..48000 && tts!!.numSpeakers() > 0)
        } catch (error: Throwable) { close(); throw error }
    }
    override fun transcribe(samples: FloatArray): String {
        require(samples.size in 3200..480000 && samples.all { it.isFinite() && it in -1f..1f })
        check(samples.any { kotlin.math.abs(it) > 0.002f }) { "No audible input" }
        val active = recognizer ?: error("ASR not initialized")
        val stream = active.createStream()
        return try {
            stream.acceptWaveform(samples, 16000); active.decode(stream)
            active.getResult(stream).text.trim().also { require(it.length in 1..500) }
        } finally { stream.release() }
    }
    override fun synthesize(text: String): SpeechAudio {
        require(text.isNotBlank() && text.length <= 500)
        val active = tts ?: error("TTS not initialized")
        val pieces = text.chunked(140).map { part -> active.generate(part, 0, 1f).let {
            SpeechAudio(it.samples, it.sampleRate).also { value -> value.validate() }
        } }
        val rate = pieces.first().sampleRate
        require(pieces.all { it.sampleRate == rate })
        val total = pieces.sumOf { it.samples.size }
        require(total <= rate * 90)
        val combined = FloatArray(total); var offset = 0
        pieces.forEach { it.samples.copyInto(combined, offset); offset += it.samples.size }
        return SpeechAudio(combined, rate).also { it.validate() }
    }
    override fun close() {
        val oldAsr = recognizer; recognizer = null
        val oldTts = tts; tts = null
        runCatching { oldAsr?.release() }; runCatching { oldTts?.release() }
    }
}
