package com.leqi.experiment

/** Only the devices and click codes captured in rgr06-adb-events.md are business inputs. */
object ShortcutInput {
    fun action(deviceName: String?, keyCode: Int): String? {
        if (deviceName !in setOf("RGR06", "RGR06 Keyboard", "RGR06 Consumer Control")) return null
        return when (keyCode) {
            66 -> "voice" // ENTER: measured left single click
            85 -> "navigate" // MEDIA_PLAY_PAUSE: measured middle single click
            else -> "ignore" // consume prefixes, scrolling and reserved keys in this Activity
        }
    }
}
