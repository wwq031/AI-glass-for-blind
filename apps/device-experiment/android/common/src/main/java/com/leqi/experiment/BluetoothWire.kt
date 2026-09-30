package com.leqi.experiment

import java.util.UUID

/** Shared identity for the temporary, self-owned Bluetooth RFCOMM experiment. */
class BluetoothWire private constructor() {
    companion object {
        @JvmField
        val SERVICE_ID: UUID = UUID.fromString("30ce9c2c-2a4e-4d56-873e-c681bf52a106")

        const val SESSION: String = "device-probe-1"
    }
}
