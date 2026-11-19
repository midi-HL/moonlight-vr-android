package com.limelight.vr

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.OrientationManager
import com.limelight.PcView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VrOrientationRuntimeTest {
    @Test
    fun vrWinsOverStreamOrientationAndPendingServerRestore() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val original = VrSession.settings
        instrumentation.runOnMainSync { VrSession.setEnabled(context, false) }
        val scenario = ActivityScenario.launch<PcView>(
            Intent(context, PcView::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var manager: OrientationManager? = null
        try {
            scenario.onActivity { activity ->
                assertTrue(VrSession.isSupported())
                val orientation = OrientationManager(activity, 720, 1280, true, false) { activity.windowManager.defaultDisplay }
                manager = orientation
                orientation.onServerResolutionChanged(isLandscape = false)
                assertTrue(orientation.isServerInitiatedRotation)
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, activity.requestedOrientation)

                VrSession.setEnabled(activity, true)
                assertTrue(VrSession.enabled)
                orientation.setPreferredOrientation()
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
                orientation.onConfigurationChanged()
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
                orientation.onServerResolutionChanged(isLandscape = false)
                assertFalse(orientation.isServerInitiatedRotation)
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
                orientation.connected = true
                orientation.syncOrientationOnFirstFrame(720, 1280)
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
            }
            // Cross the original server-rotation timer's deadline without blocking the UI thread.
            val settled = CountDownLatch(1)
            Handler(Looper.getMainLooper()).postDelayed({ settled.countDown() }, 1_100L)
            assertTrue("Main-loop orientation deadline did not run", settled.await(3, TimeUnit.SECONDS))
            scenario.onActivity { activity ->
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
                VrSession.setEnabled(activity, false)
                manager!!.reset()
                manager!!.setPreferredOrientation()
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, activity.requestedOrientation)
                val portrait = OrientationManager(activity, 720, 1280, false, false) { activity.windowManager.defaultDisplay }
                try {
                    portrait.setPreferredOrientation()
                    assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, activity.requestedOrientation)
                } finally {
                    portrait.cleanup()
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                manager?.cleanup()
                VrSession.updateSettings(context, original)
            }
            scenario.close()
        }
    }
}
