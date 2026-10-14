package com.leqi.experiment.phonel

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class MockObservationTest {
    @Test fun noObservationCanBeCreatedBeforeValidatedImage() {
        val gate = PhotoGate().apply { newSession("s1") }
        val request = gate.arm(0)!!
        try {
            MockObservation.create(request, PhotoMetadata(20, 32, 24, "image/webp"))
            fail("Unconfirmed image must not produce a result")
        } catch (_: IllegalArgumentException) { }
    }
    @Test fun resultRetainsRequestIdentityAndCannotClaimSceneRecognition() {
        val gate = PhotoGate().apply { newSession("s1") }
        val request = gate.arm(0)!!
        gate.confirm(request.id, 1); gate.receive(request.id, 2); gate.validated(request.id, 3)
        val result = MockObservation.create(request, PhotoMetadata(20, 32, 24, "image/webp"))
        assertEquals(request.id, result.getString("request_id"))
        assertEquals("s1", result.getString("session_id"))
        assertEquals("vision.scene", result.getString("capability_id"))
        assertEquals("partial", result.getString("status"))
        assertEquals("unknown", result.getString("confidence"))
        assertEquals(0, result.getJSONArray("facts").length())
        assertTrue(result.getString("summary").startsWith("Mock："))
        assertFalse(result.has("source"))
    }
    @Test fun customViewDeltaPreservesEscapedTextAndTargetsExistingNode() {
        val text = "Mock：\"quoted\"\nline\\tail"
        val update = JSONArray(MockObservation.viewUpdate(text)).getJSONObject(0)
        assertEquals("title_text", update.getString("id"))
        assertEquals("update", update.getString("action"))
        assertEquals(text, update.getJSONObject("props").getString("text"))
    }
}
