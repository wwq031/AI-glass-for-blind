package com.leqi.experiment.phonel

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class MvpRunLogTest {
    private fun request(): PhotoGate.Request {
        val gate = PhotoGate().apply { newSession("session") }
        val request = gate.arm(0)!!
        gate.confirm(request.id, 1)
        return request
    }
    @Test fun stagesCannotMixRequestsSkipPrerequisitesOrReverseClock() {
        val log = MvpRunLog()
        val request = request()
        log.start(request, "cold")
        assertFalse(log.mark("old", "image_callback", 2))
        assertFalse(log.mark(request.id, "human_visible", 2))
        assertFalse(log.mark(request.id, "image_callback", 0))
        assertTrue(log.mark(request.id, "image_callback", 2))
        assertFalse(log.mark(request.id, "image_callback", 3))
    }
    @Test fun sendAcceptedIsSeparateFromHumanVisibleAndFinishedRunIsNotOverwritten() {
        val log = MvpRunLog()
        val request = request()
        log.start(request, "warm")
        for ((index, stage) in MvpRunLog.STAGES.drop(1).dropLast(1).withIndex())
            assertTrue(log.mark(request.id, stage, (index + 2).toLong()))
        assertEquals("display_sent", log.export().getJSONArray("runs").getJSONObject(0).getString("outcome"))
        assertTrue(log.mark(request.id, "human_visible", 10))
        log.finish("lifecycle")
        assertEquals("human_confirmed", log.export().getJSONArray("runs").getJSONObject(0).getString("outcome"))
        assertEquals("unverified", log.export().getString("evidence_level"))
    }
    @Test fun exportIsSnapshotAndHistoryIsBounded() {
        val log = MvpRunLog()
        for (i in 0..100) { log.start(request(), "warm"); log.finish("capture") }
        val export = log.export()
        assertEquals(100, export.getJSONArray("runs").length())
        export.getJSONArray("runs").getJSONObject(0).put("outcome", "forged")
        assertEquals("failed", log.export().getJSONArray("runs").getJSONObject(0).getString("outcome"))
    }
}
