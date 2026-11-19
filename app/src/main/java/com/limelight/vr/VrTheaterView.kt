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
class VrTheaterView(context: Context, decor: View, private val isVideoHost: Boolean) : CardboardView(context) {
    companion object { private const val TAG = "VrTheaterView" }

    val panelView = VrPanelView(context)
    private val capture = VrWindowCapture(decor, panelView, isVideoHost)
    private val renderer = VrTheaterRenderer(capture, isVideoHost)
    private var resumed = false
    private var released = false

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
                resumed = true
                capture.markDirty()
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
        if (!released && resumed) capture.markDirty()
    }

    /** Marshalled onto the GL thread so head-pose state is not read concurrently. */
    fun recenter() = queueEvent { renderer.recenter() }

    fun release() {
        if (released) return
        released = true
        Log.i(TAG, "release: onDestroy")
        capture.stop()
        shutdownCalled()
        if (resumed) {
            queueEvent { renderer.release() }
            onPause()
            resumed = false
        }
        onDestroy()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        hideTouchOverlay()
    }

    override fun draw(canvas: android.graphics.Canvas) {
        // A bitmap capture must not include Cardboard or punch its SurfaceView
        // hole through the captured Android UI. Keep the real Surface visible.
        if (canvas.isHardwareAccelerated) super.draw(canvas)
    }

    override fun onDetachedFromWindow() {
        capture.stop()
        super.onDetachedFromWindow()
    }
}
