package com.limelight.vr

import com.limelight.nvstream.input.ControllerPacket
import kotlin.math.abs

/** Pure mapping for controllers whose USB/native backend reports snapshots instead of Android events. */
internal class VrDriverSnapshotRouter {
    data class Result(val hostButtons: Int, val captured: Boolean)

    private data class State(
        var buttons: Int = 0,
        var ownedButtons: Int = 0,
        var waitForNeutral: Boolean = false,
        var lastDirectionMs: Long = Long.MIN_VALUE,
    )

    private val states = HashMap<Int, State>()

    fun beginCapture() {
        states.values.forEach { it.waitForNeutral = true }
    }

    fun update(
        sourceId: Int,
        buttons: Int,
        leftX: Float,
        leftY: Float,
        rightX: Float,
        rightY: Float,
        leftTrigger: Float,
        rightTrigger: Float,
        timeMs: Long,
        deadZone: Float,
        repeatMs: Long,
        panelOpen: () -> Boolean,
        onKey: (keyCode: Int, action: Int, repeated: Boolean) -> Boolean,
    ): Result {
        val state = states.getOrPut(sourceId) { State() }
        val wasOpen = panelOpen()
        if (wasOpen) state.waitForNeutral = true
        val changed = state.buttons xor buttons
        // Releases first: one snapshot may release a chord and press another button.
        for ((flag, key) in BUTTONS) if (changed and flag != 0 && buttons and flag == 0) {
            onKey(key, VrKeys.ACTION_UP, false)
            state.ownedButtons = state.ownedButtons and flag.inv()
        }
        for ((flag, key) in BUTTONS) if (changed and flag != 0 && buttons and flag != 0) {
            if (onKey(key, VrKeys.ACTION_DOWN, false)) state.ownedButtons = state.ownedButtons or flag
            if (flag and DIRECTION_MASK != 0) state.lastDirectionMs = timeMs
        }
        state.buttons = buttons
        val open = panelOpen()
        if (open) state.waitForNeutral = true
        if (open && buttons and DIRECTION_MASK != 0 && changed and DIRECTION_MASK == 0 &&
            (state.lastDirectionMs == Long.MIN_VALUE || timeMs - state.lastDirectionMs >= repeatMs)) {
            state.lastDirectionMs = timeMs
            for ((flag, key) in BUTTONS) if (buttons and flag and DIRECTION_MASK != 0) {
                onKey(key, VrKeys.ACTION_DOWN, true)
            }
        }
        val capture = wasOpen || open || state.waitForNeutral
        if (!open && buttons == 0 && abs(leftX) <= deadZone && abs(leftY) <= deadZone &&
            abs(rightX) <= deadZone && abs(rightY) <= deadZone && leftTrigger <= 0.1f && rightTrigger <= 0.1f) {
            state.waitForNeutral = false
        }
        return Result(if (capture) 0 else buttons and state.ownedButtons.inv(), capture)
    }

    fun forget(sourceId: Int, onRelease: (keyCode: Int) -> Unit) {
        val state = states.remove(sourceId) ?: return
        for ((flag, key) in BUTTONS) if (state.buttons and flag != 0) onRelease(key)
    }

    companion object {
        const val DIRECTION_MASK = ControllerPacket.UP_FLAG or ControllerPacket.DOWN_FLAG or
            ControllerPacket.LEFT_FLAG or ControllerPacket.RIGHT_FLAG

        private val BUTTONS = listOf(
            ControllerPacket.UP_FLAG to VrKeys.DPAD_UP,
            ControllerPacket.DOWN_FLAG to VrKeys.DPAD_DOWN,
            ControllerPacket.LEFT_FLAG to VrKeys.DPAD_LEFT,
            ControllerPacket.RIGHT_FLAG to VrKeys.DPAD_RIGHT,
            ControllerPacket.A_FLAG to VrKeys.BUTTON_A,
            ControllerPacket.B_FLAG to VrKeys.BUTTON_B,
            ControllerPacket.X_FLAG to VrKeys.BUTTON_X,
            ControllerPacket.Y_FLAG to 100, // KEYCODE_BUTTON_Y
            ControllerPacket.LB_FLAG to VrKeys.BUTTON_L1,
            ControllerPacket.RB_FLAG to VrKeys.BUTTON_R1,
            ControllerPacket.PLAY_FLAG to VrKeys.BUTTON_START,
            ControllerPacket.BACK_FLAG to VrKeys.BUTTON_SELECT,
            ControllerPacket.SPECIAL_BUTTON_FLAG to VrKeys.BUTTON_MODE,
        )
    }
}
