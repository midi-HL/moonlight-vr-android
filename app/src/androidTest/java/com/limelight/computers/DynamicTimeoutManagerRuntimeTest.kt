package com.limelight.computers

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises map-backed probe failure tracking on the device runtime, including Android desugaring. */
@RunWith(AndroidJUnit4::class)
class DynamicTimeoutManagerRuntimeTest {
    @Test fun consecutiveProbeFailuresUpdateHealthAndCooldownWithoutCrashing() {
        val manager = DynamicTimeoutManager(
            NetworkDiagnostics(InstrumentationRegistry.getInstrumentation().targetContext),
        )
        val address = "192.0.2.1"

        manager.recordFailure(address)
        assertFalse(manager.isAddressHealthy(address))
        assertTrue(manager.getProbeCooldownRemainingMs(address, isLikelyOnline = false) == 0L)

        manager.recordFailure(address)
        manager.recordFailure(address)

        assertTrue(manager.getProbeCooldownRemainingMs(address, isLikelyOnline = false) > 0L)
        assertTrue(manager.getStatisticsDebugInfo().contains(address))
    }
}
