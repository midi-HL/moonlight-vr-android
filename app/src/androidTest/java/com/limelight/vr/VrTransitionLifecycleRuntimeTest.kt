package com.limelight.vr

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.os.Bundle
import android.os.SystemClock
import android.graphics.Canvas
import android.view.KeyEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.PcView
import com.limelight.R
import com.limelight.preferences.StreamSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/** Regressions at the real pause/stop and actual-window-orientation boundaries. */
@RunWith(AndroidJUnit4::class)
class VrTransitionLifecycleRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun pausedVisibleWindowRetainsStereoAndResumesTheSameTracker() = withVrActivity { scenario ->
        lateinit var originalTheater: VrTheaterView
        var captures = 0
        scenario.onActivity { activity ->
            originalTheater = requireNotNull(findTheater(activity.window.decorView))
            // Count the real software capture producer, independently of the GL frame loop.
            val counter = object : View(activity) {
                override fun draw(canvas: Canvas) {
                    if (!canvas.isHardwareAccelerated) captures++
                    super.draw(canvas)
                }
            }
            (activity.window.decorView as ViewGroup).addView(counter, FrameLayout.LayoutParams(1, 1))
        }
        awaitMain { captures > 0 }

        // STARTED pauses a visible Activity; unlike CREATED it does not send onStop.
        scenario.moveToState(Lifecycle.State.STARTED)
        var pausedCaptures = 0
        instrumentation.runOnMainSync {
            assertNotNull("onPause must leave a stereo cover over the outgoing 2D window", originalTheater.parent)
            pausedCaptures = captures
        }
        SystemClock.sleep(220L)
        instrumentation.runOnMainSync {
            assertEquals("The paused visible host must stop capturing the source UI", pausedCaptures, captures)
        }
        scenario.moveToState(Lifecycle.State.RESUMED)
        scenario.onActivity { activity ->
            assertSame("A short pause must preserve Cardboard and its tracker reference frame",
                originalTheater, findTheater(activity.window.decorView))
        }
        awaitMain { captures > pausedCaptures }
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

