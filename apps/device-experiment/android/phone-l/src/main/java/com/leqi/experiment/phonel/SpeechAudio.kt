package com.leqi.experiment.phonel

internal data class SpeechAudio(val samples: FloatArray, val sampleRate: Int) {
    fun validate() {
        require(sampleRate in 8000..48000 && samples.isNotEmpty() && samples.size <= sampleRate * 90)
        require(samples.all { it.isFinite() && it in -1f..1f })
        require(samples.any { kotlin.math.abs(it) > 0.00001f }) { "Synthesized audio is silent" }
    }
}
