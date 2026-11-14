package com.leqi.experiment.phonel

/** 先播首句，播放期间只预合成下一句；所有回调均由宿主投递到主线程。 */
internal class CxrlSpeechPlayback(
    private val synthesize: (String, (SpeechAudio?) -> Unit) -> Unit,
    private val play: (SpeechAudio, (Boolean) -> Unit) -> Unit,
    private val completed: (Boolean) -> Unit,
) {
    private var parts = emptyList<String>()
    private var next = 0
    private var pending: SpeechAudio? = null
    private var synthesizing = false
    private var playing = false
    private var finished = false

    fun start(text: String) {
        check(parts.isEmpty() && !finished)
        parts = SpeechChunks.split(text)
        advance()
    }

    fun cancel() = finish(false)

    private fun finish(ok: Boolean) {
        if (finished) return
        finished = true; pending = null
        completed(ok)
    }

    private fun advance() {
        if (finished) return
        val audio = pending
        if (!playing && audio != null) {
            pending = null; playing = true
            play(audio) { ok ->
                if (!finished) {
                    playing = false
                    if (ok) advance() else finish(false)
                }
            }
        }
        if (finished) return
        if (!synthesizing && pending == null && next < parts.size) {
            synthesizing = true
            val part = parts[next++]
            synthesize(part) { result ->
                if (!finished) {
                    synthesizing = false
                    if (result == null) finish(false)
                    else { pending = result; advance() }
                }
            }
        }
        if (!finished && !playing && !synthesizing && pending == null && next == parts.size) finish(true)
    }
}
