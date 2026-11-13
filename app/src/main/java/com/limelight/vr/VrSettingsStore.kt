package com.limelight.vr

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/**
 * Single owner of VR preferences. Uses PreferenceManager's default file so the
 * PreferenceScreen entries in res/xml/preferences.xml and the in-VR quick panel
 * read/write exactly the same values with no mirroring.
 *
 * Storage contract: floating-point settings are persisted as integer milli-units
 * because androidx.preference.SeekBarPreference only writes Int. Boolean settings
 * are plain Boolean. [load] and [save] are the ONLY access points; no other class
 * may touch these keys directly.
 */
object VrSettingsStore {
    const val K_ENABLED = "vr_enabled"
    const val K_SCALE = "vr_screen_scale"
    const val K_DISTANCE = "vr_screen_distance"
    const val K_BRIGHTNESS = "vr_brightness"
    const val K_CURVATURE = "vr_curvature"
    const val K_HEAD = "vr_head_tracking"
    const val K_HEAD_SENS = "vr_head_sensitivity"
    const val K_DEAD_ZONE = "vr_gamepad_dead_zone"
    const val K_REPEAT = "vr_menu_repeat_ms"
    const val K_IPD = "vr_ipd_scale"
    const val K_RECENTER = "vr_recenter_on_enter"
    const val K_ZOOM = "vr_zoom_scale"
    const val K_V_FLIP = "vr_vertical_flip"
    const val K_ALIGN_LINE = "vr_center_alignment_line"

    private const val M = 1000

    fun load(context: Context): VrSettings {
        val p = prefs(context)
        return VrSettings(
            enabled = p.getBoolean(K_ENABLED, VrSettings.DEFAULT.enabled),
            screenScale = milli(p, K_SCALE, VrSettings.DEFAULT.screenScale),
            screenDistanceMeters = milli(p, K_DISTANCE, VrSettings.DEFAULT.screenDistanceMeters),
            brightness = milli(p, K_BRIGHTNESS, VrSettings.DEFAULT.brightness),
            curvature = milli(p, K_CURVATURE, VrSettings.DEFAULT.curvature),
            headTrackingEnabled = p.getBoolean(K_HEAD, VrSettings.DEFAULT.headTrackingEnabled),
            headTrackingSensitivity = milli(p, K_HEAD_SENS, VrSettings.DEFAULT.headTrackingSensitivity),
            gamepadDeadZone = milli(p, K_DEAD_ZONE, VrSettings.DEFAULT.gamepadDeadZone),
            menuRepeatMs = p.getInt(K_REPEAT, VrSettings.DEFAULT.menuRepeatMs.toInt()).toLong(),
            ipdScale = milli(p, K_IPD, VrSettings.DEFAULT.ipdScale),
            recenterOnEnter = p.getBoolean(K_RECENTER, VrSettings.DEFAULT.recenterOnEnter),
            zoomScale = milli(p, K_ZOOM, VrSettings.DEFAULT.zoomScale),
            verticalFlip = p.getBoolean(K_V_FLIP, VrSettings.DEFAULT.verticalFlip),
            showCenterAlignmentLine = p.getBoolean(K_ALIGN_LINE, VrSettings.DEFAULT.showCenterAlignmentLine),
        ).sanitized()
    }

    fun save(context: Context, value: VrSettings) {
        val s = value.sanitized()
        prefs(context).edit()
            .putBoolean(K_ENABLED, s.enabled)
            .putInt(K_SCALE, (s.screenScale * M).toInt())
            .putInt(K_DISTANCE, (s.screenDistanceMeters * M).toInt())
            .putInt(K_BRIGHTNESS, (s.brightness * M).toInt())
            .putInt(K_CURVATURE, (s.curvature * M).toInt())
            .putBoolean(K_HEAD, s.headTrackingEnabled)
            .putInt(K_HEAD_SENS, (s.headTrackingSensitivity * M).toInt())
            .putInt(K_DEAD_ZONE, (s.gamepadDeadZone * M).toInt())
            .putInt(K_REPEAT, s.menuRepeatMs.toInt())
            .putInt(K_IPD, (s.ipdScale * M).toInt())
            .putBoolean(K_RECENTER, s.recenterOnEnter)
            .putInt(K_ZOOM, (s.zoomScale * M).toInt())
            .putBoolean(K_V_FLIP, s.verticalFlip)
            .putBoolean(K_ALIGN_LINE, s.showCenterAlignmentLine)
            .apply()
    }

    /** Applies one PreferenceScreen key change (all VR keys funnel through here). */
    fun onPreferenceChanged(context: Context, key: String) {
        if (!isVrKey(key)) return
        VrSession.reloadFromStore(context)
    }

    fun isVrKey(key: String?): Boolean = when (key) {
        K_ENABLED, K_SCALE, K_DISTANCE, K_BRIGHTNESS, K_CURVATURE,
        K_HEAD, K_HEAD_SENS, K_DEAD_ZONE, K_REPEAT, K_IPD, K_RECENTER,
        K_ZOOM, K_V_FLIP, K_ALIGN_LINE -> true
        else -> false
    }

    private fun prefs(context: Context): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    private fun milli(p: SharedPreferences, key: String, fallback: Float): Float =
        p.getInt(key, (fallback * M).toInt()).toFloat() / M
}

/** Keys referenced by res/xml/preferences.xml; kept in one place for both UIs. */
object VrPreferenceKeys {
    const val ENABLED = VrSettingsStore.K_ENABLED
    const val SCALE = VrSettingsStore.K_SCALE
    const val DISTANCE = VrSettingsStore.K_DISTANCE
    const val BRIGHTNESS = VrSettingsStore.K_BRIGHTNESS
    const val CURVATURE = VrSettingsStore.K_CURVATURE
    const val HEAD_TRACKING = VrSettingsStore.K_HEAD
    const val HEAD_SENSITIVITY = VrSettingsStore.K_HEAD_SENS
    const val DEAD_ZONE = VrSettingsStore.K_DEAD_ZONE
    const val REPEAT = VrSettingsStore.K_REPEAT
    const val IPD = VrSettingsStore.K_IPD
    const val RECENTER = VrSettingsStore.K_RECENTER
    const val ZOOM = VrSettingsStore.K_ZOOM
    const val VERTICAL_FLIP = VrSettingsStore.K_V_FLIP
    const val ALIGN_LINE = VrSettingsStore.K_ALIGN_LINE
}
