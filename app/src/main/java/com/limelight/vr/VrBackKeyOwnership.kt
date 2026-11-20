package com.limelight.vr

/** Keeps a BACK release with the destination that received its initial press. */
internal class VrBackKeyOwnership {
    enum class Result { PassThrough, Consumed, InvokeBack }

    private data class Press(val downTime: Long, val captured: Boolean)

    private val presses = HashMap<Int, Press>()

    fun onKey(
        action: Int,
        deviceId: Int,
        downTime: Long,
        isRepeat: Boolean,
        isCanceled: Boolean,
        vrEnabled: Boolean,
        backOwnedByHost: Boolean,
        panelOpen: Boolean,
    ): Result = when (action) {
        VrKeys.ACTION_DOWN -> {
            // Repeats and duplicate deliveries of the same DOWN retain its owner. A new
            // physical press replaces stale state if the previous UP was lost elsewhere.
            val press = presses[deviceId]?.takeIf { isRepeat || it.downTime == downTime }
                ?: Press(downTime, vrEnabled && (!backOwnedByHost || panelOpen)).also {
                    presses[deviceId] = it
                }
            if (press.captured) Result.Consumed else Result.PassThrough
        }
        VrKeys.ACTION_UP -> {
            val press = presses.remove(deviceId)
            when {
                press == null -> if (vrEnabled && (!backOwnedByHost || panelOpen)) {
                    Result.Consumed
                } else Result.PassThrough
                !press.captured -> Result.PassThrough
                vrEnabled && !isCanceled && press.downTime == downTime -> Result.InvokeBack
                else -> Result.Consumed
            }
        }
        else -> Result.PassThrough
    }

    fun clear() = presses.clear()
}
