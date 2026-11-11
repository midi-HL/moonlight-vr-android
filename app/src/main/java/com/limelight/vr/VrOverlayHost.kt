package com.limelight.vr

import android.app.Activity
import android.util.Log
import android.view.View
import android.view.ViewGroup
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
class VrOverlayHost(private val activity: Activity, private val decor: ViewGroup) {
    companion object { private const val TAG = "VrOverlayHost" }

    private var theaterView: VrTheaterView? = null
    private var installed = false

    fun theaterView(): VrTheaterView? = theaterView
    val captureSource: View get() = decor

    fun onStateChanged() {
        if (!VrSession.enabled) {
            uninstall()
            return
        }
        install()
        theaterView?.markDirty()
    }

    private fun install() {
        if (installed) return
        val theater = VrTheaterView(activity, decor)
        theaterView = theater
        decor.addView(theater, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        theater.bringToFront()
        installed = true
        Log.i(TAG, "install on ${activity.javaClass.simpleName}")
    }

    private fun uninstall() {
        if (!installed) return
        val view = theaterView
        (view?.parent as? ViewGroup)?.removeView(view)
        view?.stop()
        view?.release()
        theaterView = null
        installed = false
        Log.i(TAG, "uninstall from ${activity.javaClass.simpleName}")
    }

    fun release() = uninstall()
}
