package com.leqi.experiment.phonel

import java.util.UUID

/** A capture guard, not a second business Agent. SDK image callbacks carry no request ID. */
internal class PhotoGate {
    enum class Phase { ARMED, RECEIVING, DECODING, VALIDATED }
    data class Request(
        val sessionId: String, val id: String, val armedAt: Long,
        var phase: Phase = Phase.ARMED,
        var confirmedAt: Long? = null, var receivedAt: Long? = null, var validatedAt: Long? = null,
    )
    var current: Request? = null
        private set
    var ready = false
        private set
    private var sessionId: String? = null
    private var used = false
    val consumed: Boolean get() = used

    fun newSession(id: String) {
        require(id.isNotBlank() && id != sessionId)
        sessionId = id
        ready = true
        used = false
        current = null
    }

    fun arm(now: Long): Request? {
        if (!ready || used || current != null) return null
        return Request(sessionId!!, UUID.randomUUID().toString(), now).also { current = it }
    }

    fun confirm(id: String, now: Long): Request? {
        val request = current ?: return null
        if (!ready || used || request.id != id || request.phase != Phase.ARMED ||
            now < request.armedAt || now - request.armedAt >= TIMEOUT_MS) return null
        used = true // Never reuse this SDK Session, even if takePhoto fails or times out.
        request.confirmedAt = now
        request.phase = Phase.RECEIVING
        return request
    }

    fun receive(id: String, now: Long): Boolean {
        val request = current ?: return false
        val confirmed = request.confirmedAt ?: return false
        if (!ready || request.id != id || request.phase != Phase.RECEIVING ||
            now < confirmed || now - confirmed >= TIMEOUT_MS) return false
        request.receivedAt = now
        request.phase = Phase.DECODING
        return true
    }

    fun validated(id: String, now: Long): Boolean {
        val request = current ?: return false
        val confirmed = request.confirmedAt ?: return false
        if (!ready || request.id != id || request.phase != Phase.DECODING ||
            now < confirmed || now - confirmed >= TIMEOUT_MS) return false
        request.validatedAt = now
        request.phase = Phase.VALIDATED
        return true
    }

    fun cancel() { current = null }
    fun pause() { ready = false; cancel() }
    fun resume() { ready = sessionId != null }
    fun close() { ready = false; current = null }

    companion object { const val TIMEOUT_MS = 30_000L }
}
