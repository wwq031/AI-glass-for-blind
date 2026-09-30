package com.leqi.experiment

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Base64
import java.util.Locale

/** Bounded, in-memory photo chunks for the temporary Bluetooth experiment. */
class PhotoWire private constructor() {
    companion object {
        const val MAX_BYTES: Int = 4 * 1024 * 1024

        @JvmStatic
        fun chunks(source: ByteArray?, chunkBytes: Int): List<String> {
            if (source == null || source.isEmpty() || source.size > MAX_BYTES
                || chunkBytes < 1 || chunkBytes > 4000
            ) {
                throw IllegalArgumentException("Invalid photo chunking request")
            }
            val result = mutableListOf<String>()
            var offset = 0
            while (offset < source.size) {
                val end = minOf(source.size, offset + chunkBytes)
                result.add(
                    Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(source.copyOfRange(offset, end)),
                )
                offset += chunkBytes
            }
            return result
        }

        @JvmStatic
        fun sha256(source: ByteArray): String {
            val digest = try {
                MessageDigest.getInstance("SHA-256").digest(source)
            } catch (impossible: NoSuchAlgorithmException) {
                throw IllegalStateException(impossible)
            }
            val hex = StringBuilder(digest.size * 2)
            for (value in digest) hex.append(String.format(Locale.ROOT, "%02x", value.toInt() and 255))
            return hex.toString()
        }
    }

    class Collector(expectedLength: Int, expectedSha: String?) {
        private val expectedLength: Int
        private val expectedSha: String
        private val buffer = ByteArrayOutputStream()

        init {
            val sha = expectedSha
            if (expectedLength < 1 || expectedLength > PhotoWire.MAX_BYTES
                || sha == null || !sha.matches(Regex("[0-9a-f]{64}"))
            ) {
                throw IllegalArgumentException("Invalid photo header")
            }
            this.expectedLength = expectedLength
            this.expectedSha = sha
        }

        fun add(encoded: String) {
            val bytes = try {
                Base64.getUrlDecoder().decode(encoded)
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid photo chunk", error)
            }
            if (bytes.isEmpty() || buffer.size() + bytes.size > expectedLength) {
                throw IllegalArgumentException("Photo exceeds declared length")
            }
            buffer.write(bytes, 0, bytes.size)
        }

        fun finish(): ByteArray {
            val bytes = buffer.toByteArray()
            if (bytes.size != expectedLength || expectedSha != PhotoWire.sha256(bytes)) {
                throw IllegalArgumentException("Photo incomplete or corrupted")
            }
            return bytes
        }
    }
}
