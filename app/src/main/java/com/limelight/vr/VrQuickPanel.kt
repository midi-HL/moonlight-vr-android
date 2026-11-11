package com.limelight.vr

import kotlin.math.roundToInt

/**
 * Rows of the in-VR quick settings panel. The panel is a real Android View added
 * to the decor, so it is captured by the whole-window projection automatically;
 * this file only owns the pure data/selection logic and is JVM-testable.
 */
object VrQuickPanel {
    const val VISIBLE_ROWS = 8

    enum class Kind { TOGGLE, RANGE, ACTION }

    data class Row(
        val key: String,
        val label: String,
        val kind: Kind,
        val min: Float = 0f,
        val max: Float = 1f,
        val step: Float = 0.05f,
        val format: (Float) -> String = { "%.2f".format(it) },
    )

    val rows: List<Row> = listOf(
        Row(VrSettingsStore.K_ENABLED, "VR 视图", Kind.TOGGLE),
        Row(VrSettingsStore.K_SCALE, "幕布大小", Kind.RANGE, 0.5f, 2.2f, 0.05f) { "%.2fx".format(it) },
        Row(VrSettingsStore.K_DISTANCE, "幕布距离", Kind.RANGE, 0.8f, 5f, 0.1f) { "%.2fm".format(it) },
        Row(VrSettingsStore.K_BRIGHTNESS, "画面亮度", Kind.RANGE, 0.3f, 1f, 0.05f) { "%.2f".format(it) },
        Row(VrSettingsStore.K_CURVATURE, "幕布弧度", Kind.RANGE, 0f, 1f, 0.05f) { "%.2f".format(it) },
        Row(VrSettingsStore.K_HEAD, "头部追踪", Kind.TOGGLE),
        Row(VrSettingsStore.K_HEAD_SENS, "追踪灵敏度", Kind.RANGE, 0.25f, 2f, 0.05f) { "%.2fx".format(it) },
        Row(VrSettingsStore.K_IPD, "等效瞳距", Kind.RANGE, 0.5f, 1.6f, 0.02f) { "%.2fx".format(it) },
        Row(VrSettingsStore.K_DEAD_ZONE, "手柄死区", Kind.RANGE, 0.05f, 0.5f, 0.01f) { "%.2f".format(it) },
        Row(VrSettingsStore.K_REPEAT, "按键重复", Kind.RANGE, 60f, 400f, 20f) { "%.0fms".format(it) },
        Row(VrSettingsStore.K_RECENTER, "进入时重置中心", Kind.TOGGLE),
        Row("vr_recenter_now", "立即重置中心点", Kind.ACTION),
        Row("vr_exit", "退出 VR", Kind.ACTION),
    )

    fun valueOf(settings: VrSettings, key: String): Float = when (key) {
        VrSettingsStore.K_ENABLED -> if (settings.enabled) 1f else 0f
        VrSettingsStore.K_SCALE -> settings.screenScale
        VrSettingsStore.K_DISTANCE -> settings.screenDistanceMeters
        VrSettingsStore.K_BRIGHTNESS -> settings.brightness
        VrSettingsStore.K_CURVATURE -> settings.curvature
        VrSettingsStore.K_HEAD -> if (settings.headTrackingEnabled) 1f else 0f
        VrSettingsStore.K_HEAD_SENS -> settings.headTrackingSensitivity
        VrSettingsStore.K_IPD -> settings.ipdScale
        VrSettingsStore.K_DEAD_ZONE -> settings.gamepadDeadZone
        VrSettingsStore.K_REPEAT -> settings.menuRepeatMs.toFloat()
        VrSettingsStore.K_RECENTER -> if (settings.recenterOnEnter) 1f else 0f
        else -> 0f
    }

    fun withValue(settings: VrSettings, key: String, value: Float): VrSettings {
        val v = value.coerceIn(0f, 2.2f)
        return when (key) {
            VrSettingsStore.K_ENABLED -> settings.copy(enabled = v >= 0.5f)
            VrSettingsStore.K_SCALE -> settings.copy(screenScale = v)
            VrSettingsStore.K_DISTANCE -> settings.copy(screenDistanceMeters = v)
            VrSettingsStore.K_BRIGHTNESS -> settings.copy(brightness = v)
            VrSettingsStore.K_CURVATURE -> settings.copy(curvature = v)
            VrSettingsStore.K_HEAD -> settings.copy(headTrackingEnabled = v >= 0.5f)
            VrSettingsStore.K_HEAD_SENS -> settings.copy(headTrackingSensitivity = v)
            VrSettingsStore.K_IPD -> settings.copy(ipdScale = v)
            VrSettingsStore.K_DEAD_ZONE -> settings.copy(gamepadDeadZone = v)
            VrSettingsStore.K_REPEAT -> settings.copy(menuRepeatMs = v.toLong())
            VrSettingsStore.K_RECENTER -> settings.copy(recenterOnEnter = v >= 0.5f)
            else -> settings
        }.sanitized()
    }

    fun labelOf(row: Row, settings: VrSettings): String = when (row.kind) {
        Kind.ACTION -> ""
        Kind.TOGGLE -> if (valueOf(settings, row.key) >= 0.5f) "开" else "关"
        Kind.RANGE -> row.format(valueOf(settings, row.key))
    }

    /** Selection window so a growing row list never gets clipped. Pure, stateless. */
    fun visibleWindowFor(selected: Int, visibleRows: Int = VISIBLE_ROWS): IntRange {
        val count = visibleRows.coerceIn(1, rows.size)
        val first = (selected - count + 1).coerceIn(0, rows.size - count)
        return first until first + count
    }

    fun adjust(row: Row, settings: VrSettings, steps: Int): VrSettings {
        if (row.kind != Kind.RANGE) return settings
        val current = valueOf(settings, row.key)
        val snapped = ((current + steps * row.step) / row.step).roundToInt() * row.step
        return withValue(settings, row.key, snapped.coerceIn(row.min, row.max))
    }

    fun toggle(row: Row, settings: VrSettings): VrSettings = when (row.kind) {
        Kind.TOGGLE -> withValue(settings, row.key, if (valueOf(settings, row.key) >= 0.5f) 0f else 1f)
        else -> settings
    }
}
