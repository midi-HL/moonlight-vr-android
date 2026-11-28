package com.limelight.utils

import android.content.Context
import android.widget.Toast
import com.limelight.vr.VrSession
import java.util.Collections
import java.util.WeakHashMap

/** Toast facade that keeps transient system UI out of the stereoscopic VR scene. */
class VrToast private constructor(private val delegate: Toast) {
    fun show() {
        if (!VrToastPolicy.shouldShow(VrSession.enabled)) {
            delegate.cancel()
            return
        }
        activeToasts[delegate] = true
        delegate.show()
    }

    fun cancel() {
        activeToasts.remove(delegate)
        delegate.cancel()
    }

    companion object {
        const val LENGTH_SHORT = Toast.LENGTH_SHORT
        const val LENGTH_LONG = Toast.LENGTH_LONG

        @JvmStatic
        fun makeText(context: Context, text: CharSequence, duration: Int): VrToast =
            VrToast(Toast.makeText(context, text, duration))

        @JvmStatic
        fun makeText(context: Context, resId: Int, duration: Int): VrToast =
            VrToast(Toast.makeText(context, resId, duration))

        /** Cancel app-owned transient hints that were shown before VR was enabled. */
        @JvmStatic
        fun dismissAll() {
            synchronized(activeToasts) {
                activeToasts.keys.toList().forEach(Toast::cancel)
                activeToasts.clear()
            }
        }

        private val activeToasts = Collections.synchronizedMap(WeakHashMap<Toast, Boolean>())
    }
}

internal object VrToastPolicy {
    fun shouldShow(vrEnabled: Boolean): Boolean = !vrEnabled
}
