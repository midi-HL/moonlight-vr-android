package com.limelight.vr

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The SDK returns a finite pose before receiving gravity, and compensates display rotation one
 * pose late. Do not choose the automatic center from either transient basis. This gate is used
 * only at tracker startup/display changes; it never filters or recenters normal head motion.
 */
internal class VrPoseStartupGate {
    var ready = false
        private set
    private var basis: Int? = null
    private var firstSampleMs = 0L
    private var previousSampleMs = 0L
    private var samples = 0
    private val previous = FloatArray(4)

    /** Returns true exactly when this basis becomes ready. All calls run on the GL thread. */
    fun update(pose: FloatArray, timeMs: Long, gravityReceived: Boolean, displayBasis: Int): Boolean {
        if (basis != displayBasis) {
            basis = displayBasis
            ready = false
            samples = 0
        }
        if (ready) return false
        var norm = 0f
        for (i in 0..3) norm += pose[i] * pose[i]
        if (!gravityReceived || !norm.isFinite() || norm < 1e-8f) {
            samples = 0
            return false
        }
        val inverseLength = 1f / sqrt(norm)
        var dot = 0f
        for (i in 0..3) dot += pose[i] * inverseLength * previous[i]
        // A 30-degree discontinuity during startup is a new candidate basis. Ordinary continuous
        // motion is allowed; no still-head requirement or ongoing pose smoothing is introduced.
        if (samples == 0 || timeMs < previousSampleMs || abs(dot) < 0.9659258f) {
            firstSampleMs = timeMs
            samples = 0
        }
        for (i in 0..3) previous[i] = pose[i] * inverseLength
        previousSampleMs = timeMs
        samples++
        if (samples < 3 || timeMs - firstSampleMs < 100L) return false
        ready = true
        return true
    }
}
