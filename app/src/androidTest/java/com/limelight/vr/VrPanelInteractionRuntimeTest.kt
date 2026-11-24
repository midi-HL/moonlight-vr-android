package com.limelight.vr

import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.widget.Button
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.Game
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual panel key handling, rather than direct writes to VrSettingsStore. */
@RunWith(AndroidJUnit4::class)
class VrPanelInteractionRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun fixture(block: (ComponentActivity) -> Unit) {
        val context = instrumentation.targetContext
        val original = VrSession.settings
        instrumentation.runOnMainSync { VrSession.setEnabled(context, false) }
        val scenario = ActivityScenario.launch<ComponentActivity>(Intent(context, ComponentActivity::class.java))
        try {
            scenario.onActivity { activity ->
                VrSession.updateSettings(activity, VrSettings.DEFAULT.copy(enabled = true))
            }
            // Enabling VR can recreate ComponentActivity for landscape. Install test UI
            // only after that transition; an old instance's content is not the oracle.
            val deadline = SystemClock.uptimeMillis() + 15000
            var ready = false
            while (!ready && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { ready = it.resources.configuration.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
                    it.window.decorView.width > it.window.decorView.height && it.hasWindowFocus() }
                if (!ready) SystemClock.sleep(25)
            }
            assertTrue("Test Activity must settle in focused landscape before installing controls", ready)
            scenario.onActivity { activity ->
                activity.setContentView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(Button(activity).apply { text = "First"; tag = "first-controller-button" })
                    addView(Button(activity).apply { text = "Second" })
                })
                block(activity)
            }
        } finally {
            instrumentation.runOnMainSync { VrSession.updateSettings(context, original.copy(enabled = false)) }
            scenario.close()
        }
    }

    private fun press(activity: ComponentActivity, key: Int) {
        val time = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val event = KeyEvent(time, SystemClock.uptimeMillis(), action, key, 0, 0, -1, 0, 0,
                InputDevice.SOURCE_GAMEPAD)
            assertTrue(activity.window.callback.dispatchKeyEvent(event))
        }
    }

    @Test fun selectedRangeAdjustsImmediatelyWithDpadAndPersists() = fixture { activity ->
        VrSession.setPanelOpen(true)
        for (key in listOf(VrSettingsStore.K_SCALE, VrSettingsStore.K_DISTANCE,
            VrSettingsStore.K_CURVATURE, VrSettingsStore.K_IPD, VrSettingsStore.K_ZOOM)) {
            val index = VrQuickPanel.rows.indexOfFirst { it.key == key }
            VrSession.setSelectedIndex(index)
            val before = VrQuickPanel.valueOf(VrSession.settings, key)
            press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
            val after = VrQuickPanel.valueOf(VrSession.settings, key)
            assertTrue("Selected range $key must adjust on RIGHT without an extra A", after > before)
            assertEquals(after, VrQuickPanel.valueOf(VrSettingsStore.load(activity), key), 0.000001f)
            press(activity, KeyEvent.KEYCODE_DPAD_LEFT)
            assertEquals(before, VrQuickPanel.valueOf(VrSession.settings, key), 0.000001f)
            assertEquals(index, VrSession.selectedIndex)
            assertTrue(VrSession.panelOpen)
        }
    }

    @Test fun editDpadUpDownAdjustsWithoutLeavingSelectedRange() = fixture { activity ->
        VrSession.setPanelOpen(true)
        VrSession.setSelectedIndex(1)
        press(activity, KeyEvent.KEYCODE_BUTTON_A)
        assertTrue(VrSession.editing)
        val before = VrSession.settings.screenScale
        press(activity, KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("UP in edit mode must keep the selected range", 1, VrSession.selectedIndex)
        assertTrue(VrSession.settings.screenScale < before)
        assertTrue(VrSession.editing)
        press(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(before, VrSession.settings.screenScale, 0.000001f)
        press(activity, KeyEvent.KEYCODE_BUTTON_B)
        assertFalse(VrSession.editing)
        assertTrue(VrSession.panelOpen)
    }

    @Test fun legacyControllerBackCompletesStreamMenuChord() {
        val context = instrumentation.targetContext
        val original = VrSession.settings
        instrumentation.runOnMainSync { VrSession.setEnabled(context, false) }
        val scenario = ActivityScenario.launch<ComponentActivity>(Intent(context, ComponentActivity::class.java))
        try {
            scenario.onActivity { activity ->
                VrSession.updateSettings(activity, VrSettings.DEFAULT.copy(enabled = true))
                VrSession.setPanelOpen(false)
            }
            val deadline = SystemClock.uptimeMillis() + 15000
            var ready = false
            while (!ready && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { ready = it.resources.configuration.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE && it.hasWindowFocus() }
                if (!ready) SystemClock.sleep(25)
            }
            assertTrue("Menu chord must start after the window has completed rotation", ready)
            scenario.onActivity {
                // Exercise the Game branch of the public router without starting a network
                // session; host packet handling is covered by VrHostInputRuntimeTest.
                val game = Game()
                val time = SystemClock.uptimeMillis()
                for (key in listOf(KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BACK)) {
                    val event = KeyEvent(time, time, KeyEvent.ACTION_DOWN, key, 0, 0, -1, 0, 0,
                        InputDevice.SOURCE_GAMEPAD)
                    VrInputDispatcher.dispatchKey(game, event, false) { true }
                }
            }
            SystemClock.sleep(VrKeys.MENU_LONG_PRESS_MS + 250)
            scenario.onActivity {
                assertTrue("START plus gamepad BACK-as-SELECT must open stream VR controls", VrSession.panelOpen)
            }
        } finally {
            instrumentation.runOnMainSync { VrSession.updateSettings(context, original.copy(enabled = false)) }
            scenario.close()
        }
    }

    @Test fun firstConfirmAcquiresOrdinaryButtonFocusWithoutXOrY() {
        instrumentation.setInTouchMode(true)
        try {
            fixture { activity ->
                VrSession.setPanelOpen(false)
                val root = activity.window.decorView
                root.clearFocus()
                var clicks = 0
                val button = requireNotNull(root.findViewWithTag<Button>("first-controller-button"))
                button.setOnClickListener { clicks++ }
                press(activity, KeyEvent.KEYCODE_BUTTON_A)
                assertTrue("A must establish a native UI focus without a preparatory X/Y", button.hasFocus())
                assertEquals("The first complete A press must activate its focused control once", 1, clicks)
            }
        } finally {
            instrumentation.setInTouchMode(false)
        }
    }
}
