package com.limelight.vr

/** Only constrained multi-window hosts are rejected; fullscreen rotation remains asynchronous. */
internal object VrWindowPolicy {
    fun rejectsMultiWindow(multiWindow: Boolean, landscapeConfiguration: Boolean, width: Int, height: Int): Boolean =
        multiWindow && (!landscapeConfiguration || (width > 0 && height > 0 && width <= height))
}
