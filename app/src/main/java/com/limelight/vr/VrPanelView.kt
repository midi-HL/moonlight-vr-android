package com.limelight.vr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import androidx.core.content.ContextCompat
import com.limelight.R

/** Draws the controller menu onto its own head-relative stereo layer. */
class VrPanelView(context: Context) : View(context) {
    private val shape = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val icon = ContextCompat.getDrawable(context, R.drawable.ic_vr_headset)?.mutate()
    private var widthPx = 1024
    private var heightPx = 576

    fun layoutFor(w: Int, h: Int) {
        widthPx = w
        heightPx = h
    }

    fun drawInto(canvas: Canvas, settings: VrSettings, selectedIndex: Int, editing: Boolean) {
        val save = canvas.save()
        canvas.scale(widthPx / 1024f, heightPx / 576f)
        round(canvas, 142f, 18f, 882f, 558f, 30f, surface)
        round(canvas, 170f, 44f, 226f, 100f, 16f, focusFill)
        icon?.apply {
            setTint(accent)
            setBounds(181, 55, 215, 89)
            draw(canvas)
        }
        line(canvas, context.getString(R.string.vr_panel_title), 244f, 79f, 450f, 35f, primaryText, true)
        val index = selectedIndex.coerceIn(VrQuickPanel.rows.indices)
        val window = VrQuickPanel.visibleWindowFor(index, 6)
        val section = context.getString(sectionLabel(VrQuickPanel.rows[index].key))
        line(canvas, section, 174f, 137f, 520f, 20f, secondary, true)
        val page = context.getString(R.string.vr_panel_position, index + 1, VrQuickPanel.rows.size)
        right(canvas, page, 842f, 137f, 19f, secondary)

        window.forEachIndexed { offset, i ->
            val row = VrQuickPanel.rows[i]
            val y = 151f + offset * 54f
            val selected = i == index
            val isEditing = selected && editing && row.kind == VrQuickPanel.Kind.RANGE
            if (selected) {
                round(canvas, 166f, y, 848f, y + 49f, 15f, if (isEditing) editingFill else focusFill)
                round(canvas, 174f, y + 10f, 180f, y + 39f, 3f, accent)
            }
            val label = context.getString(rowLabel(row.key))
            val current = VrQuickPanel.valueOf(settings, row.key)
            val color = if (row.key == "vr_exit") exitAccent else primaryText
            line(canvas, label, 192f, y + 33f, if (row.kind == VrQuickPanel.Kind.RANGE) 390f else 465f,
                24f, color, selected)
            when (row.kind) {
                VrQuickPanel.Kind.RANGE -> {
                    val fraction = ((current - row.min) / (row.max - row.min)).coerceIn(0f, 1f)
                    round(canvas, 594f, y + 21f, 715f, y + 28f, 3.5f, track)
                    if (fraction > 0f) round(canvas, 594f, y + 21f, 594f + fraction * 121f, y + 28f, 3.5f, accent)
                    round(canvas, 731f, y + 7f, 834f, y + 42f, 10f, if (isEditing) accent else chip)
                    right(canvas, row.format(current), 823f, y + 32f, 20f, if (isEditing) surface else primaryText, true)
                }
                VrQuickPanel.Kind.TOGGLE -> {
                    val on = current >= 0.5f
                    val state = context.getString(if (on) R.string.vr_panel_on else R.string.vr_panel_off)
                    right(canvas, state, 761f, y + 32f, 19f, secondary)
                    round(canvas, 773f, y + 12f, 832f, y + 38f, 13f, if (on) accent else track)
                    shape.color = if (on) surface else secondary
                    canvas.drawCircle(if (on) 818f else 787f, y + 25f, 9f, shape)
                }
                VrQuickPanel.Kind.ACTION -> {
                    right(canvas, "A", 818f, y + 33f, 23f, if (selected) accent else secondary, true)
                }
            }
        }

        val marks = VrQuickPanel.rows.size - window.count()
        if (marks > 0) {
            val height = 312f
            val thumb = height * window.count() / VrQuickPanel.rows.size
            val y = 153f + (height - thumb) * window.first / marks
            round(canvas, 858f, 153f, 862f, 465f, 2f, track)
            round(canvas, 858f, y, 862f, y + thumb, 2f, accent)
        }
        rule(canvas, 169f, 488f, 855f)
        val hint = if (editing) R.string.vr_panel_edit_hint else R.string.vr_panel_hint
        line(canvas, context.getString(hint), 174f, 527f, 672f, 21f, secondary, true)
        canvas.restoreToCount(save)
    }

