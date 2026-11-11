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
 * Ownership rules (they prevent the double-handling that made B exit PcView and
 * A double-click a Preference after closing the VR panel):
 *  - While the quick panel is OPEN the VR layer owns every gamepad key. Nothing
 *    reaches the Activity, so ClosePanel cannot also trigger the 2D back stack.
 *  - With the panel CLOSED, only A/B are translated into DPAD_CENTER/BACK and
 *    delivered to the DECOR VIEW hierarchy (never back through
 *    Activity.dispatchKeyEvent, which would re-enter the hook and let
 *    StreamSettings apply its own BUTTON_A mapping a second time).
 *  - L1/R1 are NOT remapped: StreamSettings already uses them to open/close its
 *    drawer, and in VR they are the global "enter VR" combo.
 */
object VrInputDispatcher {
    fun dispatchKey(activity: Activity, event: KeyEvent): Boolean {
        val isGamepad = VrGamepadRouter.isGamepad(event.source)
        if (!VrSession.enabled) {
            // L1+R1 is a global "enter VR" shortcut, even on the home page.
            return VrSession.onKeyEvent(event)
        }
        if (VrSession.onKeyEvent(event)) return true
        if (VrSession.panelOpen) {
            // Panel owns input exclusively; router already returned PassThrough
            // for unknown keys, but they must still not reach the hidden 2D UI.
            return isGamepad
        }
        if (!isGamepad) return false
        val mapped = mapKey(event.keyCode) ?: return false
        val clone = KeyEvent(
            event.downTime, event.eventTime, event.action, mapped,
            event.repeatCount, event.metaState, event.deviceId, event.scanCode,
            event.flags, event.source,
        )
        // A visible Dialog owns a separate Window and has focus; deliver the
        // translated key there first, otherwise fall back to the Activity decor.
        val dialogDecor = VrSession.activeDialogDecors().lastOrNull()
        val target: View? = dialogDecor ?: activity.window?.decorView
        return target?.dispatchKeyEvent(clone) ?: false
    }

    fun dispatchMotion(activity: Activity, event: MotionEvent): Boolean {
        if (VrSession.onMotionEvent(event)) return true
        if (!VrSession.enabled || VrSession.panelOpen) return false
        if (!VrGamepadRouter.isGamepad(event.source)) return false
        VrSession.onUiNavigation(
            event.getAxisValue(VrKeys.AXIS_X),
            event.getAxisValue(VrKeys.AXIS_Y),
            event.getAxisValue(VrKeys.AXIS_HAT_Y),
            event.eventTime,
        )
        val direction = VrSession.drainNavDirection()
        if (direction == 0) return false
        val keyCode = when (direction) {
            -1 -> KeyEvent.KEYCODE_DPAD_UP
            1 -> KeyEvent.KEYCODE_DPAD_DOWN
            -2 -> KeyEvent.KEYCODE_DPAD_LEFT
            2 -> KeyEvent.KEYCODE_DPAD_RIGHT
            else -> return false
        }
        val decor = VrSession.activeDialogDecors().lastOrNull()
            ?: activity.window?.decorView ?: return false
        // Send a complete DOWN+UP pair so Android's focus finder moves exactly once.
        val down = KeyEvent(event.downTime, event.eventTime, KeyEvent.ACTION_DOWN,
            keyCode, 0, event.metaState, event.deviceId, 0, event.flags, event.source)
        val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
        decor.dispatchKeyEvent(down)
        decor.dispatchKeyEvent(up)
        return true
    }

    private fun mapKey(keyCode: Int): Int? = when (keyCode) {
        VrKeys.BUTTON_A -> KeyEvent.KEYCODE_DPAD_CENTER
        VrKeys.BUTTON_B -> KeyEvent.KEYCODE_BACK
        else -> null
    }
}
