package com.limelight.vr

import android.app.Activity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View

/**
 * Funnels physical gamepad input into the VR layer BEFORE the Activity sees it.
 * VrActivityHooks installs this at the Window.Callback level, so 600+ existing
 * screens need no per-class edits.
 *
 * Once the VR layer declines an event, face/shoulder buttons are translated into
 * DPAD keycodes and sent to the DECOR VIEW hierarchy (not back through
 * Activity.dispatchKeyEvent, which would re-enter the hook). Android's own
 * focus navigation then moves/clicks the real Preference rows and buttons.
 */
object VrInputDispatcher {
    fun dispatchKey(activity: Activity, event: KeyEvent): Boolean {
        if (!VrSession.enabled) {
            // L1+R1 is a global "enter VR" shortcut, even on the home page.
            return VrSession.onKeyEvent(event)
        }
        if (VrSession.onKeyEvent(event)) return true
        val mapped = mapKey(event.keyCode) ?: return false
        val clone = KeyEvent(
            event.downTime, event.eventTime, event.action, mapped,
            event.repeatCount, event.metaState, event.deviceId, event.scanCode,
            event.flags, event.source,
        )
        val decor: View? = activity.window?.decorView
        return decor?.dispatchKeyEvent(clone) ?: false
    }

    fun dispatchMotion(activity: Activity, event: MotionEvent): Boolean {
        if (VrSession.onMotionEvent(event)) return true
        return false
    }

    private fun mapKey(keyCode: Int): Int? = when (keyCode) {
        VrKeys.BUTTON_A -> KeyEvent.KEYCODE_DPAD_CENTER
        VrKeys.BUTTON_B -> KeyEvent.KEYCODE_BACK
        VrKeys.BUTTON_L1 -> KeyEvent.KEYCODE_DPAD_UP
        VrKeys.BUTTON_R1 -> KeyEvent.KEYCODE_DPAD_DOWN
        else -> null
    }
}
