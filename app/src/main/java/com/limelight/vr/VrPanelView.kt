package com.limelight.vr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.roundToInt

/**
 * In-VR quick settings panel. Deliberately NOT attached to the window: it is
 * laid out manually and drawn into the same offscreen Canvas as the decor by
 * [VrWindowCapture], so it appears in BOTH eyes without a second GL surface.
 * The rows and values come from [VrQuickPanel] / [VrSession], i.e. the same
 * SharedPreferences the 2D StreamSettings page edits.
 */
class VrPanelView(context: Context) : View(context) {
    private val card = RectF()
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(232, 14, 18, 28) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 90, 150, 220); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 34f; isFakeBoldText = true
    }
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 235, 242, 255); textSize = 28f }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(255, 120, 205, 255); textSize = 28f }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 190, 205, 225); textSize = 22f }
    private val selected = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 70, 140, 220) }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(255, 90, 190, 255) }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 255, 255, 255) }

    var widthPx = 1280
    var heightPx = 720

    fun layoutFor(w: Int, h: Int) {
        widthPx = w
        heightPx = h
        val cw = (w * 0.82f).coerceAtMost(1100f)
        val ch = (h * 0.84f).coerceAtMost(620f)
        card.set((w - cw) / 2f, (h - ch) / 2f, (w + cw) / 2f, (h + ch) / 2f)
    }

    /** Draws the panel into an already-translated Canvas of size widthPx x heightPx. */
    fun drawInto(canvas: Canvas, settings: VrSettings, selectedIndex: Int, editing: Boolean) {
        canvas.drawRoundRect(card, 28f, 28f, bg)
        canvas.drawRoundRect(card, 28f, 28f, stroke)

        var y = card.top + 58f
        canvas.drawText("VR 快捷设置", card.left + 44f, y, title)
        canvas.drawText("长按 START+SELECT 或 MODE 开关 · D-pad 选择 · A 确认/编辑 · 摇杆调值 · B 关闭",
            card.left + 44f, y + 34f, hint)

        y += 78f
        val rows = VrQuickPanel.rows
        val footerRoom = 40f
        val visibleRows = (((card.bottom - footerRoom - y) / 46f).toInt())
            .coerceIn(1, VrQuickPanel.VISIBLE_ROWS)
        val window = VrQuickPanel.visibleWindowFor(selectedIndex, visibleRows)
        for (i in window) {
            val row = rows[i]
            val rowTop = y - 34f
            val rowBottom = y + 12f
            if (i == selectedIndex) {
                canvas.drawRoundRect(
                    RectF(card.left + 24f, rowTop, card.right - 24f, rowBottom), 12f, 12f, selected,
                )
            }
            val mark = if (i == selectedIndex) "▶ " else "   "
            canvas.drawText(mark + row.label, card.left + 44f, y, body)
            val right = when (row.kind) {
                VrQuickPanel.Kind.RANGE -> {
                    val fraction = ((VrQuickPanel.valueOf(settings, row.key) - row.min) / (row.max - row.min))
                        .coerceIn(0f, 1f)
                    val barLeft = card.right - 330f
                    val barRight = card.right - 150f
                    canvas.drawRoundRect(RectF(barLeft, y - 12f, barRight, y - 4f), 4f, 4f, track)
                    canvas.drawRoundRect(RectF(barLeft, y - 12f, barLeft + (barRight - barLeft) * fraction, y - 4f),
                        4f, 4f, bar)
                    (if (editing && i == selectedIndex) "[ " else "") +
                        row.format(VrQuickPanel.valueOf(settings, row.key)) +
                        (if (editing && i == selectedIndex) " ]" else "")
                }
                VrQuickPanel.Kind.TOGGLE ->
                    if (VrQuickPanel.valueOf(settings, row.key) >= 0.5f) "开" else "关"
                VrQuickPanel.Kind.ACTION -> "执行"
            }
            canvas.drawText(right, card.right - 44f - value.measureText(right), y, value)
            y += 46f
        }

        // P-F1: explicit scroll affordance so rows beyond the visible window are
        // discoverable instead of silently clipped.
        if (rows.size > visibleRows) {
            val trackTop = card.top + 136f
            val trackBottom = card.bottom - footerRoom
            val trackX = card.right - 18f
            canvas.drawRoundRect(RectF(trackX - 3f, trackTop, trackX + 3f, trackBottom), 3f, 3f, track)
            val thumbH = (trackBottom - trackTop) * visibleRows / rows.size
            val thumbTop = trackTop + (trackBottom - trackTop - thumbH) *
                window.first / (rows.size - visibleRows).coerceAtLeast(1)
            canvas.drawRoundRect(RectF(trackX - 5f, thumbTop, trackX + 5f, thumbTop + thumbH), 5f, 5f, bar)
        }
        val footer = "${window.first + 1}-${window.last + 1}/${rows.size}  ·  " +
            "修改即时生效，无需退出串流"
        canvas.drawText(footer, card.left + 44f, card.bottom - 28f, hint)
    }

    @Deprecated("Use drawInto; this View is never attached to a window.")
    override fun draw(canvas: Canvas) {
        drawInto(canvas, VrSession.settings, VrSession.selectedIndex, VrSession.editing)
    }
}
