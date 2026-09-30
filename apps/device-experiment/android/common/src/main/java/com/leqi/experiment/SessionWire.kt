package com.leqi.experiment

import java.nio.charset.StandardCharsets
import java.util.Base64

/** Small, versioned CXR message envelope for the device experiment. */
class SessionWire private constructor() {
    companion object {
        @JvmStatic
        fun encode(sessionId: String?, sequence: Long, type: String?, payload: String?): String {
            if (sessionId == null || !sessionId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) {
                throw IllegalArgumentException("Invalid session ID")
            }
            if (sequence <= 0) throw IllegalArgumentException("Invalid sequence")
            if (type == null || !type.matches(Regex("[a-z][a-z0-9_.-]{0,63}"))) {
                throw IllegalArgumentException("Invalid event type")
            }
            if (payload == null || payload.length > 8192) {
                throw IllegalArgumentException("Invalid payload")
            }
            return "v1|" + sessionId + "|" + sequence + "|" + type + "|" + Base64.getUrlEncoder()
                .withoutPadding().encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
        }

        @JvmStatic
        fun decode(encoded: String?): Event {
            if (encoded == null || encoded.length > 12000) throw IllegalArgumentException("Invalid message size")
            val parts = encoded.split("|")
            if (parts.size != 5 || parts[0] != "v1") throw IllegalArgumentException("Invalid wire format")
            return try {
                val sequence = parts[2].toLong()
                val payload = String(Base64.getUrlDecoder().decode(parts[4]), StandardCharsets.UTF_8)
                encode(parts[1], sequence, parts[3], payload)
                Event.of(parts[1], sequence, parts[3], payload)
            } catch (error: NumberFormatException) {
                throw IllegalArgumentException("Invalid sequence", error)
            }
        }
    }

    class Event private constructor(
        @JvmField val sessionId: String,
        @JvmField val sequence: Long,
        @JvmField val type: String,
        @JvmField val payload: String,
    ) {
        internal companion object {
            fun of(sessionId: String, sequence: Long, type: String, payload: String): Event =
                Event(sessionId, sequence, type, payload)
        }
    }
}
