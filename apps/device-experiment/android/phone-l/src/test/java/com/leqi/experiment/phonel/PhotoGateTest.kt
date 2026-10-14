package com.leqi.experiment.phonel

import org.junit.Assert.*
import org.junit.Test

class PhotoGateTest {
    @Test fun captureRequiresReadySessionAndExplicitCurrentConsent() {
        val gate = PhotoGate()
        assertNull(gate.arm(0))
        gate.newSession("s1")
        val request = gate.arm(10)!!
        assertNull(gate.confirm("other-request", 11))
        assertFalse(gate.receive(request.id, 11))
        assertNotNull(gate.confirm(request.id, 12))
        assertNull(gate.confirm(request.id, 13))
    }
    @Test fun consentExpiresAndCannotBeConfirmedFromThePast() {
        val gate = PhotoGate().apply { newSession("s1") }
        val request = gate.arm(100)!!
        assertNull(gate.confirm(request.id, 99))
        assertNull(gate.confirm(request.id, 30_100))
    }
    @Test fun duplicateWrongAndExpiredImagesNeverBecomeValidated() {
        val gate = PhotoGate().apply { newSession("s1") }
        val request = gate.arm(0)!!
        gate.confirm(request.id, 1)
        assertFalse(gate.receive("wrong", 2))
        assertFalse(gate.validated(request.id, 2))
        assertTrue(gate.receive(request.id, 3))
        assertFalse(gate.receive(request.id, 4))
        assertFalse(gate.validated(request.id, 30_001))
    }
    @Test fun timeoutOrCancellationCannotReassignAnUntaggedSdkImage() {
        val gate = PhotoGate().apply { newSession("s1") }
        val request = gate.arm(0)!!
        gate.confirm(request.id, 1)
        gate.cancel()
        assertFalse(gate.receive(request.id, 2))
        assertNull(gate.arm(3))
        gate.close()
        gate.newSession("s2")
        val next = gate.arm(4)!!
        assertNotEquals(request.id, next.id)
        assertFalse(gate.receive(request.id, 5))
    }
    @Test fun pauseRevokesConsentAndResumeDoesNotRearm() {
        val gate = PhotoGate().apply { newSession("s1") }
        val request = gate.arm(0)!!
        gate.pause()
        gate.resume()
        assertNull(gate.confirm(request.id, 1))
        assertNull(gate.current)
        assertNotNull(gate.arm(2))
    }
    @Test fun completedCaptureStillConsumesTheSdkSession() {
        val gate = PhotoGate().apply { newSession("s1") }
        val request = gate.arm(0)!!
        gate.confirm(request.id, 1)
        assertTrue(gate.receive(request.id, 2))
        assertTrue(gate.validated(request.id, 3))
        assertFalse(gate.validated(request.id, 4))
        gate.cancel()
        assertNull(gate.arm(5))
    }
    @Test fun cancelledConfirmationCanBeArmedAgainWithoutCapturing() {
        val gate = PhotoGate().apply { newSession("s1") }
        val request = gate.arm(0)!!
        gate.cancel()
        val next = gate.arm(1)!!
        assertNull(gate.confirm(request.id, 2))
        assertNotNull(gate.confirm(next.id, 2))
    }
    @Test fun imageDimensionsAreBoundedBeforeAllocatingABitmap() {
        assertTrue(PhotoDecoder.validBounds(640, 480))
        assertFalse(PhotoDecoder.validBounds(0, 1))
        assertFalse(PhotoDecoder.validBounds(4097, 1))
        assertFalse(PhotoDecoder.validBounds(4096, 4096))
    }
}
