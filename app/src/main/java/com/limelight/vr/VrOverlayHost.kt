package com.limelight.vr

import android.app.Activity
import android.util.Log
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
    fun theaterView(): VrTheaterView? = theaterView

    fun install() {
        if (theaterView != null) return
        val theater = VrTheaterView(activity, decor, activity is com.limelight.Game)
        theaterView = theater
        decor.addView(theater, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        theater.bringToFront()
        Log.i(TAG, "install on ${activity.javaClass.simpleName}")
    }

    fun release() {
        val view = theaterView ?: return
        theaterView = null
        // Removing the view stops the GL thread. Release VR resources while it is still attached.
        view.release()
        (view.parent as? ViewGroup)?.removeView(view)
        Log.i(TAG, "released from ${activity.javaClass.simpleName}")
    }
}
