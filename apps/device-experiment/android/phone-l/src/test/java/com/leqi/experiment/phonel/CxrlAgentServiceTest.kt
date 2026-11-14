package com.leqi.experiment.phonel

import android.Manifest
import android.content.Intent
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CxrlAgentServiceTest {
    class Host : CxrlAgentService() {
        var blocker: String? = null
        var foregroundFailure: RuntimeException? = null
        var initializationFailure = false
        var starts = 0; var connects = 0; var releases = 0
        override fun startupBlocker() = blocker
        override fun enterForeground() { foregroundFailure?.let { throw it } }
        override fun initializeRuntime() { starts++; if (initializationFailure) error("partial initialization") }
        override fun connectRuntime(token: String) { connects++ }
        override fun releaseRuntime() { releases++; super.releaseRuntime() }
    }

    @Test fun repeatedStartReusesRuntimeAndRevocationStopsIt() {
        val controller = Robolectric.buildService(Host::class.java).create()
        val host = controller.get()
        val intent = Intent().putExtra("token", "test-only-token")
        host.onStartCommand(intent, 0, 1); host.onStartCommand(intent, 0, 2)
        assertEquals(1, host.starts); assertEquals(2, host.connects)
        host.blocker = "location_permission_missing"
        host.onStartCommand(intent, 0, 3)
        assertTrue(shadowOf(host).isStoppedBySelf)
        assertEquals(2, host.connects); assertEquals(1, host.releases)
        controller.destroy()
        assertEquals(1, host.releases)
    }

    @Test fun platformDenialAndPartialInitializationReleaseResources() {
        for (platformFailure in listOf(true, false)) {
            val controller = Robolectric.buildService(Host::class.java).create()
            val host = controller.get()
            if (platformFailure) host.foregroundFailure = SecurityException("private details")
            else host.initializationFailure = true
            host.onStartCommand(Intent().putExtra("token", "test-only-token"), 0, 1)
            assertTrue(shadowOf(host).isStoppedBySelf)
            assertEquals(0, host.connects); assertEquals(1, host.releases)
            assertEquals(if (platformFailure) 0 else 1, host.starts)
            assertEquals(if (platformFailure) "foreground_permission_denied" else "service_start_failed",
                host.getSharedPreferences("agent_status", 0).getString("startup_failure", null))
            controller.destroy()
            assertEquals(1, host.releases)
        }
    }

    @Test fun normalStopReleasesWithoutReinitializing() {
        val controller = Robolectric.buildService(Host::class.java).create()
        val host = controller.get()
        host.onStartCommand(Intent().putExtra("token", "test-only-token"), 0, 1)
        host.onStartCommand(Intent().setAction("stop"), 0, 2)
        assertEquals(1, host.starts); assertEquals(1, host.releases)
        assertTrue(shadowOf(host).isStoppedBySelf)
        controller.destroy()
    }

    @Test fun missingPermissionsStopsWithoutInitializingRuntime() {
        for (bluetoothGranted in listOf(false, true)) {
            val controller = Robolectric.buildService(CxrlAgentService::class.java).create()
            val service = controller.get()
            shadowOf(service.application).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (bluetoothGranted) shadowOf(service.application).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
            service.onStartCommand(Intent().putExtra("token", "test-only-token"), 0, 1)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(shadowOf(service).isStoppedBySelf)
            val web = CxrlAgentService::class.java.getDeclaredField("web").apply { isAccessible = true }
            assertNull(web.get(service))
            assertEquals(if (bluetoothGranted) "location_permission_missing" else "bluetooth_permission_missing",
                service.getSharedPreferences("agent_status", 0).getString("startup_failure", null))
            controller.destroy()
        }
    }

    @Test fun stopOnlyAndEmptyStartNeverInitializeRuntime() {
        for (intent in listOf(null, Intent().setAction("stop"), Intent())) {
            val controller = Robolectric.buildService(CxrlAgentService::class.java).create()
            val service = controller.get()
            service.onStartCommand(intent, 0, 1)
            assertTrue(shadowOf(service).isStoppedBySelf)
            val web = CxrlAgentService::class.java.getDeclaredField("web").apply { isAccessible = true }
            assertNull(web.get(service))
            controller.destroy()
        }
    }
}
