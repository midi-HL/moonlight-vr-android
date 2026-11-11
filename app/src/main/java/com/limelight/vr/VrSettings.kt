package com.limelight.vr

/**
 * All VR preferences in one immutable snapshot. Every field maps to a real
 * [android.content.SharedPreferences] key owned by VrSettingsStore, so the 2D
 * StreamSettings screen and the in-VR panel always read the same values.
 */
data class VrSettings(
    val enabled: Boolean = DEFAULT.enabled,
    val screenScale: Float = DEFAULT.screenScale,
    val screenDistanceMeters: Float = DEFAULT.screenDistanceMeters,
    val brightness: Float = DEFAULT.brightness,
    val curvature: Float = DEFAULT.curvature,
    val headTrackingEnabled: Boolean = DEFAULT.headTrackingEnabled,
    val headTrackingSensitivity: Float = DEFAULT.headTrackingSensitivity,
    val gamepadDeadZone: Float = DEFAULT.gamepadDeadZone,
    val menuRepeatMs: Long = DEFAULT.menuRepeatMs,
    val ipdScale: Float = DEFAULT.ipdScale,
    val recenterOnEnter: Boolean = DEFAULT.recenterOnEnter,
) {
    fun sanitized(): VrSettings = copy(
        screenScale = screenScale.coerceIn(MIN_SCALE, MAX_SCALE),
        screenDistanceMeters = screenDistanceMeters.coerceIn(MIN_DISTANCE, MAX_DISTANCE),
        brightness = brightness.coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS),
        curvature = curvature.coerceIn(0f, 1f),
        headTrackingSensitivity = headTrackingSensitivity.coerceIn(0.25f, 2f),
        gamepadDeadZone = gamepadDeadZone.coerceIn(0.05f, 0.5f),
        menuRepeatMs = menuRepeatMs.coerceIn(60L, 400L),
        ipdScale = ipdScale.coerceIn(0.5f, 1.6f),
    )

    companion object {
        val DEFAULT = VrSettings(
            enabled = false,
            screenScale = 1f,
            screenDistanceMeters = 2.2f,
            brightness = 1f,
            curvature = 0f,
            headTrackingEnabled = true,
            headTrackingSensitivity = 1f,
            gamepadDeadZone = 0.18f,
            menuRepeatMs = 140L,
            ipdScale = 1f,
            recenterOnEnter = true,
        )
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 2.2f
        const val MIN_DISTANCE = 0.8f
        const val MAX_DISTANCE = 5f
        const val MIN_BRIGHTNESS = 0.3f
        const val MAX_BRIGHTNESS = 1f
    }
}
