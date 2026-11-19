package com.leqi.experiment.phonel

import com.leqi.experiment.ShortcutInput
import org.junit.Assert.*
import org.junit.Test

class ShortcutInputTest {
    @Test fun onlyVerifiedRingClicksAreRoutedAndReservedKeysAreConsumedWithoutBusinessAction() {
        assertEquals("voice", ShortcutInput.action("RGR06 Keyboard", 66))
        assertEquals("navigate", ShortcutInput.action("RGR06 Consumer Control", 85))
        assertEquals("ignore", ShortcutInput.action("RGR06", 83))
        assertEquals("ignore", ShortcutInput.action("RGR06", 186))
        assertEquals("ignore", ShortcutInput.action("RGR06", 22))
        assertNull(ShortcutInput.action("ordinary keyboard", 85))
        assertNull(ShortcutInput.action(null, 66))
    }
}
