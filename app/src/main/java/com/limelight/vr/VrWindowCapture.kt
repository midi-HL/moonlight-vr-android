package com.limelight.vr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import kotlin.math.roundToInt

/** Captures the application and quick controls separately for the GL thread. */
class VrWindowCapture(
    private val source: View,
    private val panel: VrPanelView,
    private val isVideoHost: Boolean,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val sceneFrames = Frames()
    private val panelFrames = Frames()
    private val sourceLocation = IntArray(2)
    private val dialogLocation = IntArray(2)
    private var stopped = false
    private var lastPanelSettings: VrSettings? = null
    private var lastPanelIndex = -1
    private var lastPanelEditing = false
    private var reportedDrawFailure = false

    private val tick = object : Runnable {
        override fun run() {
            if (stopped) return
            val width = source.width
            val height = source.height
            if (width > 0 && height > 0) captureScene(width, height)
            capturePanel()
            if (!stopped) handler.postDelayed(this, FRAME_MS)
        }
    }

    init { markDirty() }

    fun markDirty() {
        if (stopped) return
        lastPanelSettings = null
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    fun stop() {
        stopped = true
        handler.removeCallbacks(tick)
        sceneFrames.close()
        panelFrames.close()
    }

    // Context loss can destroy a GL renderer while this capture remains attached.
    // Drop queued frames, allowing the producer to publish new ones on the next tick.
    fun releasePending() {
        sceneFrames.dropPending()
        panelFrames.dropPending()
        // markDirty is confined to the main thread.
        handler.post {
            lastPanelSettings = null
            markDirty()
        }
    }

    fun consume(): Bitmap? = sceneFrames.consume()
    fun consumePanel(): Bitmap? = panelFrames.consume()
    fun returnScene(bitmap: Bitmap) = sceneFrames.giveBack(bitmap)
    fun returnPanel(bitmap: Bitmap) = panelFrames.giveBack(bitmap)

    private fun captureScene(width: Int, height: Int) {
        val scale = minOf(1f, MAX_SCENE_EDGE.toFloat() / maxOf(width, height))
        val bitmap = sceneFrames.obtain((width * scale).roundToInt().coerceAtLeast(1),
            (height * scale).roundToInt().coerceAtLeast(1))
        val canvas = Canvas(bitmap)
        canvas.drawColor(0, PorterDuff.Mode.CLEAR)
        canvas.scale(scale, scale)
        try {
            // SurfaceView pixels never enter a software Canvas. Do not change its
            // visibility to exclude it: that can tear down the producer Surface.
            source.draw(canvas)
            source.getLocationOnScreen(sourceLocation)
            VrSession.captureFloatingWindows(source).forEach { floating ->
                val dialog = floating.decor
                dialog.getLocationOnScreen(dialogLocation)
                if (floating.dimAmount > 0f) {
                    canvas.drawColor(Color.argb((floating.dimAmount * 255f).roundToInt(), 0, 0, 0))
                }
                val opacity = (floating.alpha * 255f).roundToInt()
                val checkpoint = if (opacity < 255) {
                    canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), opacity)
                } else canvas.save()
                try {
                    canvas.translate((dialogLocation[0] - sourceLocation[0]).toFloat(),
                        (dialogLocation[1] - sourceLocation[1]).toFloat())
                    dialog.draw(canvas)
                } finally {
                    canvas.restoreToCount(checkpoint)
                }
            }
            sceneFrames.publish(bitmap)
            reportedDrawFailure = false
        } catch (failure: IllegalArgumentException) {
            // Device/profile-specific hardware bitmaps may reject a software canvas.
            sceneFrames.giveBack(bitmap)
            if (!reportedDrawFailure) Log.w(TAG, "Unable to draw a VR window frame", failure)
            reportedDrawFailure = true
        }
    }

    private fun capturePanel() {
        if (!VrSession.panelOpen) {
            lastPanelSettings = null
            return
        }
        val settings = VrSession.settings
        val index = VrSession.selectedIndex
        val editing = VrSession.editing
        if (settings == lastPanelSettings && index == lastPanelIndex && editing == lastPanelEditing) return
        val bitmap = panelFrames.obtain(PANEL_WIDTH, PANEL_HEIGHT)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0, PorterDuff.Mode.CLEAR)
        panel.layoutFor(PANEL_WIDTH, PANEL_HEIGHT)
        panel.drawInto(canvas, settings, index, editing)
        panelFrames.publish(bitmap)
        lastPanelSettings = settings
        lastPanelIndex = index
        lastPanelEditing = editing
    }

    // Each bitmap is owned by just one thread. GL returns it after the upload.
    private class Frames {
        private var pending: Bitmap? = null
        private var reusable: Bitmap? = null
        private var closed = false

        @Synchronized fun obtain(w: Int, h: Int): Bitmap {
            val bitmap = reusable
            reusable = null
            if (bitmap != null) {
                if (bitmap.width == w && bitmap.height == h) return bitmap
                bitmap.recycle()
            }
            return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }

        @Synchronized fun publish(bitmap: Bitmap) {
            pending?.let(::giveBack)
            pending = bitmap
        }

        @Synchronized fun consume(): Bitmap? = pending.also { pending = null }

        @Synchronized fun giveBack(bitmap: Bitmap) {
            if (closed) bitmap.recycle() else {
                reusable?.recycle()
                reusable = bitmap
            }
        }

        @Synchronized fun dropPending() {
            pending?.let(::giveBack)
            pending = null
        }

        @Synchronized fun close() {
            closed = true
            pending?.recycle()
            reusable?.recycle()
            pending = null
            reusable = null
        }
    }

    private companion object {
        const val TAG = "VrWindowCapture"
        const val FRAME_MS = 66L
        const val MAX_SCENE_EDGE = 1920
        const val PANEL_WIDTH = 1024
        const val PANEL_HEIGHT = 576
    }
}
