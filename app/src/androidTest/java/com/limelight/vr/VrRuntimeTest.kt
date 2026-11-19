package com.limelight.vr

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.PcView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class VrRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun panelDrawsEverySelectionInBothLanguagesAndSizes() {
        instrumentation.runOnMainSync {
            for (language in listOf("en", "zh")) {
                val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                    setLocale(Locale(language))
                })
                val view = VrPanelView(localized)
                for ((width, height) in listOf(1024 to 576, 640 to 360)) {
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    view.layoutFor(width, height)
                    for (row in VrQuickPanel.rows.indices) {
                        bitmap.eraseColor(0)
                        view.drawInto(Canvas(bitmap), VrSettings.DEFAULT, row, row % 2 == 0)
                        assertEquals("Outside padding must remain transparent", 0, bitmap.getPixel(0, 0))
                        assertTrue("Controls must be opaque and visible", bitmap.getPixel(width / 2, height / 2) ushr 24 > 240)
                        if (width == 1024 && row in listOf(0, 4, 12, 15)) save(bitmap, "panel-$language-$row.png")
                    }
                    bitmap.recycle()
                }
            }
        }
    }

    @Test fun stereoLifecycleDialogAndKeyDispatchStayUsable() {
        val original = VrSession.settings
        instrumentation.runOnMainSync { VrSession.setEnabled(context, false) }
        val launch = Intent(context, PcView::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val scenario = ActivityScenario.launch<PcView>(launch)
        try {
            scenario.onActivity { activity ->
                assertTrue("Device must support the bundled Cardboard runtime", VrSession.isSupported())
                VrSession.setEnabled(activity, true)
                assertTrue(VrSession.enabled)
                val decor = activity.window.decorView
                assertEquals(1, countTheaters(decor))
                val time = SystemClock.uptimeMillis()
                for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                    activity.window.callback.dispatchKeyEvent(KeyEvent(time, time, action,
                        KeyEvent.KEYCODE_DPAD_DOWN, 0, 0, 9, 0, 0, InputDevice.SOURCE_DPAD))
                }
                VrSession.setPanelOpen(true)
                VrSession.setSelectedIndex(1)
                assertTrue(VrSession.panelOpen)
                VrSession.setPanelOpen(false)
                val dialog = AlertDialog.Builder(activity).setTitle("VR dialog test")
                    .setMessage("Keep this dialog in both eyes")
                    .setPositiveButton(android.R.string.ok, null).create()
                dialog.show()
                VrSession.registerDialog(dialog)
                assertTrue(VrSession.activeDialogDecors(decor).contains(dialog.window!!.decorView))
                assertTrue(dialog.window!!.callback is VrActivityHooks)
                dialog.dismiss()
                VrSession.setEnabled(activity, false)
                assertFalse(VrSession.enabled)
                assertEquals(0, countTheaters(decor))
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertFalse(VrSession.enabled)
                assertEquals(0, countTheaters(activity.window.decorView))
            }
        } finally {
            instrumentation.runOnMainSync { VrSession.updateSettings(context, original) }
            scenario.close()
        }
    }

    @Test fun controllerStepsKeepTheirExactPersistedMilliUnits() {
        val original = VrSettingsStore.load(context)
        try {
            for (steps in listOf(27, 30, 33, 36, 40, 43, 46, 49)) {
                val expected = steps * 0.02f
                VrSettingsStore.save(context, VrSettings.DEFAULT.copy(ipdScale = expected))
                assertEquals(expected, VrSettingsStore.load(context).ipdScale, 0.000001f)
            }
            for (steps in listOf(9, 10, 15, 18, 20, 23, 27)) {
                val expected = steps * 0.01f
                VrSettingsStore.save(context, VrSettings.DEFAULT.copy(gamepadDeadZone = expected))
                assertEquals(expected, VrSettingsStore.load(context).gamepadDeadZone, 0.000001f)
            }
        } finally {
            VrSettingsStore.save(context, original)
        }
    }

    @Test fun quickControlsRenderInBothEyesAtExtremeSceneSettings() {
        val original = VrSession.settings
        instrumentation.runOnMainSync { VrSession.setEnabled(context, false) }
        val scenario = ActivityScenario.launch<PcView>(Intent(context, PcView::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            scenario.onActivity { activity ->
                VrSession.setEnabled(activity, true)
                VrSession.updateSettings(activity, VrSession.settings.copy(
                    headTrackingEnabled = false, screenScale = 0.5f, screenDistanceMeters = 5f,
                    brightness = 0.3f, curvature = 1f, zoomScale = 2.5f))
                VrSession.setPanelOpen(true)
                VrSession.setSelectedIndex(2)
            }
            val complete = CountDownLatch(1)
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val deadline = SystemClock.uptimeMillis() + 15000L
            var visibleInBothEyes = false
            val frameCheck = object : Runnable {
                override fun run() {
                    val captured = instrumentation.uiAutomation.takeScreenshot()
                    val frame = captured?.copy(Bitmap.Config.ARGB_8888, false)
                    captured?.recycle()
                    if (frame != null) {
                        var left = 0
                        var right = 0
                        for (y in 0 until frame.height step 2) for (x in 0 until frame.width step 2) {
                            val pixel = frame.getPixel(x, y)
                            val red = android.graphics.Color.red(pixel)
                            val green = android.graphics.Color.green(pixel)
                            val blue = android.graphics.Color.blue(pixel)
                            if (red in 125..160 && green in 215..250 && blue in 190..230) {
                                if (x < frame.width / 2) left++ else right++
                            }
                        }
                        visibleInBothEyes = frame.width > frame.height && left > 20 && right > 20
                        if (visibleInBothEyes || SystemClock.uptimeMillis() >= deadline) {
                            save(frame, "stereo-controls-extreme-scene.png")
                            frame.recycle()
                            complete.countDown()
                            return
                        }
                        frame.recycle()
                    }
                    if (SystemClock.uptimeMillis() >= deadline) complete.countDown()
                    else handler.postDelayed(this, 200L)
                }
            }
            handler.post(frameCheck)
            assertTrue("Timed out waiting for a rendered stereo frame", complete.await(20, TimeUnit.SECONDS))
            handler.removeCallbacks(frameCheck)
            assertTrue("The head-relative controls must stay visible in both eyes", visibleInBothEyes)
        } finally {
            instrumentation.runOnMainSync { VrSession.updateSettings(context, original.copy(enabled = false)) }
            scenario.close()
        }
    }

    private fun countTheaters(view: View): Int {
        if (view is VrTheaterView) return 1
        if (view !is ViewGroup) return 0
        return (0 until view.childCount).sumOf { countTheaters(view.getChildAt(it)) }
    }

    private fun save(bitmap: Bitmap, name: String) {
        val directory = File(context.getExternalFilesDir(null), "vr-review").apply { mkdirs() }
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