    private fun sectionLabel(key: String): Int = when (key) {
        VrSettingsStore.K_HEAD, VrSettingsStore.K_HEAD_SENS, VrSettingsStore.K_IPD, VrSettingsStore.K_RECENTER ->
            R.string.vr_panel_tracking
        VrSettingsStore.K_DEAD_ZONE, VrSettingsStore.K_REPEAT -> R.string.vr_panel_controller
        "vr_recenter_now", "vr_exit", VrSettingsStore.K_ENABLED -> R.string.vr_panel_session
        else -> R.string.vr_panel_display
    }

    private fun rowLabel(key: String): Int = when (key) {
        VrSettingsStore.K_ENABLED -> R.string.title_vr_enabled
        VrSettingsStore.K_SCALE -> R.string.title_vr_scale
        VrSettingsStore.K_DISTANCE -> R.string.title_vr_distance
        VrSettingsStore.K_BRIGHTNESS -> R.string.title_vr_brightness
        VrSettingsStore.K_CURVATURE -> R.string.title_vr_curvature
        VrSettingsStore.K_HEAD -> R.string.title_vr_head_tracking
        VrSettingsStore.K_HEAD_SENS -> R.string.title_vr_head_sensitivity
        VrSettingsStore.K_IPD -> R.string.title_vr_ipd
        VrSettingsStore.K_DEAD_ZONE -> R.string.title_vr_dead_zone
        VrSettingsStore.K_REPEAT -> R.string.title_vr_repeat
        VrSettingsStore.K_RECENTER -> R.string.title_vr_recenter_on_enter
        VrSettingsStore.K_ZOOM -> R.string.vr_panel_zoom
        VrSettingsStore.K_V_FLIP -> R.string.title_vr_vertical_flip
        VrSettingsStore.K_ALIGN_LINE -> R.string.title_vr_align_line
        "vr_recenter_now" -> R.string.vr_panel_recenter
        "vr_exit" -> R.string.vr_panel_exit
        else -> R.string.vr_panel_title
    }

    private fun round(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, radius: Float, color: Int) {
        shape.color = color
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, shape)
    }

    private fun rule(canvas: Canvas, x: Float, y: Float, right: Float) {
        shape.color = track
        canvas.drawRect(x, y, right, y + 1f, shape)
    }

    private fun textStyle(fontSize: Float, color: Int, strong: Boolean) {
        text.typeface = if (strong) medium else regular
        text.textSize = fontSize
        text.color = color
    }

    private fun line(canvas: Canvas, label: String, x: Float, y: Float, maxWidth: Float, size: Float,
                     color: Int, strong: Boolean = false) {
        textStyle(size, color, strong)
        canvas.drawText(TextUtils.ellipsize(label, text, maxWidth, TextUtils.TruncateAt.END).toString(), x, y, text)
    }

    private fun right(canvas: Canvas, label: String, x: Float, y: Float, size: Float, color: Int, strong: Boolean = false) {
        textStyle(size, color, strong)
        canvas.drawText(label, x - text.measureText(label), y, text)
    }

    private companion object {
        val surface = Color.rgb(17, 22, 31)
        val focusFill = Color.rgb(37, 52, 66)
        val editingFill = Color.rgb(38, 61, 67)
        val primaryText = Color.rgb(246, 249, 252)
        val secondary = Color.rgb(181, 198, 214)
        val accent = Color.rgb(142, 235, 211)
        val exitAccent = Color.rgb(255, 191, 185)
        val track = Color.rgb(66, 83, 98)
        val chip = Color.rgb(44, 59, 72)
    }
}
