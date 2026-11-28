package com.limelight.vr

/** Central policy for app-owned controls that can open Android's local input method. */
internal object VrLocalImePolicy {
    fun isAvailable(vrEnabled: Boolean): Boolean = !vrEnabled
}
