package com.leqi.experiment.phonel

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class DeepSeekClientTest {
    private class Connection(var status: Int = 200, var response: String =
        """{"choices":[{"finish_reason":"stop","message":{"content":"{\"goal\":\"回答\",\"actions\":[]}"}}],"usage":{"total_tokens":42}}"""
    ) : HttpURLConnection(URL("https://api.deepseek.com/chat/completions")) {
        val sent = ByteArrayOutputStream()
        var disconnects = 0
        var beforeResponse: () -> Unit = {}
        override fun connect() {}
        override fun disconnect() { disconnects++ }
        override fun usingProxy() = false
        override fun getOutputStream() = sent
        override fun getResponseCode(): Int { beforeResponse(); return status }
        override fun getInputStream() = response.byteInputStream()
    }

    @Test fun requestUsesOfficialJsonModeAndBoundedOutputWithUsage() {
        val connection = Connection()
        var tokens = 0
        val client = DeepSeekClient("test-credential", AgentModelSettings.FLASH, { connection }, { tokens += it })
        val prompt = "合同与会话历史"
        assertTrue(client.generate(prompt) { false }.contains("goal"))
        val body = JSONObject(connection.sent.toString("UTF-8"))
        assertEquals("deepseek-flash", body.getString("model"))
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
        assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
        assertEquals(1536, body.getInt("max_tokens"))
        assertEquals(prompt, body.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals("Bearer test-credential", connection.getRequestProperty("Authorization"))
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(body.toString().contains("test-credential"))
        assertEquals(42, tokens); assertEquals(1, connection.disconnects)
    }

    @Test fun errorsNeverRetryOrExposeRemoteBody() {
        for ((status, code) in mapOf(401 to "cloud_auth", 403 to "cloud_auth", 402 to "cloud_balance",
            429 to "cloud_busy", 503 to "cloud_http", 302 to "cloud_http")) {
            val connection = Connection(status, "private remote body")
            var opened = 0
            val client = DeepSeekClient("test", AgentModelSettings.FLASH, { opened++; connection })
            assertEquals(code, assertThrows(ModelServiceException::class.java) { client.generate("prompt") { false } }.code)
            assertEquals(1, opened); assertEquals(1, connection.disconnects)
        }
    }

    @Test fun truncatedBlankAndInvalidEnvelopesCannotBecomePlans() {
        for (body in listOf("not json", "{}", """{"choices":[{"finish_reason":"length","message":{"content":"{}"}}]}""",
            """{"choices":[{"finish_reason":"stop","message":{"content":"   "}}]}""",
            """{"choices":[{"finish_reason":"stop","message":{"content":null}}]}""")) {
            val client = DeepSeekClient("test", AgentModelSettings.FLASH, { Connection(response = body) })
            assertEquals("cloud_format", assertThrows(ModelServiceException::class.java) { client.generate("prompt") { false } }.code)
        }
    }

    @Test fun cancellationBeforeRequestSendsNothingAndLateResponseIsDiscarded() {
        val connection = Connection()
        var cancelled = true
        var opened = 0
        val client = DeepSeekClient("test", AgentModelSettings.FLASH, { opened++; connection })
        assertThrows(ModelServiceException::class.java) { client.generate("prompt") { cancelled } }
        assertEquals(0, opened)
        cancelled = false
        connection.beforeResponse = { cancelled = true; client.cancel() }
        assertEquals("cloud_cancelled", assertThrows(ModelServiceException::class.java) {
            client.generate("prompt") { cancelled }
        }.code)
        assertEquals(2, connection.disconnects)
    }

    @Test fun timeoutAndMissingKeyHaveSpecificErrors() {
        val connection = Connection().apply { beforeResponse = { throw SocketTimeoutException("private detail") } }
        assertEquals("cloud_timeout", assertThrows(ModelServiceException::class.java) {
            DeepSeekClient("test", AgentModelSettings.FLASH, { connection }).generate("prompt") { false }
        }.code)
        assertEquals("cloud_key_missing", assertThrows(ModelServiceException::class.java) {
            DeepSeekClient("", AgentModelSettings.FLASH, { error("must not connect") }).generate("prompt") { false }
        }.code)
    }
}
