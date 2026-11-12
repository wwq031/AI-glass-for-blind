package com.leqi.experiment.phonel

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class AgentModelSettingsTest {
    @Test fun defaultsFreeAndCredentialsAreEncryptedAcrossReopen() {
        val preferences = RuntimeEnvironment.getApplication().getSharedPreferences(UUID.randomUUID().toString(), Context.MODE_PRIVATE)
        val encryptionKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val settings = AgentModelSettings(preferences) { encryptionKey }
        assertFalse(settings.cloudEnabled); assertFalse(settings.hasKey)
        assertEquals(AgentModelSettings.FLASH, settings.model)
        assertThrows(IllegalArgumentException::class.java) { settings.save(true, AgentModelSettings.FLASH, "") }
        settings.save(true, AgentModelSettings.FLASH, "test-private-key")
        assertFalse(preferences.all.toString().contains("test-private-key"))
        val reopened = AgentModelSettings(preferences) { encryptionKey }
        assertEquals("test-private-key", reopened.apiKey())
        reopened.save(true, AgentModelSettings.PRO, "")
        assertEquals("test-private-key", reopened.apiKey())
        assertEquals(AgentModelSettings.PRO, reopened.model)
        reopened.recordUsage(42); reopened.recordUsage(8)
        assertEquals(50L, reopened.totalTokens)
        reopened.clearKey()
        assertFalse(reopened.cloudEnabled); assertFalse(reopened.hasKey)
        assertEquals("", reopened.apiKey())
    }

    @Test fun corruptedCredentialFailsClosedAndLocalModeNeedsNoDecryption() {
        val preferences = RuntimeEnvironment.getApplication().getSharedPreferences(UUID.randomUUID().toString(), Context.MODE_PRIVATE)
        val encryptionKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val settings = AgentModelSettings(preferences) { encryptionKey }
        settings.save(true, AgentModelSettings.FLASH, "test")
        preferences.edit().putString("credential", "AAAA").commit()
        assertThrows(Exception::class.java) { settings.apiKey() }
        settings.save(false, AgentModelSettings.FLASH, "")
        assertFalse(settings.cloudEnabled)
        assertThrows(IllegalArgumentException::class.java) { settings.save(true, AgentModelSettings.FLASH, "bad\nkey") }
    }
}
