package com.limelight.vr

/** Preserves a window's original IME layering flag while VR temporarily blocks the system IME. */
internal class VrImeWindowFlagState {
    private var originalAltFocusable: Boolean? = null

    fun apply(flags: Int, enabled: Boolean, altFocusableFlag: Int): Int {
        if (enabled) {
            if (originalAltFocusable == null) {
                originalAltFocusable = flags and altFocusableFlag != 0
            }
            return flags or altFocusableFlag
        }

        val original = originalAltFocusable ?: return flags
        originalAltFocusable = null
        return if (original) flags or altFocusableFlag else flags and altFocusableFlag.inv()
    }
}
