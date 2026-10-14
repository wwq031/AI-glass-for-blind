package com.leqi.experiment.phonel

import org.json.JSONArray
import org.json.JSONObject

/** Deterministic substitute at the observation seam; never fabricates scene facts. */
internal object MockObservation {
    fun create(request: PhotoGate.Request, photo: PhotoMetadata): JSONObject {
        require(request.phase == PhotoGate.Phase.VALIDATED && request.validatedAt != null)
        require(photo.bytes > 0 && PhotoDecoder.validBounds(photo.width, photo.height))
        require(photo.mime in setOf("image/webp", "image/jpeg"))
        return JSONObject()
            .put("schema_version", "1.0")
            .put("session_id", request.sessionId)
            .put("request_id", request.id)
            .put("capability_id", "vision.scene")
            .put("status", "partial")
            .put("confidence", "unknown")
            .put("needs_retake", false)
            .put("summary", "Mock：图片已收到 ${photo.width}×${photo.height} #${request.id.take(8)}")
            .put("facts", JSONArray())
            .put("limitations", JSONArray().put("通信调试结果，未进行视觉识别或安全判断"))
    }

    fun viewUpdate(text: String): String = JSONArray().put(JSONObject()
        .put("action", "update").put("id", "title_text")
        .put("props", JSONObject().put("text", text))).toString()
}
