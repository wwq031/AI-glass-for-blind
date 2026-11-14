package com.leqi.experiment.phonel

import org.json.JSONArray
import org.json.JSONObject
import android.util.Base64
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** 只返回计划文本；计划仍由仓库的合同校验和授权策略执行。 */
internal class DeepSeekClient(
    private val apiKey: String,
    private val model: String,
    private val connect: () -> HttpURLConnection = {
        URL("https://api.deepseek.com/chat/completions").openConnection() as HttpURLConnection
    },
    private val usage: (Int) -> Unit = {},
) {
    private val lock = Any()
    private var active: HttpURLConnection? = null

    fun generate(prompt: String, cancelled: () -> Boolean): String =
        complete(model, prompt, null, cancelled)

    fun observe(prompt: String, jpeg: ByteArray, cancelled: () -> Boolean): String =
        complete(AgentModelSettings.FLASH, prompt, jpeg, cancelled)

    private fun complete(selectedModel: String, prompt: String, jpeg: ByteArray?, cancelled: () -> Boolean): String {
        if (apiKey.isBlank()) throw ModelServiceException("cloud_key_missing")
        val connection = synchronized(lock) {
            if (cancelled()) throw ModelServiceException("cloud_cancelled")
            connect().also { check(active == null); active = it }
        }
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false // 凭据只发送给固定的官方地址。
            connection.connectTimeout = 15000
            connection.readTimeout = 45000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val content: Any = if (jpeg == null) prompt else JSONArray()
                .put(JSONObject().put("type", "text").put("text", prompt))
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject()
                    .put("url", "data:image/jpeg;base64,${Base64.encodeToString(jpeg, Base64.NO_WRAP)}")))
            val body = JSONObject().put("model", selectedModel).put("stream", false)
                .put("thinking", JSONObject().put("type", "disabled"))
                .put("max_tokens", 1536).put("response_format", JSONObject().put("type", "json_object"))
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content",
                        if (jpeg == null) "严格按用户提供的 Agent 合同输出 JSON。工具动作由眼镜应用执行，需要准备拍照时输出对应 tool_call。回答简短，通常一到三句。speak.priority 只能是 critical、high、normal、detail；普通对话用 normal。"
                        else "根据当前图片和观察要求输出 JSON。只报告图中可见的事实。"))
                    .put(JSONObject().put("role", "user").put("content", content)))
                .toString().toByteArray(Charsets.UTF_8)
            if (cancelled()) throw ModelServiceException("cloud_cancelled")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            if (status != 200) throw ModelServiceException(when (status) {
                401, 403 -> "cloud_auth"
                402 -> "cloud_balance"
                429 -> "cloud_busy"
                else -> "cloud_http"
            })
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (cancelled()) throw ModelServiceException("cloud_cancelled")
            val json = try { JSONObject(response) } catch (_: org.json.JSONException) {
                throw ModelServiceException("cloud_format")
            }
            json.optJSONObject("usage")?.optInt("total_tokens", -1)?.takeIf { it >= 0 }?.let(usage)
            val choice = json.optJSONArray("choices")?.optJSONObject(0)
            if (choice?.optString("finish_reason") != "stop") throw ModelServiceException("cloud_format")
            val answer = choice.optJSONObject("message")?.opt("content") as? String
            return answer?.takeIf { it.isNotBlank() } ?: throw ModelServiceException("cloud_format")
        } catch (_: SocketTimeoutException) {
            throw ModelServiceException("cloud_timeout")
        } catch (_: IOException) {
            throw ModelServiceException(if (cancelled()) "cloud_cancelled" else "cloud_network")
        } finally {
            synchronized(lock) { if (active === connection) active = null }
            connection.disconnect()
        }
    }

    fun cancel() { synchronized(lock) { active?.disconnect() } }
}

/** 只跨桥传递固定错误代号，不记录服务器正文、请求或凭据。 */
internal class ModelServiceException(val code: String) : Exception(code)
