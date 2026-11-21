package com.limelight.vr

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class VrPoseStartupGateTest {
    @Test fun finitePreSensorPosesNeverDeclareTheTrackerReady() {
        val gate = VrPoseStartupGate()
        for (time in 0L..2000L step 25) assertFalse(gate.update(pose(0f), time, false, 1))
        assertFalse(gate.ready)
        assertFalse(gate.update(pose(80f), 2025, true, 1))
        assertFalse(gate.update(pose(80f), 2075, true, 1))
        assertTrue(gate.update(pose(80f), 2125, true, 1))
    }

    @Test fun gravityInitializationJumpRestartsTheObservationWindow() {
        val gate = VrPoseStartupGate()
        assertFalse(gate.update(pose(0f), 0, true, 1))
        assertFalse(gate.update(pose(0f), 50, true, 1))
        assertFalse(gate.update(pose(80f), 100, true, 1))
        assertFalse(gate.update(pose(80f), 150, true, 1))
        assertTrue(gate.update(pose(80f), 200, true, 1))
    }

    @Test fun normalMotionDoesNotRequireTheUserToHoldTheirHeadStill() {
        val gate = VrPoseStartupGate()
        assertFalse(gate.update(pose(20f), 0, true, 1))
        assertFalse(gate.update(pose(25f), 50, true, 1))
        assertTrue(gate.update(pose(30f), 100, true, 1))
        assertTrue(gate.ready)
        assertFalse(gate.update(pose(100f), 150, true, 1))
        assertTrue("Normal tracking must never auto-recenter after startup", gate.ready)
    }

    @Test fun displayBasisChangeRequiresNewSamplesEvenAfterInitialization() {
        val gate = VrPoseStartupGate()
        repeat(3) { gate.update(pose(20f), it * 50L, true, 1) }
        assertTrue(gate.ready)
        assertFalse(gate.update(pose(20f), 200, true, 3))
        assertFalse(gate.ready)
        assertFalse(gate.update(pose(110f), 250, true, 3))
        assertFalse(gate.update(pose(110f), 300, true, 3))
        assertTrue(gate.update(pose(110f), 350, true, 3))
    }

    @Test fun invalidPosesAndClockRegressionDoNotCompleteInitialization() {
        val gate = VrPoseStartupGate()
        gate.update(pose(0f), 100, true, 1)
        assertFalse(gate.update(floatArrayOf(Float.NaN, 0f, 0f, 1f), 150, true, 1))
        assertFalse(gate.update(pose(0f), 50, true, 1))
        assertFalse(gate.update(pose(0f), 100, true, 1))
        assertTrue(gate.update(pose(0f), 150, true, 1))
    }

    @Test fun quaternionSignAndScaleDoNotLookLikeAnInitializationJump() {
        val gate = VrPoseStartupGate()
        val q = pose(70f)
        assertFalse(gate.update(q, 0, true, 1))
        assertFalse(gate.update(FloatArray(4) { -q[it] * 2f }, 50, true, 1))
        assertTrue(gate.update(q, 100, true, 1))
    }

    @Test fun frameCountAloneDoesNotDependOnRenderingRefreshRate() {
        val gate = VrPoseStartupGate()
        for (time in 0L..95L step 5) assertFalse(gate.update(pose(0f), time, true, 1))
        assertTrue(gate.update(pose(0f), 100, true, 1))
    }

    private fun pose(degrees: Float): FloatArray {
        val half = Math.toRadians(degrees.toDouble() / 2)
        return floatArrayOf(sin(half).toFloat(), 0f, 0f, cos(half).toFloat())
    }
}
