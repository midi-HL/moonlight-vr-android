package com.limelight.vr

import android.content.Intent
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.os.Bundle
import android.os.SystemClock
import android.graphics.Canvas
import android.util.Log
import android.view.KeyEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.limelight.PcView
import com.limelight.R
import com.limelight.preferences.StreamSettings
import java.io.File
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

    @Test fun normal2dSettingsRestoreAutomaticRotationAfterAPortraitSource() = verify2dSettingsAgainstDefaultPolicy(false)

    @Test fun fixedLandscape2dSourceDoesNotLockSettingsOrientation() = verify2dSettingsAgainstDefaultPolicy(true)

    private fun verify2dSettingsAgainstDefaultPolicy(landscapeSource: Boolean) {
        val original = VrSession.settings
        val application = context.applicationContext as Application
        val sourceOrientation = if (landscapeSource) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        val sourceRequest = if (landscapeSource) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val direction = if (landscapeSource) "landscape" else "portrait"
        val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, "vr-review").apply { mkdirs() }
        val traceFile = File(directory, "2d-default-policy-$direction.txt")
        val trace = ArrayList<String>()
        val stages = HashMap<Activity, String>()
        val controlInstances = ArrayList<ComponentActivity>()
        val targetInstances = ArrayList<StreamSettings>()
        var source: PcView? = null
        var control: ComponentActivity? = null
        var target: StreamSettings? = null
        var controlOrientation = Configuration.ORIENTATION_UNDEFINED
        var currentStage = "setup"
        fun identity(activity: Activity?): String = activity?.let {
            "${it.javaClass.name}@${Integer.toHexString(System.identityHashCode(it))}/task=${it.taskId}"
        } ?: "none"
        fun isResumed(activity: Activity): Boolean = ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED).any { it === activity }
        @Suppress("DEPRECATION")
        fun describe(label: String, activity: Activity?): String {
            if (activity == null) return "$label=none"
            val decor = activity.window.decorView
            return "$label=${identity(activity)} lifecycle=${stages[activity]}" +
                " req=${activity.requestedOrientation} cfg=${activity.resources.configuration.orientation}" +
                " rotation=${activity.windowManager.defaultDisplay.rotation} bounds=${decor.width}x${decor.height}" +
                " attached=${decor.isAttachedToWindow} laidOut=${decor.isLaidOut} layoutPending=${decor.isLayoutRequested}" +
                " focus=${decor.hasWindowFocus()} resumed=${isResumed(activity)} owner=${VrSession.isInputOwnerResumed(activity)}" +
                " finishing=${activity.isFinishing} destroyed=${activity.isDestroyed}"
        }
        fun snapshot(): String = "vr=${VrSession.enabled} expectedControlCfg=$controlOrientation " +
            describe("source", source) + " | " + describe("control", control) + " | " + describe("target", target)
        fun record(event: String, state: String = snapshot()) {
            val entry = "${SystemClock.uptimeMillis()} [$direction/$currentStage] $event $state"
            trace.add(entry)
            Log.i("Vr2dPolicyTest", entry)
        }
        fun settled(activity: Activity?): Boolean {
            if (activity == null || activity.isFinishing || activity.isDestroyed || !isResumed(activity) ||
                !VrSession.isInputOwnerResumed(activity)) return false
            val decor = activity.window.decorView
            val configuration = activity.resources.configuration.orientation
            return decor.isAttachedToWindow && decor.isLaidOut && !decor.isLayoutRequested && decor.hasWindowFocus() &&
                decor.width > 0 && decor.height > 0 && decor.width != decor.height &&
                configuration == if (decor.width > decor.height) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        }
        fun awaitPolicy(label: String, diagnostics: () -> String, condition: () -> Boolean) {
            currentStage = label
            val deadline = SystemClock.uptimeMillis() + 20000L
            var lastState = ""
            while (SystemClock.uptimeMillis() < deadline) {
                var ready = false
                instrumentation.runOnMainSync {
                    val state = diagnostics() + " | " + snapshot()
                    if (state != lastState) { record("waiting", state); lastState = state }
                    ready = condition()
                    if (ready) record("ready", state)
                }
                if (ready) return
                SystemClock.sleep(40L)
            }
            throw AssertionError("2D default-policy stage '$label' timed out; $lastState; trace=${traceFile.absolutePath}")
        }
        val observer = object : Application.ActivityLifecycleCallbacks {
            private fun changed(activity: Activity, stage: String) {
                if (activity !is PcView && activity !is StreamSettings && activity.javaClass != ComponentActivity::class.java) return
                stages[activity] = stage
                record("lifecycle=$stage eventActivity=${identity(activity)}")
            }
            override fun onActivityPreCreated(activity: Activity, state: Bundle?) = changed(activity, "preCreated")
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                if (activity.javaClass == ComponentActivity::class.java) {
                    val createdControl = activity as ComponentActivity
                    control = createdControl
                    controlInstances.add(createdControl)
                    // The library's debug Activity has an original UNSPECIFIED manifest entry.
                    // Give it plain measurable content without changing its orientation policy.
                    createdControl.setContentView(View(createdControl))
                }
                if (activity is StreamSettings) { target = activity; targetInstances.add(activity) }
                changed(activity, "created")
            }
            override fun onActivityResumed(activity: Activity) = changed(activity, "resumed")
            override fun onActivityStarted(activity: Activity) = changed(activity, "started")
            override fun onActivityPaused(activity: Activity) = changed(activity, "paused")
            override fun onActivityStopped(activity: Activity) = changed(activity, "stopped")
            override fun onActivityDestroyed(activity: Activity) = changed(activity, "destroyed")
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        }
        var scenario: ActivityScenario<PcView>? = null
        instrumentation.runOnMainSync {
            VrSession.setEnabled(context, false)
            application.registerActivityLifecycleCallbacks(observer)
            record("observer registered")
        }
        try {
            val launched = ActivityScenario.launch<PcView>(
                Intent(context, PcView::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            scenario = launched
            launched.onActivity { activity ->
                source = activity
                currentStage = "fix-source"
                activity.requestedOrientation = sourceRequest
                record("requested fixed source orientation")
            }
            awaitPolicy("fixed-source-before-control", {
                "settled=${settled(source)} fixedRequest=${source?.requestedOrientation == sourceRequest}" +
                    " fixedConfig=${source?.resources?.configuration?.orientation == sourceOrientation}"
            }) { settled(source) && source?.requestedOrientation == sourceRequest &&
                source?.resources?.configuration?.orientation == sourceOrientation }
            launched.onActivity { activity ->
                @Suppress("DEPRECATION")
                val info = context.packageManager.getActivityInfo(ComponentName(context, ComponentActivity::class.java), 0)
                assertEquals("Control must use its original default orientation policy",
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, info.screenOrientation)
                currentStage = "launch-control"
                record("launching original UNSPECIFIED control from fixed source")
                activity.startActivity(Intent(activity, ComponentActivity::class.java))
            }
            awaitPolicy("control-default-policy", {
                "settled=${settled(control)} defaultRequest=${control?.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED}"
            }) { settled(control) && control?.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            instrumentation.runOnMainSync {
                val actualControl = requireNotNull(control)
                assertEquals("Control and Settings must share the fixed source's task context",
                    requireNotNull(source).taskId, actualControl.taskId)
                controlOrientation = actualControl.resources.configuration.orientation
                record("recorded independent default-policy orientation=$controlOrientation")
                actualControl.finish()
            }
            awaitPolicy("same-fixed-source-after-control", {
                "settled=${settled(source)} fixedRequest=${source?.requestedOrientation == sourceRequest}" +
                    " fixedConfig=${source?.resources?.configuration?.orientation == sourceOrientation}"
            }) { settled(source) && source?.requestedOrientation == sourceRequest &&
                source?.resources?.configuration?.orientation == sourceOrientation }
            launched.onActivity { activity ->
                assertSame("Both destination launches must use the same source Activity", source, activity)
                currentStage = "launch-settings"
                record("launching Settings from the same fixed source")
                activity.startActivity(Intent(activity, StreamSettings::class.java))
            }
            awaitPolicy("settings-matches-independent-control", {
                "resumed=${target?.let(::isResumed)} settled=${settled(target)}" +
                    " defaultRequest=${target?.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED}" +
                    " matchesControl=${target?.resources?.configuration?.orientation == controlOrientation}"
            }) { settled(target) && target?.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED &&
                target?.resources?.configuration?.orientation == controlOrientation }
            instrumentation.runOnMainSync {
                assertFalse(VrSession.enabled)
                assertEquals(requireNotNull(source).taskId, requireNotNull(target).taskId)
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, requireNotNull(target).requestedOrientation)
                assertEquals("Settings must resolve orientation like the original UNSPECIFIED control in this task",
                    controlOrientation, requireNotNull(target).resources.configuration.orientation)
                assertEquals(0, countTheaters(requireNotNull(target).window.decorView))
                record("all default-policy assertions passed")
            }
        } finally {
            try {
                instrumentation.runOnMainSync {
                    currentStage = "cleanup"
                    record("finishing control/Settings instances")
                    (targetInstances + controlInstances).forEach { if (!it.isDestroyed && !it.isFinishing) it.finish() }
                    application.unregisterActivityLifecycleCallbacks(observer)
                }
            } finally {
                try { scenario?.close() } finally {
                    instrumentation.runOnMainSync {
                        VrSession.updateSettings(context, original)
                        record("original VR settings restored")
                    }
                    traceFile.writeText(trace.joinToString("\n", postfix = "\n"))
                }
            }
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
