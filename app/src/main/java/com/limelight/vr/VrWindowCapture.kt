package com.limelight.vr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import java.util.concurrent.atomic.AtomicReference

/**
 * Renders the ENTIRE Activity decor (plus the detached quick panel) into one
 * Bitmap, then hands it to the GL thread as a texture upload.
 *
 * Why a Bitmap and not a SurfaceTexture: the requirement is "all 2D screens,
 * including the home page, work in VR". A View tree is not a single Surface —
 * it is many Views, some of them SurfaceViews (the stream video). Capturing the
 * decor on the UI thread with Canvas.draw() is the only approach that keeps
 * every existing screen, dialog and preference row intact without rewriting
 * 600+ source files. Game's StreamView keeps rendering behind the promoted GL
 * layer; VrTheaterRenderer samples it through external-OES once a VR video
 * SurfaceTexture is registered. Until then the decor bitmap still shows the UI.
 *
 * Double buffering: [pending] owns the newest Bitmap exclusively. The GL thread
 * recycles the previous upload after replacing the texture, so Canvas.draw and
 * glTexSubImage2D never touch the same pixels.
 */
class VrWindowCapture(
    private val source: View,
    private val panel: VrPanelView,
) {
    private val uiHandler = Handler(Looper.getMainLooper())
    private val pending = AtomicReference<Bitmap?>(null)
    private var width = 0
    private var height = 0

    init {
        schedule()
    }

    fun markDirty() = schedule()

    fun stop() {
        uiHandler.removeCallbacks(tick)
        pending.getAndSet(null)?.recycle()
    }

    /** GL thread: takes ownership of the newest completed frame. */
    fun consume(): Bitmap? = pending.getAndSet(null)

    private fun schedule() {
        uiHandler.removeCallbacks(tick)
        uiHandler.post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            val w = source.width
            val h = source.height
            if (w <= 0 || h <= 0) {
                uiHandler.postDelayed(this, FRAME_MS)
                return
            }
            if (w != width || h != height) {
                width = w
                height = h
                panel.layoutFor(w, h)
            }
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            // Transparent background: when a stream frame is present the VR
            // video quad shows through everything the 2D UI did not paint.
            // Screens without a video (home page, settings) still render their
            // own opaque backgrounds from the existing decor Views.
            c.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            // Exclude the CardboardView itself, otherwise we feed the VR picture
            // back into its own texture (infinite feedback / growing lag).
            val excluded = findTheater(source)
            val wasHidden = excluded?.visibility
            excluded?.visibility = View.INVISIBLE
            try {
                source.draw(c)
            } finally {
                wasHidden?.let { excluded?.visibility = it }
            }
            if (VrSession.panelOpen) {
                panel.drawInto(c, VrSession.settings, VrSession.selectedIndex, VrSession.editing)
            }
            pending.getAndSet(b)?.recycle()
            uiHandler.postDelayed(this, FRAME_MS)
        }
    }

    private fun findTheater(root: View): View? {
        if (root is VrTheaterView) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findTheater(root.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private companion object { const val FRAME_MS = 66L }
}
