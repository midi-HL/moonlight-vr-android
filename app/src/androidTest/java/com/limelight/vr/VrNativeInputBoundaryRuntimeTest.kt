package com.limelight.vr

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.HelpActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the installed Window.Callback, including native handlers with DOWN-only actions. */
@RunWith(AndroidJUnit4::class)
class VrNativeInputBoundaryRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun canceledAndOrphanConfirmReleaseNeverActivatesNativeUi() = withWindow { activity, button ->
        var activations = 0
        button.setOnKeyListener { _, key, event ->
            if (key == KeyEvent.KEYCODE_BUTTON_A && event.action == KeyEvent.ACTION_UP) {
                activations++
                true
            } else false
        }
        val callback = activity.window.callback
        val now = SystemClock.uptimeMillis()
        callback.dispatchKeyEvent(key(now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A))
        assertEquals("A release delivered to a new window must not confirm", 0, activations)
        callback.dispatchKeyEvent(key(now + 1, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A))
        callback.dispatchKeyEvent(KeyEvent.changeFlags(key(now + 1, KeyEvent.ACTION_UP,
            KeyEvent.KEYCODE_BUTTON_A), KeyEvent.FLAG_CANCELED))
        assertEquals("A canceled press must not activate a custom native UP handler", 0, activations)
    }

    @Test fun repeatedConfirmDownIsDeliveredOnceAndReleaseKeepsItsOriginalMapping() = withWindow { activity, button ->
        var presses = 0
        var releases = 0
        button.setOnKeyListener { _, key, event ->
            if (key == KeyEvent.KEYCODE_BUTTON_A) {
                if (event.action == KeyEvent.ACTION_DOWN) presses++ else releases++
                true
            } else false
        }
        val callback = activity.window.callback
        val now = SystemClock.uptimeMillis()
        callback.dispatchKeyEvent(key(now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A))
        callback.dispatchKeyEvent(KeyEvent.changeTimeRepeat(key(now, KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_BUTTON_A), now + 20, 1))
        callback.dispatchKeyEvent(key(now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A))
        assertEquals("OS repeat must not repeat a native confirm action", 1, presses)
        assertEquals(1, releases)
    }

    @Test fun uiOwnsNeutralAndThrottledAxisEvents() = withWindow { activity, button ->
        button.setOnKeyListener { _, key, _ -> key == KeyEvent.KEYCODE_DPAD_DOWN }
        val callback = activity.window.callback
        for (value in listOf(0.8f, 0.8f, 0f)) {
            val event = axis(value)
            try {
                assertTrue("VR navigation must also consume neutral and throttled raw axes",
                    callback.dispatchGenericMotionEvent(event))
            } finally { event.recycle() }
        }
    }

    @Test fun oneHeldHatSampleRepeatsAndWindowFocusLossCancelsIt() {
        var moves = 0
        var beforeBlur = 0
        withWindowAcrossWaits { scenario ->
            scenario.onActivity { activity ->
                val button = focusedButton(activity)
                button.setOnKeyListener { _, key, event ->
                    if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                        if (event.action == KeyEvent.ACTION_DOWN) moves++
                        true
                    } else false
                }
                val event = axis(0f, hatY = 1f)
                try { assertTrue(activity.window.callback.dispatchGenericMotionEvent(event)) }
                finally { event.recycle() }
            }
            SystemClock.sleep(VrSettings.DEFAULT.menuRepeatMs * 3 + 100)
            scenario.onActivity { activity ->
                assertTrue("A held pad that emits only state changes must keep navigating; moves=$moves", moves >= 3)
                activity.window.callback.onWindowFocusChanged(false)
                beforeBlur = moves
            }
            SystemClock.sleep(VrSettings.DEFAULT.menuRepeatMs * 2 + 100)
            scenario.onActivity { assertEquals("Focus loss must cancel pending axis repeats", beforeBlur, moves) }
        }
    }

    private fun withWindow(block: (HelpActivity, Button) -> Unit) = withWindowAcrossWaits { scenario ->
        scenario.onActivity { block(it, focusedButton(it)) }
    }

    private fun withWindowAcrossWaits(block: (ActivityScenario<HelpActivity>) -> Unit) {
        val context = instrumentation.targetContext
        val original = VrSession.settings
        instrumentation.runOnMainSync { VrSession.setEnabled(context, false) }
        val scenario = ActivityScenario.launch<HelpActivity>(Intent(context, HelpActivity::class.java)
            .setData(Uri.parse("about:blank")))
        try {
            scenario.onActivity { activity ->
                val button = Button(activity).apply {
                    id = android.R.id.button1
                    text = "Native focus target"
                    isFocusableInTouchMode = true
                }
                activity.setContentView(button)
                button.requestFocus()
                VrSession.setEnabled(activity, true)
                VrSession.setPanelOpen(false)
                assertTrue(activity.window.callback is VrActivityHooks)
            }
            instrumentation.waitForIdleSync()
            block(scenario)
        } finally {
            try { scenario.onActivity { VrSession.updateSettings(it, original.copy(enabled = false)) } }
            finally { scenario.close() }
        }
    }

    private fun focusedButton(activity: HelpActivity): Button =
        activity.findViewById<Button>(android.R.id.button1).apply { requestFocus() }

    private fun key(time: Long, action: Int, keyCode: Int) = KeyEvent(time,
        SystemClock.uptimeMillis(), action, keyCode, 0, 0, 15, 0, 0, InputDevice.SOURCE_GAMEPAD)

    private fun axis(y: Float, hatY: Float = 0f): MotionEvent {
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            setAxisValue(MotionEvent.AXIS_Y, y)
            setAxisValue(MotionEvent.AXIS_HAT_Y, hatY)
        })
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0 })
        val now = SystemClock.uptimeMillis()
        return MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, 1, props, coords,
            0, 0, 1f, 1f, 15, 0, InputDevice.SOURCE_JOYSTICK, 0)
    }
}
