package com.limelight.vr

import android.content.Context
import android.util.Log
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import com.google.cardboard.sdk.CardboardView

/**
 * Google Cardboard stereo view hosted inside EVERY Activity's decor. Keeping it
 * in the same Activity (rather than a separate VR Activity) is what lets the
 * home page, settings and dialogs stay live and navigable while VR is on.
 *
 * setZOrderOnTop(true) is applied ONCE in init, before the GL surface exists.
 * Re-applying it after surface creation forces a re-creation that can leave a
 * transparent GL layer on some devices (documented failure in OpenNOW-VR).
 * The 2D SurfaceViews are deliberately left alive behind the GL layer.
 */
class VrTheaterView(context: Context, decor: View) : CardboardView(context) {
    companion object { private const val TAG = "VrTheaterView" }

    val panelView = VrPanelView(context)
    private val capture = VrWindowCapture(decor, panelView)
    private val renderer = VrTheaterRenderer(capture)

    init {
        promoteGlSurface()
        setRenderer(renderer)
        setKeepScreenOn(true)
        setStereoRenderMode(true)
        hideTouchOverlay()
        addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                removeOnAttachStateChangeListener(this)
                Log.i(TAG, "attached: onResume")
                onResume()
            }
            override fun onViewDetachedFromWindow(v: View) = Unit
        })
    }

    private fun promoteGlSurface() {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is SurfaceView) {
                child.setZOrderOnTop(true)
                Log.i(TAG, "promoted GL SurfaceView child #$i")
            }
        }
    }

    /** Cardboard's 2D touch overlay is useless with a headset on; keep it hidden. */
    private fun hideTouchOverlay() {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child !is SurfaceView) child.visibility = View.GONE
        }
    }

    fun markDirty() {
        capture.markDirty()
        renderer.markMeshDirty()
    }

    fun recenter() = renderer.recenter()

    fun stop() {
        Log.i(TAG, "stop: onPause")
        onPause()
    }

    fun release() {
        Log.i(TAG, "release: onDestroy")
        capture.stop()
        onDestroy()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        hideTouchOverlay()
    }

    override fun onDetachedFromWindow() {
        capture.stop()
        super.onDetachedFromWindow()
    }
}
