package com.leqi.experiment.phonel

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class SpeechTransportAudioTest {
    private fun tone(hertz: Int, size: Int = 44100) = SpeechAudio(
        FloatArray(size) { (0.4 * sin(2 * PI * hertz * it / 44100)).toFloat() }, 44100)
    private fun rms(samples: FloatArray): Double =
        sqrt((100 until samples.size - 100).sumOf { samples[it].toDouble() * samples[it] } / (samples.size - 200))

    @Test fun halvesPayloadWhilePreservingDurationPitchAndSpeechBand() {
        for (frequency in listOf(300, 1000, 4000, 8000)) {
            val original = tone(frequency)
            val audio = SpeechTransportAudio.prepare(original)
            assertEquals(22050, audio.sampleRate)
            assertEquals(original.samples.size / 2, audio.samples.size)
            assertEquals(1.0, audio.samples.size.toDouble() / audio.sampleRate, 0.00005)
            val error = (100 until audio.samples.size - 100).maxOf {
                abs(audio.samples[it] - 0.4 * sin(2 * PI * frequency * it / audio.sampleRate))
            }
            assertTrue("frequency=$frequency error=$error", error < 0.005)
            audio.validate()
        }
    }

    @Test fun suppressesHighFrequencyAliasingInsteadOfSimplyDroppingSamples() {
        val original = tone(15000)
        val converted = SpeechTransportAudio.prepare(original)
        assertTrue(rms(converted.samples) < rms(original.samples) * 0.01)
    }

    @Test fun oddLengthKeepsTailAndImpulseTimingWithoutMutatingInput() {
        val source = FloatArray(44101)
        source[22050] = 0.5f
        val original = source.copyOf()
        val result = SpeechTransportAudio.prepare(SpeechAudio(source, 44100))
        assertEquals(22051, result.samples.size)
        assertEquals(11025, result.samples.indices.maxBy { result.samples[it] })
        assertArrayEquals(original, source, 0f)
    }

    @Test fun existingLowerRateAudioPassesThroughAndPeaksStayInPcmRange() {
        val lowerRate = SpeechAudio(floatArrayOf(0.2f), 16000)
        assertSame(lowerRate, SpeechTransportAudio.prepare(lowerRate))
        val square = SpeechAudio(FloatArray(1000) { if (it % 50 < 25) 1f else -1f }, 44100)
        val converted = SpeechTransportAudio.prepare(square)
        assertTrue(converted.samples.all { it.isFinite() && it in -1f..1f })
    }
}
