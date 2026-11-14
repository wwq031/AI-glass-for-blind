package com.leqi.experiment.phonel

import org.junit.Assert.*
import org.junit.Test

class CxrlSpeechPlaybackTest {
    private class Fixture {
        val text = "第一段先播放给用户听。第二段继续说明回答的内容，第三段还应该保留更多文字，保证整段文字均被播报完成。"
        val synthesized = mutableListOf<String>()
        val synth = ArrayDeque<(SpeechAudio?) -> Unit>()
        val play = ArrayDeque<(Boolean) -> Unit>()
        val outcomes = mutableListOf<Boolean>()
        var played = 0
        val pipeline = CxrlSpeechPlayback({ part, done -> synthesized.add(part); synth.add(done) },
            { _, done -> played++; play.add(done) }, { outcomes.add(it) })
        fun ready() = synth.removeFirst()(SpeechAudio(floatArrayOf(0.1f), 16000))
    }

    @Test fun firstPhrasePlaysEarlyPrefetchIsOneAndSuccessWaitsForLastPlayback() {
        val f = Fixture(); f.pipeline.start(f.text)
        assertEquals(1, f.synthesized.size); assertEquals(0, f.played)
        f.ready()
        assertEquals(1, f.played); assertEquals(2, f.synthesized.size)
        f.ready()
        assertEquals(1, f.played); assertTrue(f.synth.isEmpty())
        assertTrue(f.outcomes.isEmpty())
        while (f.play.isNotEmpty()) {
            f.play.removeFirst()(true)
            if (f.synth.isNotEmpty()) f.ready()
        }
        assertEquals(f.text, f.synthesized.joinToString(""))
        assertEquals(f.synthesized.size, f.played)
        assertEquals(listOf(true), f.outcomes)
    }

    @Test fun cancelDiscardsPreparedAndLateAudioWithoutDoubleCompletion() {
        val f = Fixture(); f.pipeline.start(f.text); f.ready()
        f.pipeline.cancel(); f.ready(); f.play.removeFirst()(true); f.pipeline.cancel()
        assertEquals(1, f.played); assertEquals(2, f.synthesized.size)
        assertEquals(listOf(false), f.outcomes)
    }

    @Test fun synthesisAndDeviceFailureNeverReportWholeUtteranceSuccess() {
        for (synthesisFailure in listOf(true, false)) {
            val f = Fixture(); f.pipeline.start(f.text); f.ready()
            if (synthesisFailure) { f.synth.removeFirst()(null); f.play.removeFirst()(true) }
            else { f.play.removeFirst()(false); f.ready() }
            assertEquals(1, f.played); assertEquals(listOf(false), f.outcomes)
        }
    }
}
