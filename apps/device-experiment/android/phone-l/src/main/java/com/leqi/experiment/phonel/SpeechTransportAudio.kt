package com.leqi.experiment.phonel

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 对 24 kHz 和 44.1 kHz 语音先低通再二倍抽取，减少一半传输量且保持时长与音调。 */
internal object SpeechTransportAudio {
    private const val HALF = 31
    private val filter = DoubleArray(HALF * 2 + 1) { index ->
        val offset = index - HALF
        // 截止频率为输入采样率的 0.22 倍的窗化 sinc；在新的 Nyquist 频率之前留出过渡带，抑制混叠。
        val cutoff = 0.22
        val sinc = if (offset == 0) 2 * cutoff else sin(2 * PI * cutoff * offset) / (PI * offset)
        sinc * (0.54 - 0.46 * cos(2 * PI * index / (HALF * 2)))
    }.also { coefficients ->
        val gain = coefficients.sum()
        coefficients.indices.forEach { coefficients[it] /= gain }
    }

    fun prepare(audio: SpeechAudio): SpeechAudio {
        if (audio.sampleRate != 44100 && audio.sampleRate != 24000) return audio
        val source = audio.samples
        val result = FloatArray((source.size + 1) / 2) { index ->
            val center = index * 2
            var value = 0.0
            for (tap in filter.indices) {
                val input = center + tap - HALF
                if (input in source.indices) value += source[input] * filter[tap]
            }
            value.toFloat().coerceIn(-1f, 1f)
        }
        return SpeechAudio(result, audio.sampleRate / 2)
    }
}
