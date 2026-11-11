package com.limelight.vr

/**
 * Plain Int constants mirroring AOSP KeyEvent / InputEvent values.
 *
 * These deliberately do NOT reference android.view.KeyEvent: the JVM unit tests must be able to
 * exercise VrGamepadRouter without Android framework stubs ("Method ... not mocked").
 * Values are from AOSP KeyEvent and are stable across API levels.
 */
object VrKeys {
    const val ACTION_DOWN = 0
    const val ACTION_UP = 1

    const val BACK = 4
    // KEYCODE_B (30) and KEYCODE_ESCAPE (111) are ordinary game keys, NOT back-stack keys.
    // MainActivity's VR branch forwards them to the stream game and swallows them, so they can
    // never reach the hidden 2D UI. Only KEYCODE_BACK (4) needs special VR handling.
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val DPAD_CENTER = 23

    const val BUTTON_A = 96
    const val BUTTON_B = 97
    const val BUTTON_X = 99
    const val BUTTON_Y = 100
    const val BUTTON_L1 = 102
    const val BUTTON_R1 = 103
    const val BUTTON_L2 = 104
    const val BUTTON_R2 = 105
    const val BUTTON_START = 108
    const val BUTTON_SELECT = 109
    const val BUTTON_MODE = 110

    const val SOURCE_DPAD = 0x00000201
    const val SOURCE_GAMEPAD = 0x00000401
    const val SOURCE_JOYSTICK = 0x01000010

    const val AXIS_X = 0
    const val AXIS_Y = 1
    /**
     * Many Bluetooth pads report the physical D-pad as HAT AXES inside a MotionEvent instead of
     * KEYCODE_DPAD_*. With the 2D Compose layer isolated while VR is on, those hat events used to
     * be swallowed and the D-pad appeared completely dead. AXIS_HAT_Y is now handled by
     * VrGamepadRouter.onHat(); up = -1, down = +1 (same sign as AXIS_Y).
     */
    const val AXIS_HAT_X = 15
    const val AXIS_HAT_Y = 16

    /**
     * Analogue triggers. Most Bluetooth / Xbox / PlayStation pads report LT/RT as
     * AXIS_LTRIGGER / AXIS_RTRIGGER (or the legacy AXIS_BRAKE / AXIS_GAS) inside a MotionEvent
     * and NEVER emit KEYCODE_BUTTON_L2 / R2. VrSession.onTriggerEvent() reads these so the
     * L2+R2 long-press works on real devices as well as on pads that DO send keycodes (handled by
     * VrGamepadRouter.onKey). Keeping them as plain Int constants preserves JVM-only unit tests.
     */
    const val AXIS_LTRIGGER = 17
    const val AXIS_RTRIGGER = 18
    const val AXIS_GAS = 22
    const val AXIS_BRAKE = 23
    /** A trigger counts as "pressed" once its analogue value crosses this threshold. */
    const val TRIGGER_PRESS_THRESHOLD = 0.5f
    /**
     * Guards against a pad that emits BOTH keycodes and axes for the same long-press: the keycode
     * path (VrGamepadRouter) and the axis path (VrSession) would otherwise each fire one toggle
     * and immediately cancel each other out.
     */
    const val PASSTHROUGH_TOGGLE_DEBOUNCE_MS = 800L

    /**
     * L1+R1 is ENTRY-ONLY now (user request): it opens VR when VR is off, but can no longer kick
     * the user out of an active session. Exiting is deliberately explicit — the phone's system
     * BACK key, or the "退出 VR" row in the in-VR settings panel. START+SELECT remains reserved
     * for the menu long-press.
     */
    val VR_TOGGLE_COMBOS = listOf(
        intArrayOf(BUTTON_L1, BUTTON_R1),
    )

    /**
     * Long-press triggers for the in-VR settings panel (hold >= MENU_LONG_PRESS_MS):
     * START+SELECT together, OR the HOME/MODE key alone. A short press of any of these is passed
     * straight through to the game so a stray tap cannot open the panel.
     */
    val MENU_LONG_PRESS_COMBO = intArrayOf(BUTTON_START, BUTTON_SELECT)
    const val MENU_LONG_PRESS_MS = 500L

    /** Single press closes the panel while it is open. */
    val MENU_CLOSE_KEYS = intArrayOf(BUTTON_SELECT, BUTTON_START, BUTTON_MODE)

    /**
     * L2+R2 (LT/RT) long-press is a DEDICATED ON/OFF switch for camera passthrough. It only flips
     * `VrSettings.passthroughEnabled`; the three layout styles are settings, never shortcut steps.
     * These two trigger buttons are otherwise unused in VR (L1+R1 enters VR, LB/RB page the menu,
     * START+SELECT / MODE long-press opens the menu), so there is no key conflict.
     *
     * Like the menu long-press, it relies on OS key-repeat as its clock — the same mechanism that
     * is already verified working on the user's real device for START+SELECT.
     */
    val PASSTHROUGH_TOGGLE_COMBO = intArrayOf(BUTTON_L2, BUTTON_R2)
    const val PASSTHROUGH_LONG_PRESS_MS = 500L

    /**
     * Second-level PAGE navigation lives on the shoulder buttons (user request): LB = previous
     * page, RB = next page. D-pad LEFT/RIGHT deliberately do NOTHING in the menu — the D-pad is
     * selection-only (UP/DOWN) and numeric values belong to the left stick behind an A-armed
     * edit mode. Keeping L1+R1 as the VR toggle is still safe because that combo is only checked
     * while the menu is CLOSED.
     */
    const val PAGE_PREV_KEY = BUTTON_L1
    const val PAGE_NEXT_KEY = BUTTON_R1

}
