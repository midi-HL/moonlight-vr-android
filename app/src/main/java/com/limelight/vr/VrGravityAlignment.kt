package com.limelight.vr

import kotlin.math.sqrt

/** Checks Cardboard's gravity basis without changing its pose or the application's 3DoF math. */
internal object VrGravityAlignment {
    fun matches(headView: FloatArray, acceleration: FloatArray, displayRotation: Int): Boolean {
        val x = acceleration[0]; val y = acceleration[1]; val z = acceleration[2]
        val norm = x * x + y * y + z * z
        if (!norm.isFinite() || norm < 1f) return false
        val displayX: Float
        val displayY: Float
        when (displayRotation) {
            0 -> { displayX = x; displayY = y }
            1 -> { displayX = -y; displayY = x }
            2 -> { displayX = -x; displayY = -y }
            3 -> { displayX = y; displayY = -x }
            else -> return false
        }
        // SDK: R = sensorToDisplay * sensorFromStart * ekfToHead. All four ekfToHead
        // rotations map world Y to canonical Z. Once EKF has aligned canonical Z to
        // measured gravity, R's Y column therefore equals gravity in display space.
        val dot = (headView[4] * displayX + headView[5] * displayY + headView[6] * z) / sqrt(norm)
        return dot.isFinite() && dot >= 0.990268f // cos(8 degrees), allowing prediction/sample latency.
    }
}
