package com.leqi.experiment.phonel

/** 整条回答只合成、播放一次。收到眼镜播放完成的回执才结束。 */
internal class CxrlSpeechPlayback(
    private val synthesize: (String, (SpeechAudio?) -> Unit) -> Unit,
    private val play: (SpeechAudio, (Boolean) -> Unit) -> Unit,
    private val completed: (Boolean) -> Unit,
) {
    private var finished = false

    fun start(text: String) {
        require(text.isNotBlank())
        synthesize(text) { audio ->
            if (!finished) {
                if (audio == null) finish(false) else play(audio, ::finish)
            }
        }
    }

    fun cancel() = finish(false)

    private fun finish(ok: Boolean) {
        if (finished) return
        finished = true
        completed(ok)
    }
}
