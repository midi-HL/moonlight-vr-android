package com.limelight

/** Whether the window copy of a physical key is already owned by accessibility. */
internal object KeyboardAccessibilityInputPolicy {
    fun shouldSuppressWindowEvent(
        fromAccessibility: Boolean,
        physicalDevice: Boolean,
        systemKey: Boolean,
        serviceConnected: Boolean,
        intercepting: Boolean,
        callbackIsOwner: Boolean,
    ): Boolean = !fromAccessibility && physicalDevice && !systemKey &&
        serviceConnected && intercepting && callbackIsOwner
}
