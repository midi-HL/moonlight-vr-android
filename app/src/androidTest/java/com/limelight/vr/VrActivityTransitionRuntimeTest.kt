package com.limelight.vr

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.limelight.PcView
import com.limelight.preferences.StreamSettings
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Real Activity navigation, including the window contract before the target can resume/draw. */
@RunWith(AndroidJUnit4::class)
class VrActivityTransitionRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val frames = ArrayList<String>()

    @Test fun settingsStartsInLandscapeVrAndReturnsWithoutManualRecenter() {
        val original = VrSession.settings
        val application = context.applicationContext as Application
        val lifecycle = TransitionLifecycle()
        var scenario: ActivityScenario<PcView>? = null
        var source: PcView? = null
        instrumentation.runOnMainSync {
            VrSession.setEnabled(context, false)
            application.registerActivityLifecycleCallbacks(lifecycle)
        }
        try {
            val launched = ActivityScenario.launch<PcView>(
                Intent(context, PcView::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            scenario = launched
            launched.onActivity { activity ->
                source = activity
                assertTrue("Device must support the bundled Cardboard runtime", VrSession.isSupported())
                VrSession.updateSettings(activity, VrSettings.DEFAULT.copy(
                    enabled = true, headTrackingEnabled = true, recenterOnEnter = true,
                    screenScale = 1f, screenDistanceMeters = 2f, curvature = 0f,
                    brightness = 1f, zoomScale = 1f))
                installMarker(activity, SOURCE_MARKER)
            }
            awaitStereoScene("activity-source-vr", SOURCE_MARKER) {
                source?.let(::isSettledVrActivity) == true
            }

            // Use the same Activity launch route as the real settings button. No direct lifecycle calls.
            launched.onActivity { activity ->
                lifecycle.events.add("${SystemClock.uptimeMillis()} startActivity StreamSettings")
                activity.startActivity(Intent(activity, StreamSettings::class.java))
            }
            var markerAdded = false
            awaitStereoScene("activity-settings-vr", SETTINGS_MARKER) {
                val settings = lifecycle.settingsActivity
                if (settings != null && isResumed(settings) && !markerAdded) {
                    installMarker(settings, SETTINGS_MARKER)
                    markerAdded = true
                }
                settings != null && isSettledVrActivity(settings)
            }
            instrumentation.runOnMainSync {
                val settings = lifecycle.settingsActivity
                assertNotNull("The real settings Activity must have been created", settings)
                assertTrue("Opening settings must pause the original source Activity",
                    source?.let(::isResumed) == false)
                assertTrue("Settings must own the real focused input window before B",
                    settings!!.window.decorView.hasWindowFocus())
                lifecycle.events.add("${SystemClock.uptimeMillis()} inject system gamepad B")
            }
            // Traverse InputDispatcher and the real Settings Window.Callback. Calling finish()
            // directly would miss the global VR Back callback that caused the user regression.
            val backTime = SystemClock.uptimeMillis()
            val back = KeyEvent(backTime, backTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B,
                0, 0, -1, 0, 0, InputDevice.SOURCE_GAMEPAD)
            assertTrue(instrumentation.uiAutomation.injectInputEvent(back, true))
            assertTrue(instrumentation.uiAutomation.injectInputEvent(KeyEvent.changeAction(back, KeyEvent.ACTION_UP), true))
            awaitStereoScene("activity-returned-vr", SOURCE_MARKER) {
                source?.let(::isSettledVrActivity) == true
            }

            // Do this after capturing both directions, so an old APK leaves useful screenshots and
            // a complete lifecycle trace. Waiting for eventual landscape alone misses the portrait flash.
            instrumentation.runOnMainSync {
                assertEquals("Settings must request VR landscape by onStart, before resume or GL startup",
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
                    lifecycle.firstSettingsStartedOrientation ?: Int.MIN_VALUE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    assertEquals("Settings must already request VR landscape at onActivityPreResumed",
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
                        lifecycle.firstSettingsPreResumedOrientation ?: Int.MIN_VALUE)
                }
                assertTrue("The same PcView must resume after settings closes", lifecycle.sourceResumes >= 2)
                assertTrue("A real B return must keep VR enabled", VrSession.enabled)
                assertEquals("Settings must inflate in inherited landscape before creating its layout",
                    Configuration.ORIENTATION_LANDSCAPE, lifecycle.firstSettingsCreatedConfiguration)
                assertEquals("Entering settings in VR must not force a portrait-layout recreation",
                    1, lifecycle.settingsCreates)
                assertTrue("Every visible paused source must retain its stereo cover until stop",
                    lifecycle.sourcePausedTheaters.isNotEmpty() && lifecycle.sourcePausedTheaters.all { it == 1 })
                assertEquals("Returning to PcView must leave only one stereo host",
                    1, countTheaters(requireNotNull(source).window.decorView))
            }
        } finally {
            val lifecycleEvents = ArrayList<String>()
            try {
                instrumentation.runOnMainSync {
                    VrSession.setEnabled(context, false)
                    // Public test lifecycle API also finds a target paused during a failed assertion.
                    val monitor = ActivityLifecycleMonitorRegistry.getInstance()
                    val settings = listOf(Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED)
                        .flatMap { monitor.getActivitiesInStage(it).toList() }
                        .filterIsInstance<StreamSettings>().distinct()
                    settings.forEach { if (!it.isFinishing && !it.isDestroyed) it.finish() }
                    source?.window?.decorView?.findViewWithTag<View>(MARKER_TAG)?.let { marker ->
                        (marker.parent as? ViewGroup)?.removeView(marker)
                    }
                    application.unregisterActivityLifecycleCallbacks(lifecycle)
                    lifecycleEvents.addAll(lifecycle.events)
                }
            } finally {
                try { scenario?.close() } finally {
                    instrumentation.runOnMainSync { VrSession.updateSettings(context, original) }
                    val directory = File(context.getExternalFilesDir(null), "vr-review").apply { mkdirs() }
                    File(directory, "activity-transition-lifecycle.txt").writeText(
                        (lifecycleEvents + frames).joinToString("\n", postfix = "\n"))
                }
            }
        }
    }

    private inner class TransitionLifecycle : Application.ActivityLifecycleCallbacks {
        val events = ArrayList<String>()
        var settingsActivity: StreamSettings? = null
        var firstSettingsStartedOrientation: Int? = null
        var firstSettingsPreResumedOrientation: Int? = null
        var sourceResumes = 0
        var settingsCreates = 0
        var firstSettingsCreatedConfiguration: Int? = null
        val sourcePausedTheaters = ArrayList<Int>()

        private fun record(stage: String, activity: Activity) {
            if (activity !is PcView && activity !is StreamSettings) return
            events.add("${SystemClock.uptimeMillis()} $stage ${activity.javaClass.simpleName}" +
                "@${System.identityHashCode(activity)} requested=${activity.requestedOrientation}" +
                " config=${activity.resources.configuration.orientation}" +
                " theaters=${countTheaters(activity.window.decorView)}")
        }

        override fun onActivityCreated(activity: Activity, state: Bundle?) {
            if (activity is StreamSettings) {
                settingsActivity = activity
                settingsCreates++
                if (firstSettingsCreatedConfiguration == null) {
                    firstSettingsCreatedConfiguration = activity.resources.configuration.orientation
                }
            }
            record("created", activity)
        }
        override fun onActivityStarted(activity: Activity) {
            if (activity is StreamSettings && firstSettingsStartedOrientation == null) {
                firstSettingsStartedOrientation = activity.requestedOrientation
            }
            record("started", activity)
        }
        override fun onActivityPreResumed(activity: Activity) {
            if (activity is StreamSettings && firstSettingsPreResumedOrientation == null) {
                firstSettingsPreResumedOrientation = activity.requestedOrientation
            }
            record("preResumed", activity)
        }
        override fun onActivityResumed(activity: Activity) {
            if (activity is PcView) sourceResumes++
            record("resumed", activity)
        }
        override fun onActivityPaused(activity: Activity) {
            if (activity is PcView) sourcePausedTheaters.add(countTheaters(activity.window.decorView))
            record("paused", activity)
        }
        override fun onActivityStopped(activity: Activity) = record("stopped", activity)
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = record("destroyed", activity)
    }

    private fun isResumed(activity: Activity): Boolean = ActivityLifecycleMonitorRegistry.getInstance()
        .getActivitiesInStage(Stage.RESUMED).any { it === activity }

    private fun isSettledVrActivity(activity: Activity): Boolean {
        val decor = activity.window.decorView
        return VrSession.enabled && isResumed(activity) && !activity.isFinishing && !activity.isDestroyed &&
            activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE &&
            activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            decor.isAttachedToWindow && decor.isLaidOut && decor.width > decor.height && countTheaters(decor) == 1
    }

    private fun installMarker(activity: Activity, color: Int) {
        val decor = activity.window.decorView as ViewGroup
        decor.findViewWithTag<View>(MARKER_TAG)?.let { return }
        // A normal View is captured into the world-space screen behind Cardboard. A head-locked
        // quick panel could remain visible while the actual screen is outside the field of view.
        val marker = View(activity).apply { tag = MARKER_TAG; setBackgroundColor(color) }
        decor.addView(marker, FrameLayout.LayoutParams(maxOf(96, decor.width / 8),
            maxOf(48, decor.height / 10), Gravity.CENTER))
    }

    private fun awaitStereoScene(name: String, markerColor: Int, ready: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30000L
        val directory = File(context.getExternalFilesDir(null), "vr-review").apply { mkdirs() }
        var attempt = 0
        while (SystemClock.uptimeMillis() < deadline) {
            var activityReady = false
            instrumentation.runOnMainSync { activityReady = ready() }
            val captured = instrumentation.uiAutomation.takeScreenshot()
            val frame = captured?.copy(Bitmap.Config.ARGB_8888, false)
            captured?.recycle()
            if (frame != null) {
                try {
                    var left = 0
                    var right = 0
                    var leftX = 0L
                    var rightX = 0L
                    var leftY = 0L
                    var rightY = 0L
                    for (y in 0 until frame.height step 2) for (x in 0 until frame.width step 2) {
                        val pixel = frame.getPixel(x, y)
                        val matches = if (markerColor == SOURCE_MARKER) {
                            Color.red(pixel) > 190 && Color.green(pixel) < 70 && Color.blue(pixel) > 150
                        } else {
                            Color.red(pixel) in 100..155 && Color.green(pixel) > 210 && Color.blue(pixel) < 60
                        }
                        if (matches) {
                            if (x < frame.width / 2) { left++; leftX += x; leftY += y }
                            else { right++; rightX += x; rightY += y }
                        }
                    }
                    val lx = leftX.toDouble() / maxOf(1, left)
                    val rx = rightX.toDouble() / maxOf(1, right)
                    val ly = leftY.toDouble() / maxOf(1, left)
                    val ry = rightY.toDouble() / maxOf(1, right)
                    // One ordinary marker crossing the midpoint can fill both half-counters.
                    // Only two separated eye copies establish that GL stereo is really visible.
                    val separated = rx - lx > frame.width * 0.25 &&
                        kotlin.math.abs(lx / frame.width - 0.25) < 0.18 &&
                        kotlin.math.abs(rx / frame.width - 0.75) < 0.18 &&
                        kotlin.math.abs(ly / frame.height - 0.5) < 0.25 &&
                        kotlin.math.abs(ry / frame.height - 0.5) < 0.25
                    val visible = activityReady && frame.width > frame.height && left > 12 && right > 12 && separated
                    frames.add("sceneCentroids " + name + " left=" + lx + "," + ly +
                        " right=" + rx + "," + ry + " separated=" + separated)
                    frames.add("${SystemClock.uptimeMillis()} $name frame=$attempt ready=$activityReady" +
                        " size=${frame.width}x${frame.height} markerLeft=$left markerRight=$right visible=$visible")
                    if (attempt < 12) {
                        val previewWidth = minOf(640, frame.width)
                        val preview = Bitmap.createScaledBitmap(frame, previewWidth,
                            (frame.height * previewWidth.toFloat() / frame.width).toInt().coerceAtLeast(1), true)
                        try { save(preview, File(directory, "$name-${attempt.toString().padStart(2, '0')}.png")) }
                        finally { if (preview !== frame) preview.recycle() }
                    }
                    if (visible) {
                        save(frame, File(directory, "$name-final.png"))
                        return
                    }
                    if (SystemClock.uptimeMillis() + 200L >= deadline) {
                        save(frame, File(directory, "$name-failure.png"))
                    }
                } finally { frame.recycle() }
            }
            attempt++
            // Bounded screenshot samples on the instrumentation thread leave the UI/GL loop free.
            SystemClock.sleep(150L)
        }
        fail("$name did not show its captured scene marker in both eyes without manual recenter")
    }

    private fun save(bitmap: Bitmap, file: File) {
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun countTheaters(view: View): Int {
        if (view is VrTheaterView) return 1
        if (view !is ViewGroup) return 0
        return (0 until view.childCount).sumOf { countTheaters(view.getChildAt(it)) }
    }

    private companion object {
        const val MARKER_TAG = "vr-activity-transition-marker"
        val SOURCE_MARKER = Color.rgb(230, 10, 200)
        val SETTINGS_MARKER = Color.rgb(130, 250, 10)
    }
}
