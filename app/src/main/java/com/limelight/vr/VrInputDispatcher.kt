package com.limelight.vr

import android.app.Activity
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.limelight.Game

/** Routes input through the next Android Window.Callback without redispatching into its decor. */
object VrInputDispatcher {
    internal fun isControllerKey(event: KeyEvent): Boolean =
        VrGamepadRouter.isGamepad(event.source) ||
            VrGamepadRouter.isGamepad(event.device?.sources ?: 0) ||
            VrGamepadRouter.isControllerKeyCode(event.keyCode)

    fun dispatchKey(
        activity: Activity,
        event: KeyEvent,
        inDialog: Boolean,
        deliver: (KeyEvent) -> Boolean,
    ): Boolean {
        val controllerKey = isControllerKey(event)
        // BACK from a controller can be the game's legacy SELECT button.
        val mouseBack = event.isFromSource(InputDevice.SOURCE_MOUSE) ||
            event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)
        val backOwnedByHost = inDialog || (activity is Game && activity.connected && (controllerKey || mouseBack))
        if (VrSession.onKeyEvent(event, controllerKey, backOwnedByHost,
                captureUiMenuKeys = activity !is Game || inDialog)) return true
        if (!VrSession.enabled || !controllerKey || activity is Game) return deliver(event)

        // Existing Android dialogs may own Button B via OnKeyListener. A fallback
        // mapping is needed for dialogs and Views that expect DPAD_CENTER for A.
        // Deliver through the original callback: DecorView.dispatchKeyEvent would
        // call our Window.Callback again for the same event.
        if (event.keyCode != VrKeys.BUTTON_A) return deliver(event)
        if (deliver(event)) return true
        val mapped = KeyEvent(event.downTime, event.eventTime, event.action, KeyEvent.KEYCODE_DPAD_CENTER,
            event.repeatCount, event.metaState, event.deviceId, 0, event.flags, event.source)
        return deliver(mapped)
    }

    /** Hardware pointer presses use touch dispatch too; only touchscreen gestures recenter. */
    fun dispatchTouch(activity: Activity, event: MotionEvent, inDialog: Boolean,
                      deliver: (MotionEvent) -> Boolean): Boolean {
        if (!VrSession.enabled) return deliver(event)
        val tool = if (event.pointerCount > 0) event.getToolType(0) else MotionEvent.TOOL_TYPE_UNKNOWN
        val hardwarePointer = event.isFromSource(InputDevice.SOURCE_MOUSE) ||
            event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE) ||
            event.isFromSource(InputDevice.SOURCE_TOUCHPAD) ||
            tool == MotionEvent.TOOL_TYPE_MOUSE || tool == MotionEvent.TOOL_TYPE_STYLUS ||
            tool == MotionEvent.TOOL_TYPE_ERASER
        if (hardwarePointer && activity is Game && activity.connected &&
            !inDialog && !VrSession.panelOpen && !activity.hasNativeGameMenu()) {
            // Send to the same production input handler as pointer hover/scroll. Letting this
            // hit View dispatch would target the promoted Cardboard Surface, not streamView.
            activity.onGenericMotionEvent(event)
        } else if (!hardwarePointer && event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) {
            VrSession.onVrTap(event)
        }
        return true
    }

    fun dispatchMotion(
        activity: Activity,
        event: MotionEvent,
        inDialog: Boolean,
        deliverKey: (KeyEvent) -> Boolean,
    ): Boolean {
        if (!VrSession.enabled || event.actionMasked != MotionEvent.ACTION_MOVE ||
            !VrGamepadRouter.isGamepad(event.source)) return false
        if (VrSession.onMotionEvent(event)) return true
        // A stream (including its START wheel) consumes real stick and HAT state.
        // Its native game menu also owns its axis navigation, so keep both paths.
        if (activity is Game && (!inDialog || activity.hasNativeGameMenu())) return false
        VrSession.onUiNavigation(
            event.getAxisValue(VrKeys.AXIS_X),
            event.getAxisValue(VrKeys.AXIS_Y),
            event.getAxisValue(VrKeys.AXIS_HAT_X),
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
        val time = SystemClock.uptimeMillis()
        val down = KeyEvent(time, time, KeyEvent.ACTION_DOWN, keyCode, 0,
            event.metaState, event.deviceId, 0, 0, InputDevice.SOURCE_DPAD)
        val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
        deliverKey(down)
        deliverKey(up)
        return true
    }
}
