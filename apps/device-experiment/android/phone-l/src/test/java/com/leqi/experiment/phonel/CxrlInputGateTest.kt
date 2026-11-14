package com.leqi.experiment.phonel

import com.leqi.experiment.CxrlInputGate
import org.junit.Assert.*
import org.junit.Test

class CxrlInputGateTest {
    @Test fun authorizationIsConsumedOnceAndCannotCrossCancel() {
        val gate = CxrlInputGate()
        gate.arm("voice.arm", "turn-1", 100)
        val first = gate.press(101)!!
        assertEquals("voice.confirm", first.first)
        assertEquals("turn-1", first.second)
        assertNull(gate.press(102))
        gate.arm("entrance.arm", "photo-1", 103)
        gate.cancel()
        assertNull(gate.press(104))
    }
    @Test fun expiredWindowReportsExpiryAndNeverConfirmsCapture() {
        val gate = CxrlInputGate()
        gate.arm("entrance.arm", "photo", 0)
        assertEquals("entrance.expired", gate.press(120001)!!.first)
        assertNull(gate.press(120002))
    }
    @Test fun newWindowRetiresTheOtherInputAndDisarmIsSpecific() {
        val gate = CxrlInputGate()
        gate.arm("voice.arm", "voice", 0)
        gate.arm("entrance.arm", "photo", 1)
        gate.arm("voice.disarm", "", 2)
        assertEquals("photo", gate.press(3)!!.second)
    }
    @Test fun delayedExpiryCannotConsumeAReplacementWindow() {
        val gate = CxrlInputGate()
        gate.arm("voice.arm", "old", 0)
        gate.arm("voice.arm", "new", 80000)
        assertNull(gate.expire(90001))
        assertEquals("new", gate.press(90002)!!.second)
    }
}
