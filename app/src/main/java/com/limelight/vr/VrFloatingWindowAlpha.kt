package com.limelight.vr

/** Reversibly hides a WindowManager opacity value, used independently for alpha and dimAmount. */
internal class VrFloatingWindowAlpha {
    private var original: Float? = null

    fun update(current: Float, hidden: Boolean): Float? {
        if (hidden) {
            // A caller may adjust its window's opacity after showing it.
            if (original == null || current != 0f) original = current
            return if (current == 0f) null else 0f
        }
        val saved = original ?: return null
        original = null
        return if (current == saved) null else saved
    }
}
