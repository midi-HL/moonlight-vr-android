package com.limelight.vr

import kotlin.math.abs

/**
 * Physical-gamepad state machine for the in-VR quick settings panel.
 *
 * Pure JVM: uses only the plain Int constants from [VrKeys], so unit tests run
 * without Robolectric. Long-press timing is NOT done here — a held pad may stop
 * emitting repeat events — see [VrSession] which arms a Handler on
 * [Command.ArmMenuHold] and cancels it on [Command.CancelMenuHold].
 */
class VrGamepadRouter {
    var vrEnabled = false
    var panelOpen = false
    var editing = false
    var deadZone = VrSettings.DEFAULT.gamepadDeadZone
    var repeatMs = VrSettings.DEFAULT.menuRepeatMs

    private val pressed = HashSet<Int>()
    private var comboFired = false
    private var menuArmed = false
    private var lastAxisMs = Long.MIN_VALUE
    private var lastHatY = 0f
    private var navAxisMs = Long.MIN_VALUE
    private var navHatY = 0f

    /** Direction to inject into the 2D focus system while the quick panel is closed. */
    var pendingNavDirection: Int = 0
        private set

    sealed class Command {
        object PassThrough : Command()
        object Consumed : Command()
        object ToggleVr : Command()
        object ArmMenuHold : Command()
        object CancelMenuHold : Command()
        object TogglePanel : Command()
        object ClosePanel : Command()
        object Confirm : Command()
        object Recentre : Command()
        object ExitVr : Command()
        data class Move(val delta: Int) : Command()
        data class Adjust(val steps: Int) : Command()
    }

    fun reset() {
        pressed.clear()
        comboFired = false
        menuArmed = false
        resetAxisHold()
        editing = false
    }

    /** Restarts the stick repeat cadence when edit mode changes. */
    fun resetAxisHold() {
        lastAxisMs = Long.MIN_VALUE
        lastHatY = 0f
        navAxisMs = Long.MIN_VALUE
        navHatY = 0f
        pendingNavDirection = 0
    }

    /** Called by VrSession once the armed long-press has elapsed. */
    fun onMenuHoldElapsed(): Command.TogglePanel? {
        if (!vrEnabled || !menuHoldActive()) return null
        return Command.TogglePanel
    }

    fun onKey(keyCode: Int, action: Int, isGamepadSource: Boolean): Command {
        if (!isGamepadSource) return Command.PassThrough
        if (action == VrKeys.ACTION_UP) {
            val wasMenuActive = menuHoldActive()
            pressed.remove(keyCode)
            if (!comboPressed()) comboFired = false
            if (wasMenuActive && !menuHoldActive()) {
                menuArmed = false
                return Command.CancelMenuHold
            }
            return if (panelOpen) Command.Consumed else Command.PassThrough
        }
        if (action != VrKeys.ACTION_DOWN) return Command.PassThrough
        val wasMenuActive = menuHoldActive()
        pressed.add(keyCode)

        // L1+R1 enters VR from anywhere in the app; entry-only, never exits.
        if (comboPressed() && !comboFired) {
            comboFired = true
            if (!vrEnabled) return Command.ToggleVr
        }
        if (!vrEnabled) return Command.PassThrough

        // START+SELECT / HOME: arm the long-press on the DOWN edge that completes it.
        if (!wasMenuActive && menuHoldActive() && !menuArmed && !panelOpen) {
            menuArmed = true
            return Command.ArmMenuHold
        }
        if (panelOpen) {
            return when (keyCode) {
                VrKeys.DPAD_UP -> Command.Move(-1)
                VrKeys.DPAD_DOWN -> Command.Move(1)
                VrKeys.DPAD_LEFT -> if (editing) Command.Adjust(-1) else Command.Consumed
                VrKeys.DPAD_RIGHT -> if (editing) Command.Adjust(1) else Command.Consumed
                VrKeys.BUTTON_A, VrKeys.DPAD_CENTER -> Command.Confirm
                VrKeys.BUTTON_B, VrKeys.BUTTON_SELECT,
                VrKeys.BUTTON_START, VrKeys.BUTTON_MODE -> Command.ClosePanel
                else -> Command.Consumed
            }
        }
        return Command.PassThrough
    }

    fun onAxis(x: Float, y: Float, timeMs: Long): Command {
        if (!panelOpen) return Command.PassThrough
        // eventTime is uptimeMillis and may be small on a freshly booted device;
        // Long.MIN_VALUE sentinel must not overflow when subtracted.
        if (lastAxisMs != Long.MIN_VALUE && timeMs - lastAxisMs < repeatMs) return Command.Consumed
        if (abs(y) > deadZone && abs(y) >= abs(x)) {
            lastAxisMs = timeMs
            if (editing) return Command.Adjust(if (y < 0) -1 else 1)
            return Command.Move(if (y < 0) -1 else 1)
        }
        if (editing && abs(x) > deadZone) {
            lastAxisMs = timeMs
            return Command.Adjust(if (x < 0) -1 else 1)
        }
        return Command.Consumed
    }

    /**
     * Stick/HAT navigation for the ordinary 2D screens while VR is on and the
     * quick panel is closed. Does NOT touch menu state; the host drains
     * [pendingNavDirection] and injects one DPAD key per repeat window.
     */
    fun onUiNavigation(x: Float, y: Float, hatY: Float, timeMs: Long): Boolean {
        if (!vrEnabled || panelOpen) return false
        val hat = when {
            hatY < -0.5f -> -1f
            hatY > 0.5f -> 1f
            else -> 0f
        }
        if (hat != navHatY) {
            navHatY = hat
            if (hat != 0f) {
                pendingNavDirection = hat.toInt()
                navAxisMs = timeMs
                return true
            }
        }
        if (navAxisMs != Long.MIN_VALUE && timeMs - navAxisMs < repeatMs) return false
        val direction = when {
            abs(y) > deadZone && abs(y) >= abs(x) -> if (y < 0) -1 else 1
            abs(x) > deadZone -> if (x < 0) -2 else 2
            else -> 0
        }
        if (direction == 0) return false
        navAxisMs = timeMs
        pendingNavDirection = direction
        return true
    }

    fun drainNavDirection(): Int {
        val value = pendingNavDirection
        pendingNavDirection = 0
        return value
    }

    /** Many pads report the D-pad as AXIS_HAT_Y instead of DPAD keycodes. */
    fun onHat(hatY: Float): Command {
        if (!panelOpen) return Command.PassThrough
        val normalized = when {
            hatY < -0.5f -> -1f
            hatY > 0.5f -> 1f
            else -> 0f
        }
        if (normalized == lastHatY) return Command.Consumed
        lastHatY = normalized
        if (normalized == 0f) return Command.Consumed
        return Command.Move(normalized.toInt())
    }

    private fun comboPressed(): Boolean =
        pressed.contains(VrKeys.BUTTON_L1) && pressed.contains(VrKeys.BUTTON_R1)

    private fun menuHoldActive(): Boolean =
        (pressed.contains(VrKeys.BUTTON_START) && pressed.contains(VrKeys.BUTTON_SELECT)) ||
            pressed.contains(VrKeys.BUTTON_MODE)

    companion object {
        /** Source mask check without importing android.view.InputDevice. */
        fun isGamepad(source: Int): Boolean =
            source and VrKeys.SOURCE_GAMEPAD == VrKeys.SOURCE_GAMEPAD ||
                source and VrKeys.SOURCE_JOYSTICK == VrKeys.SOURCE_JOYSTICK ||
                source and VrKeys.SOURCE_DPAD == VrKeys.SOURCE_DPAD
    }
}
