package com.limelight.vr

import android.annotation.SuppressLint
import android.app.Activity
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.limelight.Game

/** Routes input through the next Android Window.Callback without redispatching into its decor. */
@SuppressLint("InlinedApi")
object VrInputDispatcher {
    /** Input held by one actual Window must never continue in its successor. */
    class WindowState(
        private val inputManager: InputManager? = null,
        private val focusedView: () -> View? = { null },
    ) {
        private data class Button(val deviceId: Int, val keyCode: Int)
        private data class Press(val downTime: Long, var target: Int, val view: View?)
        private val presses = HashMap<Button, Press>()
        private val handler = Handler(Looper.getMainLooper())
        private var focused = true
        private var axes: Axes? = null
        private var axisDirection = 0
        private var axisPanel = false
        private var nextRepeatAt = 0L
        private var emitAxes: ((Axes, Long) -> Unit)? = null
        private var isValid: (() -> Boolean)? = null
        private var watchingDevice = false
        private val deviceListener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) = Unit
            override fun onInputDeviceChanged(deviceId: Int) = Unit
            override fun onInputDeviceRemoved(deviceId: Int) {
                if (axes?.deviceId == deviceId) cancelAxes()
            }
        }
        private val repeat = object : Runnable {
            override fun run() {
                val held = axes ?: return
                if (!focused || !VrSession.enabled || axisPanel != VrSession.panelOpen || isValid?.invoke() != true) {
                    cancelAxes()
                    return
                }
                val now = SystemClock.uptimeMillis()
                nextRepeatAt = now + VrSession.settings.menuRepeatMs
                emitAxes?.invoke(held, now)
                if (axes != null) handler.postAtTime(this, nextRepeatAt)
            }
        }

        internal fun dispatchUiKey(event: KeyEvent, fallback: Int, deliver: (KeyEvent) -> Boolean): Boolean {
            val button = Button(event.deviceId, event.keyCode)
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (!focused || event.isCanceled || event.repeatCount > 0) return true
                    val previous = presses[button]
                    if (previous?.downTime == event.downTime) return true
                    if (previous != null) clearNativePress(previous)
                    val press = Press(event.downTime, event.keyCode, focusedView())
                    presses[button] = press
                    if (!deliver(event) && fallback != event.keyCode && presses[button] === press) {
                        press.target = fallback
                        // B is often handled only on UP by native dialog listeners. Defer
                        // its BACK pair until neither edge was claimed, so DOWN cannot arm
                        // a second return behind the dialog's own dismissal.
                        if (fallback != KeyEvent.KEYCODE_BACK) deliver(mapKey(event, fallback))
                    }
                    return true
                }
                KeyEvent.ACTION_UP -> {
                    val press = presses[button] ?: return true
                    if (press.downTime != event.downTime) return true
                    presses.remove(button)
                    if (!focused || event.isCanceled ||
                        press.view !== focusedView()) {
                        clearNativePress(press)
                        return true
                    }
                    // A DOWN-only native action can change the page/panel. Its unhandled UP
                    // still belongs to that action, not to Android's B -> BACK fallback.
                    if (press.target == event.keyCode) {
                        deliver(event)
                    } else if (deliver(event)) {
                        // A UP-only toolbar action takes priority over DPAD_CENTER fallback.
                        // Its mapped DOWN must leave no pressed feedback or pending long click.
                        clearNativePress(press)
                    } else {
                        if (press.target == KeyEvent.KEYCODE_BACK) {
                            deliver(mapKey(KeyEvent.changeAction(event, KeyEvent.ACTION_DOWN), press.target))
                        }
                        deliver(mapKey(event, press.target))
                    }
                    return true
                }
            }
            return deliver(event)
        }

        internal fun offerAxes(value: Axes, valid: () -> Boolean, emit: (Axes, Long) -> Unit) {
            val direction = value.direction(VrSession.settings.gamepadDeadZone)
            val now = SystemClock.uptimeMillis()
            if (!focused || !valid()) {
                cancelAxes()
                return
            }
            if (direction == 0) {
                cancelAxes()
                emit(value, now)
                return
            }
            val fresh = axes == null || axisDirection != direction || axes?.deviceId != value.deviceId ||
                axisPanel != VrSession.panelOpen
            axes = value
            axisDirection = direction
            axisPanel = VrSession.panelOpen
            emitAxes = emit
            isValid = valid
            if (!watchingDevice && inputManager != null) {
                inputManager.registerInputDeviceListener(deviceListener, handler)
                watchingDevice = true
            }
            handler.removeCallbacks(repeat)
            if (fresh || now >= nextRepeatAt) {
                nextRepeatAt = now + VrSession.settings.menuRepeatMs
                emit(value, now)
            }
            if (axes != null) handler.postAtTime(repeat, nextRepeatAt)
        }

        fun cancelAxes() {
            handler.removeCallbacks(repeat)
            if (watchingDevice) {
                inputManager?.unregisterInputDeviceListener(deviceListener)
                watchingDevice = false
            }
            axes = null
            axisDirection = 0
            emitAxes = null
            isValid = null
        }

        /** Returns true only for the first loss; a late detach must not reset the next window. */
        fun onWindowFocusChanged(value: Boolean): Boolean {
            val lostFocus = focused && !value
            focused = value
            if (!value) cancel()
            return lostFocus
        }

        fun cancel() {
            cancelAxes()
            presses.values.forEach(::clearNativePress)
            presses.clear()
        }

        private fun clearNativePress(press: Press) {
            press.view?.apply { isPressed = false; cancelLongPress() }
        }
    }

    internal data class Axes(val x: Float, val y: Float, val hatX: Float, val hatY: Float,
                             val metaState: Int, val deviceId: Int) {
        fun direction(deadZone: Float): Int = when {
            kotlin.math.abs(hatY) > 0.5f && kotlin.math.abs(hatY) >= kotlin.math.abs(hatX) -> if (hatY < 0) -1 else 1
            kotlin.math.abs(hatX) > 0.5f -> if (hatX < 0) -2 else 2
            kotlin.math.abs(y) > deadZone && kotlin.math.abs(y) >= kotlin.math.abs(x) -> if (y < 0) -1 else 1
            kotlin.math.abs(x) > deadZone -> if (x < 0) -2 else 2
            else -> 0
        }
    }

    private fun mapKey(event: KeyEvent, keyCode: Int) = if (event.keyCode == keyCode) event else KeyEvent(
        event.downTime, event.eventTime, event.action, keyCode, event.repeatCount, event.metaState,
        event.deviceId, event.scanCode, event.flags, event.source,
    )

    internal fun isControllerKey(event: KeyEvent): Boolean =
        VrGamepadRouter.isGamepad(event.source) ||
            VrGamepadRouter.isGamepad(event.device?.sources ?: 0) ||
            VrGamepadRouter.isControllerKeyCode(event.keyCode)

    fun dispatchKey(
        activity: Activity,
        event: KeyEvent,
        inDialog: Boolean,
        state: WindowState? = null,
        deliver: (KeyEvent) -> Boolean,
    ): Boolean {
        val controllerKey = isControllerKey(event)
        // BACK from a controller can be the game's legacy SELECT button.
        val mouseBack = event.isFromSource(InputDevice.SOURCE_MOUSE) ||
            event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)
        val backOwnedByHost = inDialog || (activity is Game && activity.connected && (controllerKey || mouseBack))
        if (VrSession.onKeyEvent(event, controllerKey, backOwnedByHost,
                captureUiMenuKeys = activity !is Game || inDialog)) return true
        val nativeBack = event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE
        if (!VrSession.enabled || (!controllerKey && !nativeBack) || (activity is Game && !inDialog)) return deliver(event)

        // Existing Android dialogs may own Button B via OnKeyListener. A fallback
        // mapping is needed for dialogs and Views that expect DPAD_CENTER for A.
        // Deliver through the original callback: DecorView.dispatchKeyEvent would
        // call our Window.Callback again for the same event.
        val fallback = when (event.keyCode) {
            VrKeys.BUTTON_A -> KeyEvent.KEYCODE_DPAD_CENTER
            VrKeys.BUTTON_B -> KeyEvent.KEYCODE_BACK
            VrKeys.DPAD_CENTER, VrKeys.ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> event.keyCode
            else -> return deliver(event)
        }
        if (state != null) return state.dispatchUiKey(event, fallback, deliver)
        if (deliver(event)) return true
        return if (fallback != event.keyCode) deliver(mapKey(event, fallback)) else false
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
        state: WindowState? = null,
        deliverKey: (KeyEvent) -> Boolean,
    ): Boolean {
        if (!VrSession.enabled || !VrGamepadRouter.isGamepad(event.source)) {
            state?.cancelAxes()
            return false
        }
        // A stream (including its START wheel) consumes real stick and HAT state.
        // Its native game menu also owns its axis navigation, so keep both paths.
        if (!VrSession.panelOpen && activity is Game && (!inDialog || activity.hasNativeGameMenu())) {
            state?.cancelAxes()
            return false
        }
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            state?.cancelAxes()
            return true
        }
        if (event.actionMasked != MotionEvent.ACTION_MOVE) return false
        val axes = Axes(event.getAxisValue(VrKeys.AXIS_X), event.getAxisValue(VrKeys.AXIS_Y),
            event.getAxisValue(VrKeys.AXIS_HAT_X), event.getAxisValue(VrKeys.AXIS_HAT_Y),
            event.metaState, event.deviceId)
        if (state != null) state.offerAxes(axes, { !activity.isFinishing && !activity.isDestroyed }) { held, at ->
            dispatchAxes(held, at, deliverKey)
        } else dispatchAxes(axes, event.eventTime, deliverKey)
        // Neutral and throttled samples belong to the same navigator as deflection.
        // Letting them fall through enables Android's joystick-to-DPAD fallback too.
        return true
    }

    private fun dispatchAxes(axes: Axes, time: Long, deliverKey: (KeyEvent) -> Boolean) {
        if (VrSession.onNavigationAxes(axes.x, axes.y, axes.hatX, axes.hatY, time)) return
        VrSession.onUiNavigation(axes.x, axes.y, axes.hatX, axes.hatY, time)
        val direction = VrSession.drainNavDirection()
        if (direction == 0) return
        val keyCode = when (direction) {
            -1 -> KeyEvent.KEYCODE_DPAD_UP
            1 -> KeyEvent.KEYCODE_DPAD_DOWN
            -2 -> KeyEvent.KEYCODE_DPAD_LEFT
            2 -> KeyEvent.KEYCODE_DPAD_RIGHT
            else -> return
        }
        val down = KeyEvent(time, time, KeyEvent.ACTION_DOWN, keyCode, 0,
            axes.metaState, axes.deviceId, 0, 0, InputDevice.SOURCE_DPAD)
        val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
        deliverKey(down)
        deliverKey(up)
    }
}
