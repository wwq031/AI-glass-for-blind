package com.leqi.experiment.phonel

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class SpeechTextDiagnosticsTest {
    @Test fun textRequiresDebugBuildAndExplicitOptIn() {
        for (debuggable in listOf(false, true)) {
            val records = mutableListOf<String>()
            val trace = SpeechTextDiagnostics(debuggable, records::add)
            trace.record("asr", "turn-1", "固定测试文字")
            assertTrue(records.isEmpty())
            trace.enabled = true
            trace.record("asr", "turn-1", "固定测试文字")
            assertEquals(if (debuggable) 1 else 0, records.size)
            trace.enabled = false
            trace.record("speech", "play-1", "不再记录")
            assertEquals(if (debuggable) 1 else 0, records.size)
        }
    }

    @Test fun recordsPreserveTextAndCorrelationWithoutInjectingLogLines() {
        val records = mutableListOf<String>()
        val trace = SpeechTextDiagnostics(true, records::add).apply { enabled = true }
        val text = "桌上有电脑，最后是水杯。\n\"测试完成\"😀"
        trace.record("speech", "play-2", text)
        trace.record("tts.part", "play-2", text)
        assertEquals(2, records.size)
        records.forEach { raw ->
            assertFalse(raw.contains('\n'))
            val record = JSONObject(raw)
            assertEquals("play-2", record.getString("id"))
            assertEquals(text, record.getString("text"))
            assertEquals(setOf("stage", "id", "text"), record.keys().asSequence().toSet())
        }
        assertEquals("speech", JSONObject(records[0]).getString("stage"))
        assertEquals("tts.part", JSONObject(records[1]).getString("stage"))
    }
}
