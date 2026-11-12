package com.leqi.experiment.phonel

/** 仅由语音 worker 使用；同一模型的已合成短句只留在内存，服务结束时清空。 */
internal class SpeechAudioCache(private val maxSamples: Int = 44100 * 30) {
    private val entries = LinkedHashMap<String, SpeechAudio>(16, 0.75f, true)
    private var sampleCount = 0

    fun getOrSynthesize(text: String, synthesize: () -> SpeechAudio): SpeechAudio {
        entries[text]?.let { return it }
        val audio = synthesize()
        if (audio.samples.size > maxSamples) return audio
        while (sampleCount + audio.samples.size > maxSamples) {
            val oldest = entries.entries.iterator()
            sampleCount -= oldest.next().value.samples.size
            oldest.remove()
        }
        entries[text] = audio
        sampleCount += audio.samples.size
        return audio
    }

    fun clear() { entries.clear(); sampleCount = 0 }
}