    @Test fun retainedOutgoingWindowKeepsItsNativeDialogHiddenUntilStop() = withVrActivity { scenario ->
        var dialog: Dialog? = null
        try {
            scenario.onActivity { activity ->
                dialog = Dialog(activity).apply { setTitle("Retained VR dialog"); show() }
                VrSession.registerDialog(requireNotNull(dialog))
            }
            awaitMain { requireNotNull(dialog).window!!.attributes.alpha < 0.001f }
            scenario.moveToState(Lifecycle.State.STARTED)
            instrumentation.runOnMainSync {
                assertTrue("A paused but visible stereo window must not reveal its native dialog",
                    requireNotNull(dialog).window!!.attributes.alpha < 0.001f)
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            instrumentation.runOnMainSync {
                assertEquals("Stopping the retained host must restore the dialog's native opacity",
                    1f, requireNotNull(dialog).window!!.attributes.alpha, 0f)
            }
        } finally {
            instrumentation.runOnMainSync { dialog?.dismiss() }
        }
    }

    @Test fun normal2dSettingsRestoreAutomaticRotationAfterAPortraitSource() = verify2dSettingsRotation(false)

    @Test fun fixedLandscape2dSourceDoesNotLockSettingsOrientation() = verify2dSettingsRotation(true)

    private fun verify2dSettingsRotation(landscapeSource: Boolean) {
        val original = VrSession.settings
        val application = context.applicationContext as Application
        var target: StreamSettings? = null
        var targetResumed = false
        val observer = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                if (activity is StreamSettings) { target = activity; targetResumed = false }
            }
            override fun onActivityResumed(activity: Activity) {
                if (activity === target) targetResumed = true
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        }
        instrumentation.runOnMainSync {
            VrSession.setEnabled(context, false)
            application.registerActivityLifecycleCallbacks(observer)
        }
        val scenario = ActivityScenario.launch<PcView>(
            Intent(context, PcView::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            var source: PcView? = null
            var automaticOrientation = Configuration.ORIENTATION_UNDEFINED
            scenario.onActivity {
                source = it
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            awaitMain {
                val owner = requireNotNull(source)
                val decor = owner.window.decorView
                decor.isLaidOut && decor.width > 0 && decor.height > 0 &&
                    owner.resources.configuration.orientation == if (decor.width > decor.height)
                        Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
            }
            scenario.onActivity {
                automaticOrientation = it.resources.configuration.orientation
                it.requestedOrientation = if (landscapeSource) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            val sourceOrientation = if (landscapeSource) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
            awaitMain { source?.resources?.configuration?.orientation == sourceOrientation }
            scenario.onActivity { it.startActivity(Intent(it, StreamSettings::class.java)) }
            awaitMain { targetResumed && target?.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED &&
                target?.resources?.configuration?.orientation == automaticOrientation }
            instrumentation.runOnMainSync {
                assertFalse(VrSession.enabled)
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, requireNotNull(target).requestedOrientation)
                assertEquals(automaticOrientation, requireNotNull(target).resources.configuration.orientation)
                assertEquals(0, countTheaters(requireNotNull(target).window.decorView))
            }
        } finally {
            instrumentation.runOnMainSync {
                target?.finish()
                application.unregisterActivityLifecycleCallbacks(observer)
            }
            scenario.close()
            instrumentation.runOnMainSync { VrSession.updateSettings(context, original) }
        }
    }

    @Test fun floatingActivityLayoutExpandsForStereoAndRestoresOnExit() = withVrActivity { scenario ->
        scenario.onActivity { activity ->
            VrSession.setEnabled(activity, false)
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            // Use AppSelectionActivity's actual floating theme on a stable, network-independent
            // Activity window, including nondefault values that must survive the VR round trip.
            activity.setTheme(R.style.AppDialogStyle)
            activity.window.attributes = activity.window.attributes.also {
                it.width = 360
                it.height = 240
                it.gravity = Gravity.BOTTOM or Gravity.RIGHT
                it.dimAmount = 0.38f
            }
            VrSession.setEnabled(activity, true)
            with(activity.window.attributes) {
                assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, width)
                assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, height)
                assertEquals(Gravity.FILL, gravity)
                assertEquals(0f, dimAmount, 0f)
            }
            VrSession.setEnabled(activity, false)
            with(activity.window.attributes) {
                assertEquals(360, width)
                assertEquals(240, height)
                assertEquals(Gravity.BOTTOM or Gravity.RIGHT, gravity)
                assertEquals(0.38f, dimAmount, 0f)
            }
        }
    }

    /** Run with PcView in a portrait split-screen region; fullscreen devices skip this case. */
    @Test fun portraitMultiWindowKeeps2dInsteadOfWaitingBehindACurtain() {
        val original = VrSession.settings
        instrumentation.runOnMainSync { VrSession.setEnabled(context, false) }
        val scenario = ActivityScenario.launch<PcView>(
            Intent(context, PcView::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                assumeTrue("This case requires a portrait split-screen window",
                    activity.isInMultiWindowMode &&
                        (activity.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE ||
                            (decor.width > 0 && decor.height >= decor.width)))
                VrSession.setEnabled(activity, true)
                assertFalse("An incompatible split-screen window must remain usable in 2D", VrSession.enabled)
                assertEquals(0, countTheaters(decor))
            }
        } finally {
            instrumentation.runOnMainSync { VrSession.setEnabled(context, false) }
            scenario.close()
            instrumentation.runOnMainSync { VrSession.updateSettings(context, original) }
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

    private fun countTheaters(view: View): Int = when (view) {
        is VrTheaterView -> 1
        is ViewGroup -> (0 until view.childCount).sumOf { countTheaters(view.getChildAt(it)) }
        else -> 0
    }
}
