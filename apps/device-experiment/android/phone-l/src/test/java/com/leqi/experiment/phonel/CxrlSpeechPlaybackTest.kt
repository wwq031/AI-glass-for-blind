package com.leqi.experiment.phonel

import org.junit.Assert.*
import org.junit.Test

class CxrlSpeechPlaybackTest {
    private class Fixture {
        val requests = mutableListOf<String>()
        val played = mutableListOf<SpeechAudio>()
        val results = mutableListOf<Boolean>()
        lateinit var synthesized: (SpeechAudio?) -> Unit
        lateinit var playbackDone: (Boolean) -> Unit
        val pipeline = CxrlSpeechPlayback(
            { text, done -> requests.add(text); synthesized = done },
            { audio, done -> played.add(audio); playbackDone = done },
            results::add,
        )
        val audio = SpeechAudio(floatArrayOf(0.25f), 12000)
    }

    @Test fun multiSentenceReplyUsesOneRequestAndOnePlayback() {
        val f = Fixture()
        val text = "眼前是一台笔记本电脑，屏幕亮着。桌子上方还有一盏长条形灯。"
        f.pipeline.start(text)
        assertEquals(listOf(text), f.requests)
        f.synthesized(f.audio)
        assertEquals(listOf(f.audio), f.played)
        assertTrue(f.results.isEmpty())
        f.playbackDone(true)
        assertEquals(listOf(true), f.results)
    }

    @Test fun cancellationDuringSynthesisDiscardsLateAudio() {
        val f = Fixture(); f.pipeline.start("请稍候")
        f.pipeline.cancel()
        f.synthesized(f.audio)
        assertTrue(f.played.isEmpty())
        assertEquals(listOf(false), f.results)
    }

    @Test fun cancellationDuringPlaybackIgnoresLateReceipt() {
        val f = Fixture(); f.pipeline.start("请稍候"); f.synthesized(f.audio)
        f.pipeline.cancel(); f.playbackDone(true)
        assertEquals(listOf(false), f.results)
    }

    @Test fun synthesisOrPlaybackFailureIsReported() {
        val first = Fixture(); first.pipeline.start("请稍候"); first.synthesized(null)
        assertTrue(first.played.isEmpty())
        assertEquals(listOf(false), first.results)
        val second = Fixture(); second.pipeline.start("请稍候"); second.synthesized(second.audio)
        second.playbackDone(false)
        assertEquals(listOf(false), second.results)
    }
}
