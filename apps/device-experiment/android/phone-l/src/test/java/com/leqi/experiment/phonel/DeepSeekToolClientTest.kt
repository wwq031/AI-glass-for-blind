package com.leqi.experiment.phonel

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class DeepSeekToolClientTest {
    private class Response(private val bytes: ByteArray, private val status: Int = 200) :
        HttpURLConnection(URL("https://api.deepseek.com")) {
        val sent = ByteArrayOutputStream()
        override fun getOutputStream() = sent
        override fun getInputStream() = ByteArrayInputStream(bytes)
        override fun getResponseCode() = status
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun connect() = Unit
    }

    @Test fun recordsMultipartWavAndReadsTranscription() {
        val response = Response("""{"text":"你好","duration_ms":10}""".toByteArray())
        val client = DeepSeekToolClient("test-key") { assertEquals("asr", it); response }
        val raw = byteArrayOf(1, 2, 3, 4)
        assertEquals("你好", client.transcribe(raw) { false }.getString("text"))
        val sent = response.sent.toByteArray()
        val start = sent.toString(Charsets.ISO_8859_1).indexOf("RIFF")
        val wav = ByteBuffer.wrap(sent, start, 48).slice().order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(16000, wav.getInt(24))
        assertEquals(raw.size, wav.getInt(40))
        assertArrayEquals(raw, sent.copyOfRange(start + 44, start + 48))
    }

    @Test fun readsTtsAs24KhzPcmAndRejectsAnIncompleteSample() {
        val response = Response(byteArrayOf(0, 0, 0, 0x80.toByte()))
        val client = DeepSeekToolClient("test-key") { assertEquals("tts", it); response }
        val audio = client.synthesize("你好") { false }
        assertEquals(24000, audio.sampleRate)
        assertArrayEquals(floatArrayOf(0f, -1f), audio.samples, 0f)
        val broken = DeepSeekToolClient("test-key") { Response(byteArrayOf(1)) }
        val error = assertThrows(ModelServiceException::class.java) { broken.synthesize("你好") { false } }
        assertEquals("cloud_format", error.code)
    }

    @Test fun readsOcrAfterLeadingWhitespaceAndPreservesTruncation() {
        val response = Response("\n\n{\"pages\":[{\"finish_reason\":\"length\"}]}".toByteArray())
        val client = DeepSeekToolClient("test-key") { assertEquals("ocr", it); response }
        val result = client.recognize(byteArrayOf(1, 2)) { false }
        assertEquals("length", result.getJSONArray("pages").getJSONObject(0).getString("finish_reason"))
        assertTrue(response.sent.toString(Charsets.UTF_8.name()).contains("text_recognition"))
    }

    @Test fun cancellationStopsBeforeOpeningAConnection() {
        val client = DeepSeekToolClient("test-key") { error("cancelled request opened a connection") }
        val error = assertThrows(ModelServiceException::class.java) { client.synthesize("你好") { true } }
        assertEquals("cloud_cancelled", error.code)
    }
}
