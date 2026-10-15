package com.leqi.experiment.phonel

import org.json.JSONArray
import org.json.JSONObject

/** Phone monotonic stage records only; no image, token, SDK error text or device IDs. */
internal class MvpRunLog(private val mode: String = "mock") {
    init { require(mode in setOf("mock", "gemma")) }
    private val stages = if (mode == "mock") STAGES else STAGES.map {
        when (it) { "mock_start" -> "inference_start"; "mock_end" -> "inference_end"; else -> it }
    }
    private val runs = ArrayDeque<JSONObject>()
    private var active: JSONObject? = null
    fun start(request: PhotoGate.Request, startKind: String, versions: JSONObject = JSONObject()) {
        require(startKind in setOf("cold", "warm", "unspecified"))
        val run = JSONObject().put("session_id", request.sessionId).put("request_id", request.id)
            .put("start_kind", startKind).put("outcome", "pending")
            .put("metadata", JSONObject(versions.toString()))
            .put("timestamps_ms", JSONObject().put("confirmed", request.confirmedAt!!))
        if (runs.size == 100) runs.removeFirst()
        runs.addLast(run)
        active = run
    }
    fun mark(id: String, stage: String, time: Long): Boolean {
        val run = active ?: return false
        if (run.getString("request_id") != id || stage !in stages || time < 0 ||
            run.getString("outcome") !in setOf("pending", "display_sent")) return false
        val stamps = run.getJSONObject("timestamps_ms")
        if (stamps.has(stage)) return false
        val index = stages.indexOf(stage)
        if (index == 0 || !stamps.has(stages[index - 1]) || time < stamps.getLong(stages[index - 1])) return false
        stamps.put(stage, time)
        if (stage == "display_call_end") run.put("outcome", "display_sent")
        if (stage == "human_visible") run.put("outcome", "human_confirmed")
        return true
    }
    fun photo(id: String, photo: PhotoMetadata) {
        val run = active ?: return
        if (run.getString("request_id") == id) run.put("image", JSONObject()
            .put("bytes", photo.bytes).put("width", photo.width).put("height", photo.height).put("mime", photo.mime))
    }
    fun finish(stage: String) {
        require(stage in setOf("consent", "capture", "decode", "mock", "inference", "display", "visibility", "lifecycle"))
        val run = active
        if (run != null && run.getString("outcome") != "human_confirmed") {
            run.put("outcome", "failed").put("failure_stage", stage)
        }
        active = null
    }
    fun export(): JSONObject = JSONObject().put("schema_version", "1.0").put("route", "CXR-L")
        .put("mode", mode).put("clock", "phone_elapsed_realtime_ms")
        .put("evidence_level", "unverified")
        .put("runs", JSONArray().also { array -> runs.forEach { array.put(JSONObject(it.toString())) } })

    companion object {
        val STAGES = listOf("confirmed", "image_callback", "image_validated", "mock_start", "mock_end",
            "display_call_start", "display_call_end", "human_visible")
    }
}
