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
    const val ENTER = 66
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val DPAD_CENTER = 23

    const val BUTTON_A = 96
    const val BUTTON_B = 97
    const val BUTTON_X = 99
    const val BUTTON_L1 = 102
    const val BUTTON_R1 = 103
    const val BUTTON_START = 108
    const val BUTTON_SELECT = 109
    const val BUTTON_MODE = 110

    const val SOURCE_DPAD = 0x00000201
    const val SOURCE_GAMEPAD = 0x00000401
    const val SOURCE_JOYSTICK = 0x01000010

    const val AXIS_X = 0
    const val AXIS_Y = 1
    // Some gamepads report their D-pad exclusively as HAT axes.
    const val AXIS_HAT_X = 15
    const val AXIS_HAT_Y = 16

    /** Hold START+SELECT or MODE to open the VR settings panel. */
    const val MENU_LONG_PRESS_MS = 500L

    /** Native focus navigation keys, including D-pad keys sent by keyboard-source pads. */
    val FOCUS_KEYS = intArrayOf(DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT, DPAD_CENTER)
}
