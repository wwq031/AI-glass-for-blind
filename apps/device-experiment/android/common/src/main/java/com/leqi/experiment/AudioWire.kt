package com.leqi.experiment

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Wrap little-endian mono PCM samples as an uncompressed WAV message. */
class AudioWire private constructor() {
    companion object {
        @JvmStatic
        fun pcm16MonoWav(pcm: ByteArray?, sampleRate: Int): ByteArray {
            if (pcm == null || pcm.isEmpty() || pcm.size > PhotoWire.MAX_BYTES
                || pcm.size % 2 != 0 || sampleRate < 8000 || sampleRate > 48000
            ) {
                throw IllegalArgumentException("Invalid PCM recording")
            }
            val wav = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
            wav.put(byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte()))
            wav.putInt(36 + pcm.size)
            wav.put(byteArrayOf(
                'W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte(),
                'f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte(),
            ))
            wav.putInt(16)
            wav.putShort(1)
            wav.putShort(1)
            wav.putInt(sampleRate)
            wav.putInt(sampleRate * 2)
            wav.putShort(2)
            wav.putShort(16)
            wav.put(byteArrayOf('d'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte()))
            wav.putInt(pcm.size)
            wav.put(pcm)
            return wav.array()
        }
    }
}
