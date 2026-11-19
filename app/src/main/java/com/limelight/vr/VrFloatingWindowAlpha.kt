package com.limelight.vr

/** Reversibly hides a WindowManager opacity value, used independently for alpha and dimAmount. */
internal class VrFloatingWindowAlpha(private val hiddenValue: Float = 0f) {
    private var original: Float? = null

    /** The offscreen copy retains the caller's opacity while the native window is hidden. */
    fun valueForCapture(current: Float): Float = original ?: current

    fun update(current: Float, hidden: Boolean): Float? {
        if (hidden) {
            // A caller may adjust its window's opacity after showing it.
            if (original == null || current != hiddenValue) original = current
            return if (current == hiddenValue) null else hiddenValue
        }
        val saved = original ?: return null
        original = null
        return if (current == saved) null else saved
    }
}
