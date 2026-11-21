package com.limelight.vr

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.PcView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Regressions at the real pause/stop and actual-window-orientation boundaries. */
@RunWith(AndroidJUnit4::class)
class VrTransitionLifecycleRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun pausedVisibleWindowRetainsStereoAndResumesTheSameTracker() = withVrActivity { scenario ->
        lateinit var originalTheater: VrTheaterView
        scenario.onActivity { originalTheater = requireNotNull(findTheater(it.window.decorView)) }

        // STARTED pauses a visible Activity; unlike CREATED it does not send onStop.
        scenario.moveToState(Lifecycle.State.STARTED)
        instrumentation.runOnMainSync {
            assertNotNull("onPause must leave a stereo cover over the outgoing 2D window", originalTheater.parent)
        }
        scenario.moveToState(Lifecycle.State.RESUMED)
        scenario.onActivity { activity ->
            assertSame("A short pause must preserve Cardboard and its tracker reference frame",
                originalTheater, findTheater(activity.window.decorView))
        }
        scenario.moveToState(Lifecycle.State.CREATED)
        instrumentation.runOnMainSync {
            assertEquals("A stopped window must release its retained surface", null, originalTheater.parent)
        }
    }

    @Test fun disablingVrWhilePausedReleasesRetainedSurface() = withVrActivity { scenario ->
        lateinit var theater: VrTheaterView
        scenario.onActivity { theater = requireNotNull(findTheater(it.window.decorView)) }
        scenario.moveToState(Lifecycle.State.STARTED)
        instrumentation.runOnMainSync {
            VrSession.setEnabled(context, false)
            assertEquals("Disabling VR must also clean paused window covers", null, theater.parent)
        }
    }

    @Test fun requestedLandscapeDoesNotStartTrackerInAnActuallyPortraitWindow() {
        val original = VrSession.settings
        instrumentation.runOnMainSync { VrSession.setEnabled(context, false) }
        val scenario = ActivityScenario.launch<PcView>(
            Intent(context, PcView::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var host: VrOverlayHost? = null
        try {
            var activity: PcView? = null
            scenario.onActivity {
                assertTrue(VrSession.isSupported())
                activity = it
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            awaitMain {
                val owner = requireNotNull(activity)
                owner.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT &&
                    owner.window.decorView.height > owner.window.decorView.width
            }
            instrumentation.runOnMainSync {
                val owner = requireNotNull(activity)
                host = VrOverlayHost(owner, owner.window.decorView as ViewGroup).also {
                    it.install()
                    assertEquals("Portrait bounds must defer Cardboard creation", null, it.theaterView())
                }
                // The request changes immediately; the real configuration/bounds change later.
                owner.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                assertEquals("An orientation request alone must not open the tracker gate",
                    null, host!!.theaterView())
            }
            awaitMain { host?.theaterView() != null }
        } finally {
            instrumentation.runOnMainSync { host?.release() }
            scenario.close()
            instrumentation.runOnMainSync { VrSession.updateSettings(context, original) }
        }
    }

    @Test fun normalBackNavigatesThePageAndOnlyPanelOwnedBackDismissesVrControls() = withVrActivity { scenario ->
        scenario.onActivity {
            VrSession.setPanelOpen(false)
            assertFalse("System Back outside VR controls belongs to the page", VrSession.onBackInvoked())
            val firstDownTime = SystemClock.uptimeMillis()
            assertFalse(VrSession.onKeyEvent(back(firstDownTime, KeyEvent.ACTION_DOWN), false, false))
            VrSession.setPanelOpen(true)
            assertFalse("Opening the panel must preserve a page-owned Back release",
                VrSession.onKeyEvent(back(firstDownTime, KeyEvent.ACTION_UP), false, false))
            assertTrue(VrSession.panelOpen)
            val panelDownTime = firstDownTime + 1
            assertTrue(VrSession.onKeyEvent(back(panelDownTime, KeyEvent.ACTION_DOWN), false, false))
            assertTrue(VrSession.onKeyEvent(back(panelDownTime, KeyEvent.ACTION_UP), false, false))
            assertFalse(VrSession.panelOpen)
            assertTrue("Back must never leave headset mode implicitly", VrSession.enabled)
        }
    }

    private fun back(downTime: Long, action: Int) =
        KeyEvent(downTime, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_BACK, 0, 0, 905, 0)

    private fun withVrActivity(block: (ActivityScenario<PcView>) -> Unit) {
        val original = VrSession.settings
        instrumentation.runOnMainSync { VrSession.setEnabled(context, false) }
        val scenario = ActivityScenario.launch<PcView>(
            Intent(context, PcView::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            var activity: PcView? = null
            scenario.onActivity {
                activity = it
                assertTrue(VrSession.isSupported())
                VrSession.setEnabled(it, true)
            }
            awaitMain { findTheater(requireNotNull(activity).window.decorView) != null }
            block(scenario)
        } finally {
            instrumentation.runOnMainSync { VrSession.setEnabled(context, false) }
            scenario.close()
            instrumentation.runOnMainSync { VrSession.updateSettings(context, original) }
        }
    }

    private fun awaitMain(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000L
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(40L)
        }
        throw AssertionError("VR window did not reach its expected actual lifecycle/orientation state")
    }

    private fun findTheater(view: View): VrTheaterView? {
        if (view is VrTheaterView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findTheater(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
