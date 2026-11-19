package com.leqi.experiment.phonel

import android.Manifest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConnectionPermissionsTest {
    @Test fun approximateLocationIsSufficientAndNotificationsDoNotGateConnection() {
        val activity = Robolectric.buildActivity(CxrlAgentActivity::class.java).get()
        val app = shadowOf(activity.application)
        app.denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
        app.grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_COARSE_LOCATION)
        assertTrue(activity.missingConnectionPermissions(false).isEmpty())
        app.denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        assertTrue(activity.missingConnectionPermissions(false).contains(Manifest.permission.ACCESS_FINE_LOCATION))
        assertTrue(activity.missingConnectionPermissions(true).isEmpty())
    }
}
