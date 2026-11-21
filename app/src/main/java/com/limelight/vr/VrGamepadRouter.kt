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

    private data class Button(val deviceId: Int, val keyCode: Int)

    private val pressed = HashSet<Button>()
    private val forwardedReleases = HashSet<Button>()
    private val pressTimes = HashMap<Button, Long>()
    private val captured = HashSet<Button>()
    private var menuDevice: Int? = null
    private var menuHoldFired = false
    private var lastAxisMs = Long.MIN_VALUE
    private var lastHatDirection = 0
    private var lastHatMs = Long.MIN_VALUE
    private var navAxisMs = Long.MIN_VALUE
    private var navHatDirection = 0

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
        forwardedReleases.clear()
        pressTimes.clear()
        captured.clear()
        cancelWindowInput()
    }

    /** Lose gestures, but keep the release of every DOWN already sent to its original owner. */
    fun cancelWindowInput() {
        forwardedReleases.addAll(pressed.filterNot { it in captured })
        pressed.clear()
        menuDevice = null
        menuHoldFired = false
        lastHatDirection = 0
        lastHatMs = Long.MIN_VALUE
        navHatDirection = 0
        resetAxisHold()
    }

    /** Restarts the stick repeat cadence when edit mode changes. */
    fun resetAxisHold() {
        lastAxisMs = Long.MIN_VALUE
        // Preserve the physical HAT edge when entering or leaving edit mode.
        navAxisMs = Long.MIN_VALUE
        pendingNavDirection = 0
    }

    /** Called by VrSession once the armed long-press has elapsed. */
    fun onMenuHoldElapsed(): Command.TogglePanel? {
        val device = menuDevice ?: return null
        if (menuHoldFired || !vrEnabled || panelOpen || !menuHoldActive(device)) return null
        menuHoldFired = true
        return Command.TogglePanel
    }

    fun onKey(
        keyCode: Int,
        action: Int,
        isGamepadSource: Boolean,
        deviceId: Int = 0,
        isRepeat: Boolean = false,
        captureUiMenuKeys: Boolean = false,
        downTime: Long? = null,
    ): Command {
        val button = Button(deviceId, keyCode)
        if (action == VrKeys.ACTION_UP) {
            val currentTime = pressTimes[button]
            if (downTime != null && currentTime != null && currentTime != downTime) {
                return if (button in captured || (panelOpen && isGamepadSource)) Command.Consumed else Command.PassThrough
            }
            pressTimes.remove(button)
            if (forwardedReleases.remove(button)) return Command.PassThrough
            val owned = captured.remove(button)
            val wasPressed = pressed.remove(button)
            if (menuDevice == deviceId && !menuHoldActive(deviceId)) {
                menuDevice = null
                menuHoldFired = false
                // A button that reached the app on DOWN still needs its UP.
                if (owned) return Command.CancelMenuHold
            }
            return if (owned || (panelOpen && isGamepadSource && !wasPressed)) {
                Command.Consumed
            } else Command.PassThrough
        }
        if (action != VrKeys.ACTION_DOWN || !isGamepadSource) return Command.PassThrough
        val previousTime = pressTimes[button]
        if (!isRepeat && downTime != null && previousTime != null && previousTime != downTime) {
            // A new physical press proves the previous UP was lost. Keep true OS repeats
            // suppressed, but let the user's next complete press recover immediately.
            pressed.remove(button)
            forwardedReleases.remove(button)
            captured.remove(button)
            pressTimes.remove(button)
            if (menuDevice == deviceId && !menuHoldActive(deviceId)) {
                menuDevice = null
                menuHoldFired = false
            }
        }
        if (button in forwardedReleases) return Command.PassThrough
        if (button in captured && button !in pressed) return Command.Consumed
        val wasMenuActive = menuHoldActive(deviceId)
        val alreadyDown = button in pressed
        if (alreadyDown || isRepeat) {
            if (button !in captured) {
                return if (panelOpen) Command.Consumed else Command.PassThrough
            }
            // Direction repeats are useful. Confirmation and dismissal must be fresh presses.
            return if (panelOpen) when (keyCode) {
                VrKeys.DPAD_UP -> Command.Move(-1)
                VrKeys.DPAD_DOWN -> Command.Move(1)
                VrKeys.DPAD_LEFT -> if (editing) Command.Adjust(-1) else Command.Consumed
                VrKeys.DPAD_RIGHT -> if (editing) Command.Adjust(1) else Command.Consumed
                else -> Command.Consumed
            } else Command.Consumed
        }
        pressed.add(button)
        if (downTime != null) pressTimes[button] = downTime

        if (!vrEnabled) {
            val shoulder = keyCode == VrKeys.BUTTON_L1 || keyCode == VrKeys.BUTTON_R1
            if (shoulder && isPressed(deviceId, VrKeys.BUTTON_L1) && isPressed(deviceId, VrKeys.BUTTON_R1)) {
                captured.add(button)
                return Command.ToggleVr
            }
            return Command.PassThrough
        }

        // START has an Android DPAD_CENTER fallback; SELECT/MODE fall back to MENU/HOME.
        // UI windows reserve these keys from their first DOWN, while live Game retains
        // short START/SELECT input. UP ownership stays with the original DOWN context.
        val capturedUiMenuKey = captureUiMenuKeys && (keyCode == VrKeys.BUTTON_START ||
            keyCode == VrKeys.BUTTON_SELECT || keyCode == VrKeys.BUTTON_MODE)
        if (capturedUiMenuKey) captured.add(button)

        // START+SELECT / HOME: arm the long-press on the DOWN edge that completes it.
        if (!wasMenuActive && menuHoldActive(deviceId) && menuDevice == null && !panelOpen) {
            menuDevice = deviceId
            captured.add(button)
            return Command.ArmMenuHold
        }
        if (panelOpen) {
            captured.add(button)
            return when (keyCode) {
                VrKeys.DPAD_UP -> Command.Move(-1)
                VrKeys.DPAD_DOWN -> Command.Move(1)
                VrKeys.DPAD_LEFT -> if (editing) Command.Adjust(-1) else Command.Consumed
                VrKeys.DPAD_RIGHT -> if (editing) Command.Adjust(1) else Command.Consumed
                VrKeys.BUTTON_A, VrKeys.DPAD_CENTER, VrKeys.ENTER -> Command.Confirm
                VrKeys.BUTTON_B, VrKeys.BUTTON_SELECT,
                VrKeys.BUTTON_START, VrKeys.BUTTON_MODE -> Command.ClosePanel
                else -> Command.Consumed
            }
        }
        return if (capturedUiMenuKey) Command.Consumed else Command.PassThrough
    }

    fun onAxis(x: Float, y: Float, timeMs: Long): Command {
        if (!panelOpen) return Command.PassThrough
        if (abs(x) <= deadZone && abs(y) <= deadZone) {
            lastAxisMs = Long.MIN_VALUE
            return Command.Consumed
        }
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
        return onUiNavigation(x, y, 0f, hatY, timeMs)
    }

    fun onUiNavigation(x: Float, y: Float, hatX: Float, hatY: Float, timeMs: Long): Boolean {
        if (!vrEnabled || panelOpen) return false
        val hat = hatDirection(hatX, hatY)
        if (hat != navHatDirection) {
            navHatDirection = hat
            if (hat != 0) {
                pendingNavDirection = hat
                navAxisMs = timeMs
                return true
            }
        }
        val direction = when {
            hat != 0 -> hat
            abs(y) > deadZone && abs(y) >= abs(x) -> if (y < 0) -1 else 1
            abs(x) > deadZone -> if (x < 0) -2 else 2
            else -> 0
        }
        if (direction == 0) {
            navAxisMs = Long.MIN_VALUE
            pendingNavDirection = 0
            return false
        }
        if (navAxisMs != Long.MIN_VALUE && timeMs - navAxisMs < repeatMs) return false
        navAxisMs = timeMs
        pendingNavDirection = direction
        return true
    }

    fun drainNavDirection(): Int {
        val value = pendingNavDirection
        pendingNavDirection = 0
        return value
    }

    fun onHat(hatY: Float): Command = onHat(0f, hatY)

    /** Many pads report the D-pad as HAT axes instead of DPAD keycodes. */
    fun onHat(hatX: Float, hatY: Float): Command = onHatInternal(hatX, hatY, null)

    /** Runtime navigation repeats from the held sample, even on edge-only controllers. */
    fun onHat(hatX: Float, hatY: Float, timeMs: Long): Command = onHatInternal(hatX, hatY, timeMs)

    private fun onHatInternal(hatX: Float, hatY: Float, timeMs: Long?): Command {
        if (!panelOpen) return Command.PassThrough
        val direction = hatDirection(hatX, hatY)
        if (direction == 0) {
            lastHatDirection = 0
            lastHatMs = Long.MIN_VALUE
            return Command.Consumed
        }
        if (direction == lastHatDirection && (timeMs == null ||
                (lastHatMs != Long.MIN_VALUE && timeMs - lastHatMs < repeatMs))) return Command.Consumed
        lastHatDirection = direction
        if (timeMs != null) lastHatMs = timeMs
        return when (direction) {
            -1, 1 -> Command.Move(direction)
            -2, 2 -> if (editing) Command.Adjust(direction / 2) else Command.Consumed
            else -> Command.Consumed
        }
    }

    private fun hatDirection(x: Float, y: Float): Int = when {
        abs(y) > 0.5f && abs(y) >= abs(x) -> if (y < 0) -1 else 1
        abs(x) > 0.5f -> if (x < 0) -2 else 2
        else -> 0
    }

    private fun isPressed(deviceId: Int, keyCode: Int): Boolean = Button(deviceId, keyCode) in pressed

    private fun menuHoldActive(deviceId: Int): Boolean =
        (isPressed(deviceId, VrKeys.BUTTON_START) && isPressed(deviceId, VrKeys.BUTTON_SELECT)) ||
            isPressed(deviceId, VrKeys.BUTTON_MODE)

    companion object {
        /** Keep DOWN/UP classification stable across a VR or menu visibility transition. */
        fun isControllerKeyCode(keyCode: Int): Boolean =
            keyCode in VrKeys.BUTTON_A..VrKeys.BUTTON_MODE ||
                keyCode in VrKeys.FOCUS_KEYS || keyCode == VrKeys.ENTER

        /** Source mask check without importing android.view.InputDevice. */
        fun isGamepad(source: Int): Boolean =
            source and VrKeys.SOURCE_GAMEPAD == VrKeys.SOURCE_GAMEPAD ||
                source and VrKeys.SOURCE_JOYSTICK == VrKeys.SOURCE_JOYSTICK ||
                source and VrKeys.SOURCE_DPAD == VrKeys.SOURCE_DPAD
    }
}
