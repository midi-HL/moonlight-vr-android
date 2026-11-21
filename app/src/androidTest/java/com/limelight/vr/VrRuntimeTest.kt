package com.limelight.vr

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.PcView
import com.limelight.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

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
                        if (width == 640 && row in listOf(0, 15)) save(bitmap, "panel-$language-$row-640.png")
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
        var owner: PcView? = null
        var dialog: AlertDialog? = null
        var confirmedFromRealDpad = false
        var leakedUiMenuKey = false
        var originalDialogAlpha = 1f
        var originalDialogDim = 0f
        fun injectGamepadKey(keyCode: Int, action: Int, downTime: Long) {
            val event = KeyEvent(downTime, SystemClock.uptimeMillis(), action, keyCode,
                0, 0, -1, 0, 0, InputDevice.SOURCE_GAMEPAD)
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
        }
        try {
            scenario.onActivity { activity ->
                owner = activity
                assertTrue("Device must support the bundled Cardboard runtime", VrSession.isSupported())
                VrSession.setEnabled(activity, true)
                assertTrue(VrSession.enabled)
            }
            waitForUiCondition("The real landscape layout did not start its stereo host") {
                owner?.let { countTheaters(it.window.decorView) == 1 } == true
            }
            scenario.onActivity { activity ->
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
                val openedDialog = AlertDialog.Builder(activity).setTitle("VR dialog test")
                    .setMessage("Keep this dialog in both eyes")
                    .setPositiveButton(android.R.string.ok, null).create()
                dialog = openedDialog
                openedDialog.show()
                originalDialogAlpha = openedDialog.window!!.attributes.alpha
                originalDialogDim = openedDialog.window!!.attributes.dimAmount
                openedDialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                    isFocusableInTouchMode = true
                    setOnClickListener { confirmedFromRealDpad = true }
                    requestFocus()
                }
                openedDialog.setOnKeyListener { _, keyCode, _ ->
                    if (keyCode in listOf(KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
                            KeyEvent.KEYCODE_BUTTON_MODE)) leakedUiMenuKey = true
                    false
                }
                VrSession.registerDialog(openedDialog)
            }
            waitForUiCondition("Projected dialog did not retain a real input-focused window") {
                val root = dialog?.window?.decorView
                val alpha = dialog?.window?.attributes?.alpha ?: 0f
                dialog?.isShowing == true && root != null && root.isAttachedToWindow && root.isLaidOut &&
                    root.hasWindowFocus() && dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.hasFocus() == true &&
                    alpha > 0f && alpha <= 0.001f
            }
            val chordTime = SystemClock.uptimeMillis()
            injectGamepadKey(KeyEvent.KEYCODE_BUTTON_START, KeyEvent.ACTION_DOWN, chordTime)
            injectGamepadKey(KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.ACTION_DOWN, chordTime)
            waitForUiCondition("A real menu chord did not open VR controls above the dialog") {
                VrSession.panelOpen
            }
            // Releasing START must not produce its Android DPAD_CENTER fallback behind the panel.
            injectGamepadKey(KeyEvent.KEYCODE_BUTTON_START, KeyEvent.ACTION_UP, chordTime)
            injectGamepadKey(KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.ACTION_UP, chordTime)
            scenario.onActivity {
                assertTrue(VrSession.panelOpen)
                assertFalse("Menu chord keys must not reach native UI fallback handling", leakedUiMenuKey)
                assertFalse("Opening VR controls must not activate the dialog's focused button", confirmedFromRealDpad)
            }
            val closeTime = SystemClock.uptimeMillis()
            injectGamepadKey(KeyEvent.KEYCODE_BUTTON_B, KeyEvent.ACTION_DOWN, closeTime)
            injectGamepadKey(KeyEvent.KEYCODE_BUTTON_B, KeyEvent.ACTION_UP, closeTime)
            waitForUiCondition("B did not close VR controls while keeping the dialog usable") {
                !VrSession.panelOpen && dialog?.isShowing == true && dialog?.window?.decorView?.hasWindowFocus() == true
            }
            // Native input injection runs off the UI thread and goes through InputDispatcher,
            // so a completely transparent NOT_VISIBLE window cannot make this test falsely pass.
            val time = SystemClock.uptimeMillis()
            val down = KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER,
                0, 0, -1, 0, 0, InputDevice.SOURCE_DPAD)
            assertTrue(instrumentation.uiAutomation.injectInputEvent(down, true))
            assertTrue(instrumentation.uiAutomation.injectInputEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP), true))
            waitForUiCondition("A real D-pad key did not reach the projected dialog") {
                confirmedFromRealDpad && dialog?.isShowing == true && dialog?.window?.decorView?.hasWindowFocus() == true
            }
            scenario.onActivity { activity ->
                val openedDialog = requireNotNull(dialog)
                val decor = activity.window.decorView
                assertTrue(VrSession.activeDialogDecors(decor).contains(openedDialog.window!!.decorView))
                assertTrue(openedDialog.window!!.callback is VrActivityHooks)
                VrSession.setEnabled(activity, false)
                assertFalse(VrSession.enabled)
                assertEquals(0, countTheaters(decor))
                assertTrue("Exit VR before dismissing the native dialog", openedDialog.isShowing)
                assertEquals(originalDialogAlpha, openedDialog.window!!.attributes.alpha, 0.000001f)
                assertEquals(originalDialogDim, openedDialog.window!!.attributes.dimAmount, 0.000001f)
                openedDialog.dismiss()
                dialog = null
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertFalse(VrSession.enabled)
                assertEquals(0, countTheaters(activity.window.decorView))
            }
        } finally {
            instrumentation.runOnMainSync {
                dialog?.dismiss()
                VrSession.updateSettings(context, original)
            }
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

    @Test fun pcViewRootRebuildPreservesImmersiveVrWindow() {
        val original = VrSession.settings
        instrumentation.runOnMainSync { VrSession.setEnabled(context, false) }
        val scenario = ActivityScenario.launch<PcView>(Intent(context, PcView::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var owner: PcView? = null
        @Suppress("DEPRECATION")
        val immersiveMask = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        fun settled(): Boolean {
            val activity = owner ?: return false
            val decor = activity.window.decorView
            @Suppress("DEPRECATION")
            val flagsPresent = decor.systemUiVisibility and immersiveMask == immersiveMask
            val barsHidden = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val insets = decor.rootWindowInsets
                insets != null && !insets.isVisible(WindowInsets.Type.statusBars()) &&
                    !insets.isVisible(WindowInsets.Type.navigationBars())
            } else true
            return VrSession.enabled && activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                decor.isAttachedToWindow && decor.isLaidOut && flagsPresent && barsHidden
        }
        try {
            scenario.onActivity { activity ->
                owner = activity
                assertTrue("Device must support the bundled Cardboard runtime", VrSession.isSupported())
                VrSession.setEnabled(activity, true)
            }
            waitForUiCondition("VR did not settle into a landscape immersive window", condition = ::settled)
            scenario.onActivity { activity ->
                val originalRoot = activity.findViewById<View>(R.id.pcViewRootLayout)
                assertNotNull(originalRoot)
                // Exercise the actual PcView -> initializeViews -> UiHelper 2D flag reset path.
                activity.onConfigurationChanged(Configuration(activity.resources.configuration))
                assertNotSame("PcView must really rebuild its normal root", originalRoot,
                    activity.findViewById<View>(R.id.pcViewRootLayout))
            }
            waitForUiCondition("PcView root rebuild discarded VR immersive bars", condition = ::settled)
        } finally {
            instrumentation.runOnMainSync { VrSession.updateSettings(context, original.copy(enabled = false)) }
            scenario.close()
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
            val deadline = SystemClock.uptimeMillis() + 45000L
            var visibleInBothEyes = false
            var lastFrame: Bitmap? = null
            try {
                // UiAutomation screenshot synchronizes with the window manager. Keep it on the
                // instrumentation thread so the main thread can finish layout/rotation/GL startup.
                while (SystemClock.uptimeMillis() < deadline && !visibleInBothEyes) {
                    val captured = instrumentation.uiAutomation.takeScreenshot()
                    val frame = captured?.copy(Bitmap.Config.ARGB_8888, false)
                    captured?.recycle()
                    if (frame != null) {
                        lastFrame?.recycle()
                        lastFrame = frame
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
                    }
                    if (!visibleInBothEyes) SystemClock.sleep(200L)
                }
            } finally {
                lastFrame?.let { frame ->
                    try {
                        // Keep the final failure frame as well as the success frame for diagnosis.
                        save(frame, "stereo-controls-extreme-scene.png")
                    } finally {
                        frame.recycle()
                    }
                }
            }
            assertTrue("The head-relative controls must stay visible in both eyes", visibleInBothEyes)
        } finally {
            instrumentation.runOnMainSync { VrSession.updateSettings(context, original.copy(enabled = false)) }
            scenario.close()
        }
    }

    private fun waitForUiCondition(message: String, timeoutMs: Long = 15000L, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            // Only the test thread waits; the main loop remains free to attach and lay out windows.
            SystemClock.sleep(50L)
        }
        fail(message)
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
