package com.leqi.experiment.phonel

import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class SpeechDialogueTest {
    private class Queue : Executor {
        val work = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { work.addLast(command) }
        fun drain() { while (work.isNotEmpty()) work.removeFirst().run() }
    }
    private class Mic : SpeechRecorder {
        var starts = 0; var stops = 0; var cancels = 0
        lateinit var callback: (Result<FloatArray>) -> Unit
        override fun start(result: (Result<FloatArray>) -> Unit) { starts++; callback = result }
        override fun finish() { stops++; callback(Result.success(FloatArray(16000) { 0.1f })) }
        override fun cancel() { cancels++ }
    }
    private class Speaker : SpeechSpeaker {
        var calls = 0; var stops = 0
        lateinit var callback: (Result<String>) -> Unit
        override fun play(audio: SpeechAudio, result: (Result<String>) -> Unit) { audio.validate(); calls++; callback = result }
        override fun stop() { stops++ }
    }
    private class Model : TextResponder {
        override var ready = true
        var calls = 0; var cancels = 0; var prompt = ""
        lateinit var callback: (Result<String>) -> Unit
        override fun answer(prompt: String, result: (Result<String>) -> Unit) { this.prompt = prompt; calls++; callback = result }
        override fun cancelText() { cancels++ }
    }
    private class Engines : SpeechEngines {
        var asrCalls = 0; var ttsCalls = 0; var closed = false
        var failAsr = false; var failTts = false
        override fun initialize() { }
        override fun transcribe(samples: FloatArray): String { asrCalls++; check(!failAsr); return "你好，测试私有语音" }
        override fun synthesize(text: String): SpeechAudio { ttsCalls++; check(!failTts); return SpeechAudio(FloatArray(16000) { 0.1f }, 16000) }
        override fun close() { closed = true }
    }
    private class Fixture(worker: Executor = Executor { it.run() }) {
        val mic = Mic(); val speaker = Speaker(); val model = Model(); val engines = Engines()
        val views = mutableListOf<SpeechView>()
        val dialogue = SpeechDialogue(mic, speaker, model, { engines }, { views.add(it) }, worker)
        init { idle() }
        fun ask() { dialogue.startRecording(); dialogue.stopRecordingAndSend(); idle() }
        fun reply(text: String = "你好，这是私有回答") { model.callback(Result.success(text)); idle() }
        fun heard() { speaker.callback(Result.success("手机扬声器")); idle() }
    }
    companion object { private fun idle() { shadowOf(Looper.getMainLooper()).idle() } }
    @Test fun fullTurnRequiresExplicitStartAndCompletesRealStagesWithoutExportingUtterances() {
        val fixture = Fixture()
        assertEquals(0, fixture.mic.starts)
        assertEquals(0, fixture.engines.asrCalls)
        fixture.ask()
        assertEquals(1, fixture.mic.starts); assertEquals(1, fixture.mic.stops)
        assertEquals(1, fixture.engines.asrCalls); assertEquals(1, fixture.model.calls)
        assertEquals(0, fixture.speaker.calls)
        fixture.reply()
        assertEquals(SpeechStage.PLAYING, fixture.dialogue.stage)
        fixture.dialogue.startRecording(); assertEquals(1, fixture.mic.starts)
        fixture.heard(); assertEquals(SpeechStage.IDLE, fixture.dialogue.stage)
        val before = fixture.dialogue.export().toString()
        assertFalse(before.contains("私有")); assertFalse(before.contains("human_heard"))
        fixture.dialogue.confirmHeard()
        val record = fixture.dialogue.export().getJSONArray("runs").getJSONObject(0)
        assertEquals("completed", record.getString("outcome")); assertTrue(record.getBoolean("human_heard"))
        assertTrue(record.getJSONObject("timestamps_ms").has("play_end"))
        fixture.dialogue.close(); assertTrue(fixture.engines.closed)
    }
    @Test fun cancelRecordingRejectsLateMicrophoneData() {
        val fixture = Fixture(); fixture.dialogue.startRecording()
        fixture.dialogue.cancel(); fixture.mic.callback(Result.success(FloatArray(16000))); idle()
        assertEquals(0, fixture.engines.asrCalls); assertEquals(0, fixture.model.calls)
        fixture.dialogue.close()
    }
    @Test fun cancellationDuringQueuedAsrCannotLaunchModelOrPlayback() {
        val queue = Queue(); val fixture = Fixture(queue); queue.drain(); idle()
        fixture.ask(); fixture.dialogue.cancel(); queue.drain(); idle()
        assertEquals(0, fixture.engines.asrCalls); assertEquals(0, fixture.model.calls)
        fixture.dialogue.close(); queue.drain()
    }
    @Test fun lateModelReplyAfterBackgroundCannotSynthesizeOrContaminateNewTurn() {
        val fixture = Fixture(); fixture.ask(); val old = fixture.model.callback
        fixture.dialogue.clearHistory()
        fixture.ask()
        old(Result.success("旧回答")); idle()
        assertEquals(0, fixture.engines.ttsCalls)
        fixture.reply("当前回答"); fixture.heard()
        assertEquals("当前回答", fixture.views.last().answer)
        fixture.dialogue.close()
    }
    @Test fun cancelledQueuedTtsNeverStartsSpeaker() {
        val queue = Queue(); val fixture = Fixture(queue); queue.drain(); idle()
        fixture.ask(); queue.drain(); idle()
        fixture.model.callback(Result.success("回答")); fixture.dialogue.cancel()
        queue.drain(); idle(); assertEquals(0, fixture.speaker.calls)
        fixture.dialogue.close(); queue.drain()
    }
    @Test fun asrAndTtsFailureDoNotCreateSuccessOrFallbackAudio() {
        for (asrFailure in listOf(true, false)) {
            val fixture = Fixture()
            if (asrFailure) fixture.engines.failAsr = true else fixture.engines.failTts = true
            fixture.ask(); if (!asrFailure) fixture.reply()
            assertEquals(SpeechStage.FAILED, fixture.dialogue.stage)
            assertEquals(0, fixture.speaker.calls)
            assertEquals("failed", fixture.dialogue.export().getJSONArray("runs").getJSONObject(0).getString("outcome"))
            fixture.dialogue.close()
        }
    }
    @Test fun modelTimeoutRejectsLateAnswerAndStopsDevices() {
        val fixture = Fixture(); fixture.ask()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(121))
        fixture.reply(); assertEquals(SpeechStage.FAILED, fixture.dialogue.stage)
        assertEquals(0, fixture.speaker.calls); assertTrue(fixture.mic.cancels > 0)
        fixture.dialogue.close()
    }
    @Test fun ttsOnlyAndImageReadingWorkWithoutCameraOrRecognition() {
        val fixture = Fixture()
        fixture.dialogue.speakPhoto(); assertEquals(0, fixture.speaker.calls)
        fixture.dialogue.setPhotoDescription("桌上有杯子")
        fixture.dialogue.speakPhoto(); idle(); fixture.heard()
        assertEquals(0, fixture.mic.starts); assertEquals(0, fixture.model.calls)
        assertEquals(1, fixture.engines.ttsCalls)
        fixture.dialogue.close()
    }
    @Test fun historyIsBoundedAndClearedRatherThanHiddenInNativeConversation() {
        val fixture = Fixture()
        for (index in 1..8) { fixture.ask(); fixture.reply("第${index}轮回答"); fixture.heard() }
        assertFalse(fixture.model.prompt.contains("第1轮回答"))
        assertTrue(fixture.model.prompt.contains("第7轮回答"))
        assertTrue(fixture.model.prompt.length < 2100)
        fixture.dialogue.clearHistory(); fixture.ask()
        assertFalse(fixture.model.prompt.contains("第7轮回答"))
        fixture.dialogue.close()
    }
    @Test fun strictAnswerParsingAndAudioBoundsRejectMalformedOrSilentPlaceholders() {
        assertEquals("你好", SpeechDialogue.parseAnswer("{\"answer\":\"你好\"}"))
        for (raw in listOf("not JSON", "{\"answer\":null}", "{\"answer\":\"\"}",
            "{\"answer\":\"a\"}{\"answer\":\"b\"}", "{\"answer\":\"a\",\"extra\":1}")) {
            assertThrows(Exception::class.java) { SpeechDialogue.parseAnswer(raw) }
        }
        assertThrows(IllegalArgumentException::class.java) { SpeechAudio(floatArrayOf(Float.NaN), 16000).validate() }
        assertThrows(IllegalArgumentException::class.java) { SpeechAudio(floatArrayOf(), 16000).validate() }
        assertThrows(IllegalArgumentException::class.java) { SpeechAudio(FloatArray(16000), 16000).validate() }
    }
    @Test fun failedInitializationNeverRecordsOrPretendsToBeLoadingAfterBackground() {
        val mic = Mic(); val speaker = Speaker(); val model = Model(); val views = mutableListOf<SpeechView>()
        val dialogue = SpeechDialogue(mic, speaker, model, { error("missing models") }, { views.add(it) }, Executor { it.run() })
        idle(); assertEquals(SpeechStage.FAILED, dialogue.stage)
        dialogue.clearHistory(); dialogue.startRecording()
        assertEquals(SpeechStage.FAILED, dialogue.stage); assertEquals(0, mic.starts)
        dialogue.close()
    }
    @Test fun cancelledPlaybackCallbackCannotFinishANewerRecording() {
        val fixture = Fixture(); fixture.ask(); fixture.reply()
        val oldPlayback = fixture.speaker.callback
        fixture.dialogue.cancel(); fixture.dialogue.startRecording()
        oldPlayback(Result.success("旧设备")); idle()
        assertEquals(SpeechStage.RECORDING, fixture.dialogue.stage)
        assertTrue(fixture.speaker.stops > 0)
        assertEquals("cancelled", fixture.dialogue.export().getJSONArray("runs").getJSONObject(0).getString("outcome"))
        fixture.dialogue.close()
    }
    @Test fun recordingTimeLimitEndsOneUtteranceWithoutStartingAnotherCapture() {
        val fixture = Fixture(); fixture.dialogue.startRecording()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))
        assertEquals(1, fixture.mic.starts); assertEquals(1, fixture.mic.stops)
        assertEquals(1, fixture.model.calls)
        fixture.reply(); fixture.heard()
        assertEquals(1, fixture.mic.starts)
        fixture.dialogue.close()
    }
}
