package com.limelight.vr

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout

/**
 * Per-Activity VR host. Added to the decor view so it covers the whole window,
 * including the PcView grid, AppView, StreamSettings, dialogs and Game.
 *
 * Layer rule: CardboardView's GL SurfaceView is promoted with
 * setZOrderOnTop(true) exactly once, before surface creation, so the stereo
 * picture is composited above the 2D SurfaceView layers. The quick settings
 * panel is intentionally NOT a child of the window — it is a detached View
 * drawn into the same offscreen Canvas by [VrWindowCapture]. A normal View
 * would be hidden underneath the promoted GL surface.
 */
class VrOverlayHost(
    private val activity: Activity,
    private val decor: ViewGroup,
    private val onWindowChanged: () -> Unit = {},
) {
    companion object { private const val TAG = "VrOverlayHost" }

    private var theaterView: VrTheaterView? = null
    private var installed = false
    private var released = false
    private var resumed = true
    // This ordinary hardware layer covers the 2D decor while the window rotates or GL starts.
    // Software window capture deliberately skips it, just as it skips the Cardboard SurfaceView.
    private val curtain = object : View(activity) {
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (canvas.isHardwareAccelerated) canvas.drawColor(Color.BLACK)
        }
    }
    private val layoutGate = ViewTreeObserver.OnPreDrawListener {
        if (!released && resumed) {
            onWindowChanged()
            if (theaterView == null && isWindowReady()) startTheater()
        }
        true
    }
    fun theaterView(): VrTheaterView? = theaterView

    fun install() {
        if (installed || released) return
        installed = true
        decor.addView(curtain, fullWindowLayout())
        decor.viewTreeObserver.addOnPreDrawListener(layoutGate)
        decor.invalidate()
    }

    fun resume() {
        if (released) return
        resumed = true
        theaterView?.resumePresentation()
        decor.invalidate()
    }

    fun pause() {
        resumed = false
        theaterView?.pausePresentation()
    }

    private fun isWindowReady(): Boolean = !activity.isFinishing && !activity.isDestroyed &&
        decor.isAttachedToWindow && decor.isLaidOut &&
        decor.width > decor.height && decor.height > 0 &&
        activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun startTheater() {
        val theater = VrTheaterView(activity, decor, activity is com.limelight.Game)
        theaterView = theater
        decor.addView(theater, fullWindowLayout())
        theater.bringToFront()
        Log.i(TAG, "install on ${activity.javaClass.simpleName}")
    }

    fun release() {
        if (released) return
        released = true
        if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnPreDrawListener(layoutGate)
        val view = theaterView
        theaterView = null
        // Removing the view stops the GL thread. Release VR resources while it is still attached.
        view?.release()
        (view?.parent as? ViewGroup)?.removeView(view)
        (curtain.parent as? ViewGroup)?.removeView(curtain)
        Log.i(TAG, "released from ${activity.javaClass.simpleName}")
    }

    private fun fullWindowLayout() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
}
