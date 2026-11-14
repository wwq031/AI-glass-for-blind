package com.leqi.experiment.phonel

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import org.json.JSONObject

internal class DeepSeekToolClient(
    private val key: String,
    private val connect: (String) -> HttpURLConnection = {
        URL("https://api.deepseek.com/v1/tools/$it").openConnection() as HttpURLConnection
    },
) {
    private val active = mutableSetOf<HttpURLConnection>()

    fun transcribe(pcm: ByteArray, cancelled: () -> Boolean): JSONObject =
        json(multipart("asr", "speech.wav", "audio/wav", pcmWave(pcm), null, cancelled))

    fun recognize(jpeg: ByteArray, cancelled: () -> Boolean): JSONObject =
        json(multipart("ocr", "photo.jpg", "image/jpeg", jpeg, "text_recognition", cancelled))

    fun synthesize(text: String, cancelled: () -> Boolean): SpeechAudio {
        val body = JSONObject().put("text", text).put("format", "pcm").toString().toByteArray()
        val pcm = request("tts", "application/json", body, 2_880_000, cancelled)
        if (pcm.isEmpty() || pcm.size % 2 != 0) throw ModelServiceException("cloud_format")
        val samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return SpeechAudio(FloatArray(samples.remaining()) { samples.get() / 32768f }, 24000)
    }

    fun cancel() = synchronized(active) { active.forEach { it.disconnect() } }

    private fun multipart(tool: String, name: String, type: String, bytes: ByteArray,
        purpose: String?, cancelled: () -> Boolean): ByteArray {
        val boundary = "leqi-${UUID.randomUUID()}"
        val body = ByteArrayOutputStream()
        if (purpose != null) {
            body.write("--$boundary\r\nContent-Disposition: form-data; name=\"purpose\"\r\n\r\n$purpose\r\n".toByteArray())
        }
        body.write(("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\n" +
            "Content-Type: $type\r\n\r\n").toByteArray())
        body.write(bytes)
        body.write("\r\n--$boundary--\r\n".toByteArray())
        return request(tool, "multipart/form-data; boundary=$boundary", body.toByteArray(), 1_048_576, cancelled)
    }

    private fun request(tool: String, contentType: String, body: ByteArray, limit: Int,
        cancelled: () -> Boolean): ByteArray {
        val connection = synchronized(active) {
            if (cancelled()) throw ModelServiceException("cloud_cancelled")
            connect(tool).also { active.add(it) }
        }
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 45_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.setRequestProperty("Content-Type", contentType)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            if (status != 200) throw ModelServiceException(when (status) {
                401, 403 -> "cloud_auth"
                402 -> "cloud_balance"
                429, 503 -> "cloud_busy"
                408, 504 -> "cloud_timeout"
                else -> "cloud_http"
            })
            val result = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val chunk = ByteArray(8192)
                while (true) {
                    if (cancelled()) throw ModelServiceException("cloud_cancelled")
                    val count = input.read(chunk)
                    if (count < 0) break
                    if (result.size() + count > limit) throw ModelServiceException("cloud_format")
                    result.write(chunk, 0, count)
                }
            }
            if (cancelled()) throw ModelServiceException("cloud_cancelled")
            val expectedLength = connection.getHeaderField("Content-Length")?.toIntOrNull()
            if (expectedLength != null && expectedLength != result.size()) throw ModelServiceException("cloud_format")
            return result.toByteArray()
        } catch (_: SocketTimeoutException) {
            throw ModelServiceException(if (cancelled()) "cloud_cancelled" else "cloud_timeout")
        } catch (_: IOException) {
            throw ModelServiceException(if (cancelled()) "cloud_cancelled" else "cloud_network")
        } finally {
            synchronized(active) { active.remove(connection) }
            connection.disconnect()
        }
    }

    private fun json(bytes: ByteArray): JSONObject = try {
        JSONObject(bytes.toString(Charsets.UTF_8))
    } catch (_: org.json.JSONException) {
        throw ModelServiceException("cloud_format")
    }

    companion object {
        fun pcmWave(pcm: ByteArray): ByteArray {
            require(pcm.isNotEmpty() && pcm.size % 2 == 0)
            return ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000)
                .putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size).put(pcm).array()
        }
    }
}
