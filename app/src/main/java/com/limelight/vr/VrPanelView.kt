package com.limelight.vr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import com.limelight.R

/** Draws the controller menu onto its own head-relative stereo layer. */
class VrPanelView(context: Context) : View(context) {
    private val shape = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.25f
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private var widthPx = 1024
    private var heightPx = 576

    fun layoutFor(w: Int, h: Int) {
        widthPx = w
        heightPx = h
    }

    fun drawInto(canvas: Canvas, settings: VrSettings, selectedIndex: Int, editing: Boolean) {
        val save = canvas.save()
        canvas.scale(widthPx / 1024f, heightPx / 576f)
        drawPanelBackground(canvas)
        round(canvas, 170f, 44f, 226f, 100f, 16f, focusFill)
        drawHeadsetMark(canvas, 198f, 72f)
        round(canvas, 170f, 112f, 854f, 113.5f, 0.75f, Color.argb(34, 197, 238, 242))
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
            val iconColor = if (selected) accent else iconMuted
            round(canvas, 185f, y + 9f, 220f, y + 40f, 9f,
                if (selected) Color.argb(40, 141, 231, 218) else Color.argb(24, 177, 197, 216))
            drawRowIcon(canvas, row.key, 202.5f, y + 24.5f, iconColor)
            val label = context.getString(rowLabel(row.key))
            val current = VrQuickPanel.valueOf(settings, row.key)
            val color = if (row.key == "vr_exit") exitAccent else primaryText
            line(canvas, label, 232f, y + 33f, if (row.kind == VrQuickPanel.Kind.RANGE) 350f else 425f,
                24f, color, selected)
            when (row.kind) {
                VrQuickPanel.Kind.RANGE -> {
                    val fraction = ((current - row.min) / (row.max - row.min)).coerceIn(0f, 1f)
                    round(canvas, 594f, y + 21f, 715f, y + 28f, 3.5f, track)
                    if (fraction > 0f) round(canvas, 594f, y + 21f, 594f + fraction * 121f, y + 28f, 3.5f, accent)
                    round(canvas, 731f, y + 7f, 834f, y + 42f, 10f, if (isEditing) accent else chip)
                    right(canvas, row.format(current), 823f, y + 32f, 20f, if (isEditing) surfaceMid else primaryText, true)
                }
                VrQuickPanel.Kind.TOGGLE -> {
                    val on = current >= 0.5f
                    val state = context.getString(if (on) R.string.vr_panel_on else R.string.vr_panel_off)
                    right(canvas, state, 761f, y + 32f, 19f, secondary)
                    round(canvas, 773f, y + 12f, 832f, y + 38f, 13f, if (on) accent else track)
                    shape.color = if (on) surfaceMid else secondary
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

    private fun drawPanelBackground(canvas: Canvas) {
        val bounds = RectF(142f, 18f, 882f, 558f)
        val clip = Path().apply { addRoundRect(bounds, 30f, 30f, Path.Direction.CW) }
        val save = canvas.save()
        canvas.clipPath(clip)

        backgroundPaint.shader = LinearGradient(
            142f, 18f, 882f, 558f, intArrayOf(surfaceTop, surfaceMid, surfaceBottom),
            floatArrayOf(0f, 0.54f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(bounds, backgroundPaint)
        backgroundPaint.shader = RadialGradient(
            238f, 67f, 460f, intArrayOf(Color.argb(52, 55, 183, 177), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(bounds, backgroundPaint)
        backgroundPaint.shader = RadialGradient(
            850f, 535f, 360f, intArrayOf(Color.argb(36, 120, 103, 210), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(bounds, backgroundPaint)
        backgroundPaint.shader = null

        // Fine, low-contrast lines add depth without competing with the controls.
        shape.color = Color.argb(10, 206, 237, 242)
        for (x in 166..858 step 48) canvas.drawLine(x.toFloat(), 130f, x.toFloat(), 478f, shape)
        for (y in 142..478 step 48) canvas.drawLine(166f, y.toFloat(), 856f, y.toFloat(), shape)
        canvas.restoreToCount(save)

        border.color = Color.argb(76, 181, 224, 228)
        canvas.drawRoundRect(bounds, 30f, 30f, border)
        border.color = Color.argb(24, 255, 255, 255)
        canvas.drawRoundRect(RectF(149f, 25f, 875f, 551f), 24f, 24f, border)
    }

    private fun drawHeadsetMark(canvas: Canvas, centerX: Float, centerY: Float) {
        iconPaint.color = accent
        iconPaint.strokeWidth = 2.8f
        canvas.drawArc(centerX - 13f, centerY - 11f, centerX + 13f, centerY + 12f,
            202f, 136f, false, iconPaint)
        canvas.drawRoundRect(centerX - 13f, centerY - 1f, centerX - 4f, centerY + 8f, 3.5f, 3.5f, iconPaint)
        canvas.drawRoundRect(centerX + 4f, centerY - 1f, centerX + 13f, centerY + 8f, 3.5f, 3.5f, iconPaint)
        canvas.drawLine(centerX - 4f, centerY + 3.5f, centerX + 4f, centerY + 3.5f, iconPaint)
        iconPaint.strokeWidth = 2.4f
    }

    private fun drawRowIcon(canvas: Canvas, key: String, cx: Float, cy: Float, color: Int) {
        iconPaint.color = color
        val l = cx - 9f
        val t = cy - 9f
        val r = cx + 9f
        val b = cy + 9f
        when (key) {
            VrSettingsStore.K_ENABLED -> {
                canvas.drawRoundRect(l, cy - 5f, r, cy + 5f, 4f, 4f, iconPaint)
                canvas.drawLine(cx, cy - 5f, cx, cy + 5f, iconPaint)
            }
            VrSettingsStore.K_SCALE -> {
                canvas.drawLine(cx - 6f, cy + 6f, cx + 6f, cy - 6f, iconPaint)
                canvas.drawLine(cx + 1f, cy - 6f, cx + 6f, cy - 6f, iconPaint)
                canvas.drawLine(cx + 6f, cy - 6f, cx + 6f, cy - 1f, iconPaint)
                canvas.drawLine(cx - 1f, cy + 6f, cx - 6f, cy + 6f, iconPaint)
                canvas.drawLine(cx - 6f, cy + 6f, cx - 6f, cy + 1f, iconPaint)
            }
            VrSettingsStore.K_DISTANCE -> {
                canvas.drawCircle(cx, cy, 6.5f, iconPaint)
                canvas.drawCircle(cx, cy, 2f, iconPaint)
                canvas.drawLine(cx - 9f, cy, cx - 7f, cy, iconPaint)
                canvas.drawLine(cx + 7f, cy, cx + 9f, cy, iconPaint)
            }
            VrSettingsStore.K_BRIGHTNESS -> {
                canvas.drawCircle(cx, cy, 4f, iconPaint)
                for (i in 0..7) {
                    val angle = Math.PI * i / 4.0
                    val x1 = cx + kotlin.math.cos(angle).toFloat() * 6.5f
                    val y1 = cy + kotlin.math.sin(angle).toFloat() * 6.5f
                    val x2 = cx + kotlin.math.cos(angle).toFloat() * 9f
                    val y2 = cy + kotlin.math.sin(angle).toFloat() * 9f
                    canvas.drawLine(x1, y1, x2, y2, iconPaint)
                }
            }
            VrSettingsStore.K_CURVATURE -> {
                val path = Path().apply { moveTo(l, b - 1f); cubicTo(cx - 5f, cy - 4f, cx + 3f, cy - 4f, r, t + 1f) }
                canvas.drawPath(path, iconPaint)
                canvas.drawLine(l, cy, r, cy, iconPaint)
            }
            VrSettingsStore.K_HEAD -> {
                canvas.drawCircle(cx, cy, 6f, iconPaint)
                canvas.drawCircle(cx, cy, 2f, iconPaint)
                canvas.drawArc(l, t, r, b, 205f, 130f, false, iconPaint)
            }
            VrSettingsStore.K_HEAD_SENS -> {
                canvas.drawLine(l, cy - 5f, r, cy - 5f, iconPaint)
                canvas.drawLine(l, cy + 5f, r, cy + 5f, iconPaint)
                canvas.drawCircle(cx - 2f, cy - 5f, 2.4f, iconPaint)
                canvas.drawCircle(cx + 4f, cy + 5f, 2.4f, iconPaint)
            }
            VrSettingsStore.K_IPD -> {
                canvas.drawCircle(cx - 4.5f, cy, 3.2f, iconPaint)
                canvas.drawCircle(cx + 4.5f, cy, 3.2f, iconPaint)
                canvas.drawLine(cx - 1f, cy, cx + 1f, cy, iconPaint)
            }
            VrSettingsStore.K_DEAD_ZONE -> {
                canvas.drawCircle(cx, cy, 7f, iconPaint)
                canvas.drawCircle(cx, cy, 2.5f, iconPaint)
            }
            VrSettingsStore.K_REPEAT -> {
                canvas.drawArc(l, t, r, b, 205f, 125f, false, iconPaint)
                canvas.drawLine(cx + 6f, cy - 6f, cx + 9f, cy - 6f, iconPaint)
                canvas.drawLine(cx + 9f, cy - 6f, cx + 8f, cy - 3f, iconPaint)
                canvas.drawArc(l, t, r, b, 25f, 125f, false, iconPaint)
                canvas.drawLine(cx - 6f, cy + 6f, cx - 9f, cy + 6f, iconPaint)
                canvas.drawLine(cx - 9f, cy + 6f, cx - 8f, cy + 3f, iconPaint)
            }
            VrSettingsStore.K_RECENTER, "vr_recenter_now" -> {
                canvas.drawCircle(cx, cy, 7.5f, iconPaint)
                canvas.drawCircle(cx, cy, 2f, iconPaint)
                canvas.drawLine(cx - 10f, cy, cx - 7.5f, cy, iconPaint)
                canvas.drawLine(cx + 7.5f, cy, cx + 10f, cy, iconPaint)
                canvas.drawLine(cx, cy - 10f, cx, cy - 7.5f, iconPaint)
                canvas.drawLine(cx, cy + 7.5f, cx, cy + 10f, iconPaint)
            }
            VrSettingsStore.K_ZOOM -> {
                canvas.drawCircle(cx - 2f, cy - 2f, 5f, iconPaint)
                canvas.drawLine(cx + 2f, cy + 2f, cx + 8f, cy + 8f, iconPaint)
                canvas.drawLine(cx - 2f, cy - 5f, cx - 2f, cy + 1f, iconPaint)
                canvas.drawLine(cx - 5f, cy - 2f, cx + 1f, cy - 2f, iconPaint)
            }
            VrSettingsStore.K_V_FLIP -> {
                canvas.drawLine(cx, t, cx, b, iconPaint)
                canvas.drawLine(cx, t, cx - 3f, t + 3f, iconPaint)
                canvas.drawLine(cx, t, cx + 3f, t + 3f, iconPaint)
                canvas.drawLine(cx, b, cx - 3f, b - 3f, iconPaint)
                canvas.drawLine(cx, b, cx + 3f, b - 3f, iconPaint)
            }
            VrSettingsStore.K_ALIGN_LINE -> {
                canvas.drawCircle(cx, cy, 6f, iconPaint)
                canvas.drawLine(cx, t, cx, cy - 3f, iconPaint)
                canvas.drawLine(cx, cy + 3f, cx, b, iconPaint)
                canvas.drawLine(l, cy, cx - 3f, cy, iconPaint)
                canvas.drawLine(cx + 3f, cy, r, cy, iconPaint)
            }
            "vr_exit" -> {
                canvas.drawRoundRect(l, t, r, b, 3f, 3f, iconPaint)
                canvas.drawLine(cx + 1f, cy, cx + 9f, cy, iconPaint)
                canvas.drawLine(cx + 9f, cy, cx + 5f, cy - 4f, iconPaint)
                canvas.drawLine(cx + 9f, cy, cx + 5f, cy + 4f, iconPaint)
            }
            else -> canvas.drawCircle(cx, cy, 6f, iconPaint)
        }
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
        val surfaceTop = Color.rgb(17, 28, 40)
        val surfaceMid = Color.rgb(19, 25, 38)
        val surfaceBottom = Color.rgb(13, 17, 29)
        val focusFill = Color.rgb(37, 52, 66)
        val editingFill = Color.rgb(38, 61, 67)
        val primaryText = Color.rgb(246, 249, 252)
        val secondary = Color.rgb(181, 198, 214)
        val iconMuted = Color.rgb(154, 183, 198)
        val accent = Color.rgb(142, 235, 211)
        val exitAccent = Color.rgb(255, 191, 185)
        val track = Color.rgb(66, 83, 98)
        val chip = Color.rgb(44, 59, 72)
    }
}
