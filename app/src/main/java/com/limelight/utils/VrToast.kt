package com.limelight.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.limelight.vr.VrSession

/** Toast facade that keeps transient system UI out of the stereoscopic VR scene. */
class VrToast private constructor(private val delegate: Toast) {
    fun show() {
        synchronized(stateLock) {
            if (!VrToastPolicy.shouldShow(VrSession.enabled)) {
                delegate.cancel()
                return
            }
            register(delegate, durationMillis)
            delegate.show()
        }
    }

    fun cancel() {
        unregister(delegate)
        delegate.cancel()
    }

    companion object {
        const val LENGTH_SHORT = Toast.LENGTH_SHORT
        const val LENGTH_LONG = Toast.LENGTH_LONG

        @JvmStatic
        fun makeText(context: Context?, text: CharSequence, duration: Int): VrToast =
            VrToast(Toast.makeText(context, text, duration))

        @JvmStatic
        fun makeText(context: Context?, resId: Int, duration: Int): VrToast =
            VrToast(Toast.makeText(context, resId, duration))

        /** Cancel app-owned transient hints that were shown before VR was enabled. */
        @JvmStatic
        fun dismissAll() {
            val toasts = synchronized(stateLock) { activeToasts.drain() }
            toasts.forEach { (toast, cleanup) ->
                handler.removeCallbacks(cleanup)
                toast.cancel()
            }
        }

        private val handler = Handler(Looper.getMainLooper())
        private val stateLock = Any()
        private val durationMillis = 4_000L
        // Toasts shown through this facade must stay strongly reachable until their
        // display window expires, so enabling VR can reliably cancel every active one.
        private val activeToasts = ActiveToastRegistry<Toast>()

        private fun register(toast: Toast, timeoutMillis: Long) {
            lateinit var cleanup: Runnable
            cleanup = Runnable {
                if (activeToasts.unregister(toast, cleanup)) {
                    handler.removeCallbacks(cleanup)
                }
            }
            activeToasts.register(toast, cleanup)?.let(handler::removeCallbacks)
            handler.postDelayed(cleanup, timeoutMillis)
        }

        private fun unregister(toast: Toast) {
            val cleanup = activeToasts.unregister(toast)
            cleanup?.let(handler::removeCallbacks)
        }
    }
}

internal object VrToastPolicy {
    fun shouldShow(vrEnabled: Boolean): Boolean = !vrEnabled
}
