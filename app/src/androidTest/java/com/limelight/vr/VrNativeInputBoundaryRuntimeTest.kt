package com.limelight.vr

import android.content.Intent
import android.app.AlertDialog
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
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

    @Test fun unhandledBInvokesTheRealComponentActivityBackDispatcherOnce() {
        val context = instrumentation.targetContext
        val original = VrSession.settings
        instrumentation.runOnMainSync { VrSession.setEnabled(context, false) }
        val scenario = ActivityScenario.launch<ComponentActivity>(Intent(context, ComponentActivity::class.java))
        try {
            scenario.onActivity { activity ->
                var backs = 0
                activity.onBackPressedDispatcher.addCallback(activity, object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() { backs++ }
                })
                activity.setContentView(Button(activity).apply { isFocusableInTouchMode = true; requestFocus() })
                VrSession.setEnabled(activity, true)
                val now = SystemClock.uptimeMillis()
                activity.window.callback.dispatchKeyEvent(key(now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B))
                activity.window.callback.dispatchKeyEvent(key(now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_B))
                assertEquals("B must use the Activity's back dispatcher on predictive-back Android too", 1, backs)
                assertTrue(VrSession.enabled)
            }
        } finally {
            instrumentation.runOnMainSync { VrSession.updateSettings(context, original.copy(enabled = false)) }
            scenario.close()
        }
    }

    @Test fun unhandledBFinishesRealHelpActivityWithoutDisablingVr() {
        val context = instrumentation.targetContext
        val original = VrSession.settings
        instrumentation.runOnMainSync { VrSession.setEnabled(context, false) }
        val scenario = ActivityScenario.launch<HelpActivity>(Intent(context, HelpActivity::class.java)
            .setData(Uri.parse("about:blank")))
        lateinit var owner: HelpActivity
        fun awaitMain(message: String, stableMs: Long = 0L, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 15_000L
            var readySince = 0L
            while (SystemClock.uptimeMillis() < deadline) {
                var ready = false
                instrumentation.runOnMainSync { ready = condition() }
                val now = SystemClock.uptimeMillis()
                if (ready) {
                    if (readySince == 0L) readySince = now
                    if (now - readySince >= stableMs) return
                } else readySince = 0L
                SystemClock.sleep(25L)
            }
            throw AssertionError(message)
        }
        try {
            scenario.onActivity { activity ->
                owner = activity
                activity.setContentView(Button(activity).apply { isFocusableInTouchMode = true; requestFocus() })
                VrSession.setEnabled(activity, true)
            }
            // about:blank still creates a loading dialog. Inject through the real focused
            // window only after it has gone and the asynchronous landscape change settles.
            awaitMain("Help never became the focused landscape VR window", stableMs = 250L) {
                owner.window.decorView.hasWindowFocus() && owner.window.decorView.isLaidOut &&
                    owner.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
                    VrSession.isInputOwnerResumed(owner)
            }
            val downTime = SystemClock.uptimeMillis()
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                val event = KeyEvent(downTime, SystemClock.uptimeMillis(), action,
                    KeyEvent.KEYCODE_BUTTON_B, 0, 0, -1, 0, 0, InputDevice.SOURCE_GAMEPAD)
                assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
            }
            // API 35 ActivityClient requests finish through a posted callback; waiting on
            // the instrumentation thread lets that callback run on the Activity main loop.
            awaitMain("Plain Activity back must run, rather than a synthetic BACK key swallowed by API 33+") {
                owner.isFinishing || owner.isDestroyed
            }
            instrumentation.runOnMainSync {
                assertTrue(owner.isFinishing || owner.isDestroyed)
                assertTrue(VrSession.enabled)
            }
        } finally {
            instrumentation.runOnMainSync { VrSession.updateSettings(context, original.copy(enabled = false)) }
            scenario.close()
        }
    }

    @Test fun panelModeChangeCancelsNativeLongPressAndItsLaterConfirmation() {
        var clicks = 0
        var longClicks = 0
        withWindowAcrossWaits { scenario ->
            val downAt = SystemClock.uptimeMillis()
            scenario.onActivity { activity ->
                focusedButton(activity).apply {
                    setOnClickListener { clicks++ }
                    setOnLongClickListener { longClicks++; true }
                }
                activity.window.callback.dispatchKeyEvent(key(downAt, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A))
                VrSession.setPanelOpen(true)
            }
            SystemClock.sleep(1100L)
            scenario.onActivity { activity ->
                VrSession.setPanelOpen(false)
                activity.window.callback.dispatchKeyEvent(key(downAt, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A))
                assertEquals(0, longClicks)
                assertEquals(0, clicks)
                assertFalse(focusedButton(activity).isPressed)
            }
        }
    }

    @Test fun canceledNativeReleaseStaysCanceledAcrossNewWindowAndVrDisable() = withWindow { activity, button ->
        var confirmations = 0
        button.setOnKeyListener { _, code, event ->
            if (code == KeyEvent.KEYCODE_BUTTON_A) {
                if (event.action == KeyEvent.ACTION_UP) confirmations++
                true
            } else false
        }
        val now = SystemClock.uptimeMillis()
        activity.window.callback.dispatchKeyEvent(key(now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A))
        VrSession.setEnabled(activity, false)
        val successor = VrActivityHooks(activity, activity)
        successor.dispatchKeyEvent(key(now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A))
        assertEquals("The old UP must not activate a different window after exiting VR", 0, confirmations)
        successor.dispatchKeyEvent(key(now + 1, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A))
        successor.dispatchKeyEvent(key(now + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A))
        assertEquals("A fresh 2D press still works normally", 1, confirmations)
    }

    @Test fun shortPauseResumeCancelsHeldAxesBeforeAnyFocusNotification() {
        var moves = 0
        withWindowAcrossWaits { scenario ->
            scenario.onActivity { activity ->
                focusedButton(activity).setOnKeyListener { _, code, event ->
                    if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                        if (event.action == KeyEvent.ACTION_DOWN) moves++
                        true
                    } else false
                }
                val event = axis(0.8f)
                try { activity.window.callback.dispatchGenericMotionEvent(event) }
                finally { event.recycle() }
                assertEquals(1, moves)
                VrSession.onActivityPaused(activity)
                VrSession.onActivityResumed(activity)
            }
            SystemClock.sleep(VrSettings.DEFAULT.menuRepeatMs * 3 + 100)
            scenario.onActivity { assertEquals("Even a pause shorter than one repeat cancels the old hold", 1, moves) }
        }
    }

    @Test fun projectedDialogKeepsAxisOwnershipWhileItsActivityWindowIsUnfocused() {
        var moves = 0
        var dialog: AlertDialog? = null
        withWindowAcrossWaits { scenario ->
            try {
                scenario.onActivity { activity ->
                    val button = Button(activity).apply {
                        isFocusableInTouchMode = true
                        setOnKeyListener { _, code, event ->
                            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                                if (event.action == KeyEvent.ACTION_DOWN) moves++
                                true
                            } else false
                        }
                    }
                    dialog = AlertDialog.Builder(activity).setView(button).create().also {
                        it.show()
                        button.requestFocus()
                        VrSession.registerDialog(it)
                    }
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertFalse("The real dialog must own window focus", activity.window.decorView.hasWindowFocus())
                    val event = axis(0f, hatY = 1f)
                    try { requireNotNull(dialog).window!!.callback.dispatchGenericMotionEvent(event) }
                    finally { event.recycle() }
                }
                SystemClock.sleep(VrSettings.DEFAULT.menuRepeatMs * 3 + 100)
                scenario.onActivity { activity ->
                    assertTrue("A dialog shares the resumed Activity owner; moves=$moves", moves >= 3)
                    val now = SystemClock.uptimeMillis()
                    val callback = requireNotNull(dialog).window!!.callback
                    callback.dispatchKeyEvent(key(now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B))
                    callback.dispatchKeyEvent(key(now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_B))
                    assertFalse("Unhandled B must dismiss the dialog itself", requireNotNull(dialog).isShowing)
                    assertFalse(activity.isFinishing)
                    assertTrue(VrSession.enabled)
                }
            } finally { instrumentation.runOnMainSync { dialog?.dismiss() } }
        }
    }

    @Test fun keyboardEscapeAndNativeBackKeepBalancedCancellationOwnership() = withWindow { activity, button ->
        var dismissals = 0
        button.setOnKeyListener { _, code, event ->
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
                if (event.action == KeyEvent.ACTION_UP) dismissals++
                true
            } else false
        }
        val callback = activity.window.callback
        for (code in listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE)) {
            val now = SystemClock.uptimeMillis()
            fun event(action: Int, time: Long = now) = KeyEvent(time, SystemClock.uptimeMillis(), action,
                code, 0, 0, 16, 0, 0, InputDevice.SOURCE_KEYBOARD)
            callback.dispatchKeyEvent(event(KeyEvent.ACTION_UP))
            callback.dispatchKeyEvent(event(KeyEvent.ACTION_DOWN, now + 1))
            callback.dispatchKeyEvent(KeyEvent.changeFlags(event(KeyEvent.ACTION_UP, now + 1), KeyEvent.FLAG_CANCELED))
            callback.dispatchKeyEvent(event(KeyEvent.ACTION_DOWN, now + 2))
            callback.dispatchKeyEvent(event(KeyEvent.ACTION_UP, now + 2))
        }
        assertEquals("Only the two complete uncanceled key sequences may dismiss", 2, dismissals)
        assertTrue(VrSession.enabled)
    }

    @Test fun lateDetachOfAnAlreadyBlurredWindowDoesNotCancelTheNextWindowsMenuChord() {
        withWindowAcrossWaits { scenario ->
            scenario.onActivity { activity ->
                val oldWindow = VrActivityHooks(activity, activity)
                val newWindow = VrActivityHooks(activity, activity)
                oldWindow.onWindowFocusChanged(false)
                newWindow.onWindowFocusChanged(true)
                val now = SystemClock.uptimeMillis()
                newWindow.dispatchKeyEvent(key(now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_START))
                newWindow.dispatchKeyEvent(key(now + 1, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_SELECT))
                oldWindow.onDetachedFromWindow()
            }
            SystemClock.sleep(VrKeys.MENU_LONG_PRESS_MS + 150)
            scenario.onActivity { assertTrue("A detached old window must not cancel the new window's menu hold", VrSession.panelOpen) }
        }
    }

    @Test fun nativeButtonFallbackConfirmsExactlyOnce() = withWindow { activity, button ->
        var confirmations = 0
        button.setOnClickListener { confirmations++ }
        val callback = activity.window.callback
        val now = SystemClock.uptimeMillis()
        callback.dispatchKeyEvent(key(now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A))
        callback.dispatchKeyEvent(key(now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(1, confirmations)
        assertFalse(button.isPressed)
    }

    @Test fun freshPressAfterMissingReleaseConfirmsOnItsFirstAttempt() = withWindow { activity, button ->
        var confirmations = 0
        button.setOnKeyListener { _, key, event ->
            if (key == KeyEvent.KEYCODE_BUTTON_A) {
                if (event.action == KeyEvent.ACTION_UP) confirmations++
                true
            } else false
        }
        val callback = activity.window.callback
        val now = SystemClock.uptimeMillis()
        callback.dispatchKeyEvent(key(now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A))
        callback.dispatchKeyEvent(key(now + 1, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A))
        callback.dispatchKeyEvent(key(now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A))
        callback.dispatchKeyEvent(key(now + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(1, confirmations)
        assertFalse(button.isPressed)
    }

    @Test fun validAReleaseKeepsNativeUpOnlyActionAndLeavesNoMappedPress() = withWindow { activity, button ->
        var activations = 0
        button.setOnKeyListener { _, key, event ->
            if (key == KeyEvent.KEYCODE_BUTTON_A && event.action == KeyEvent.ACTION_UP) {
                activations++
                true
            } else false
        }
        val callback = activity.window.callback
        repeat(2) {
            val now = SystemClock.uptimeMillis()
            callback.dispatchKeyEvent(key(now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A))
            callback.dispatchKeyEvent(key(now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A))
            assertFalse("Native UP-only actions must release fallback feedback", button.isPressed)
        }
        assertEquals(2, activations)
    }

    @Test fun nativeBDownOnlyActionOwnsRepeatsAndItsUnhandledRelease() = withWindow { activity, button ->
        var dismissals = 0
        var leakedBack = 0
        button.setOnKeyListener { _, key, event ->
            when {
                key == KeyEvent.KEYCODE_BUTTON_B && event.action == KeyEvent.ACTION_DOWN -> { dismissals++; true }
                key == KeyEvent.KEYCODE_BACK -> { leakedBack++; true }
                else -> false
            }
        }
        val callback = activity.window.callback
        val now = SystemClock.uptimeMillis()
        assertTrue(callback.dispatchKeyEvent(key(now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B)))
        assertTrue(callback.dispatchKeyEvent(KeyEvent.changeTimeRepeat(key(now, KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_BUTTON_B), now + 20, 1)))
        assertTrue("An unhandled UP must not trigger Android B fallback",
            callback.dispatchKeyEvent(key(now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_B)))
        assertEquals(1, dismissals)
        assertEquals(0, leakedBack)
        assertTrue(VrSession.enabled)
    }

    @Test fun nativeBUpOnlyActionPrecedesBackFallback() = withWindow { activity, button ->
        var dismissals = 0
        var backs = 0
        var ownB = true
        button.setOnKeyListener { _, key, event ->
            when {
                ownB && key == KeyEvent.KEYCODE_BUTTON_B && event.action == KeyEvent.ACTION_UP -> { dismissals++; true }
                key == KeyEvent.KEYCODE_BACK -> { if (event.action == KeyEvent.ACTION_UP) backs++; true }
                else -> false
            }
        }
        val callback = activity.window.callback
        fun pressB() {
            val now = SystemClock.uptimeMillis()
            callback.dispatchKeyEvent(key(now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B))
            callback.dispatchKeyEvent(key(now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_B))
        }
        pressB()
        assertEquals(1, dismissals)
        assertEquals(0, backs)
        assertTrue(VrSession.enabled)
    }

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
        assertFalse(button.isPressed)
        callback.dispatchKeyEvent(key(now + 2, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A))
        callback.onWindowFocusChanged(false)
        assertFalse("Losing a window must clear the old fallback pressed state", button.isPressed)
        callback.onWindowFocusChanged(true)
        callback.dispatchKeyEvent(key(now + 2, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(0, activations)
        callback.dispatchKeyEvent(key(now + 3, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A))
        callback.dispatchKeyEvent(key(now + 3, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A))
        assertEquals("Canceling a press must not block the next valid confirmation", 1, activations)
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

    @Test fun unrelatedMouseHoverDoesNotCancelAHeldGamepadAxis() {
        var moves = 0
        var beforeMouse = 0
        withWindowAcrossWaits { scenario ->
            scenario.onActivity { activity ->
                focusedButton(activity).setOnKeyListener { _, key, event ->
                    if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                        if (event.action == KeyEvent.ACTION_DOWN) moves++
                        true
                    } else false
                }
                val held = axis(0f, hatY = 1f)
                try { assertTrue(activity.window.callback.dispatchGenericMotionEvent(held)) }
                finally { held.recycle() }
            }
            SystemClock.sleep(VrSettings.DEFAULT.menuRepeatMs * 2 + 100)
            scenario.onActivity { activity ->
                assertTrue("The initial held sample must already repeat", moves >= 2)
                beforeMouse = moves
                val now = SystemClock.uptimeMillis()
                val hover = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE, 4f, 4f, 0)
                hover.source = InputDevice.SOURCE_MOUSE
                try { activity.window.callback.dispatchGenericMotionEvent(hover) }
                finally { hover.recycle() }
            }
            SystemClock.sleep(VrSettings.DEFAULT.menuRepeatMs * 2 + 100)
            scenario.onActivity {
                assertTrue("Mouse motion must leave the other device's held navigation active", moves > beforeMouse)
            }
        }
    }

    @Test fun onlyTheOwningGamepadCanReleaseOrCancelHeldNavigation() {
        var moves = 0
        var checkpoint = 0
        withWindowAcrossWaits { scenario ->
            fun send(device: Int, hat: Float, action: Int = MotionEvent.ACTION_MOVE) {
                scenario.onActivity { activity ->
                    val event = axis(0f, hat, device, action)
                    try { assertTrue(activity.window.callback.dispatchGenericMotionEvent(event)) }
                    finally { event.recycle() }
                }
            }
            scenario.onActivity { activity ->
                focusedButton(activity).setOnKeyListener { _, key, event ->
                    if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                        if (event.action == KeyEvent.ACTION_DOWN) moves++
                        true
                    } else false
                }
            }
            send(15, 1f)
            SystemClock.sleep(VrSettings.DEFAULT.menuRepeatMs * 2 + 100)
            scenario.onActivity { assertTrue(moves >= 2); checkpoint = moves }
            send(16, 0f)
            send(16, 0f, MotionEvent.ACTION_CANCEL)
            SystemClock.sleep(VrSettings.DEFAULT.menuRepeatMs * 2 + 100)
            scenario.onActivity {
                assertTrue("Another pad's neutral/cancel must preserve the active pad", moves > checkpoint)
            }
            send(15, 0f)
            scenario.onActivity { checkpoint = moves }
            SystemClock.sleep(VrSettings.DEFAULT.menuRepeatMs * 2 + 100)
            scenario.onActivity { assertEquals("The owning pad's release must stop navigation", checkpoint, moves) }
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

    private fun axis(y: Float, hatY: Float = 0f, deviceId: Int = 15, action: Int = MotionEvent.ACTION_MOVE): MotionEvent {
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            setAxisValue(MotionEvent.AXIS_Y, y)
            setAxisValue(MotionEvent.AXIS_HAT_Y, hatY)
        })
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0 })
        val now = SystemClock.uptimeMillis()
        return MotionEvent.obtain(now, now, action, 1, props, coords,
            0, 0, 1f, 1f, deviceId, 0, InputDevice.SOURCE_JOYSTICK, 0)
    }
}
