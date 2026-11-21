package com.limelight.vr

import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class VrGravityAlignmentTest {
    @Test fun uprightPoseMatchesDeviceGravityInAllDisplayRotations() {
        val identity = FloatArray(16).also { VrMatrix4.identityInto(it) }
        val gravity = listOf(floatArrayOf(0f, 9.8f, 0f), floatArrayOf(9.8f, 0f, 0f),
            floatArrayOf(0f, -9.8f, 0f), floatArrayOf(-9.8f, 0f, 0f))
        gravity.forEachIndexed { rotation, value ->
            assertTrue("display rotation $rotation", VrGravityAlignment.matches(identity, value, rotation))
            assertFalse(VrGravityAlignment.matches(identity, FloatArray(3) { -value[it] }, rotation))
        }
    }

    @Test fun sdkFinitePreGravityBasisIsRejectedForAnUprightPhone() {
        val halfRoot = sqrt(0.5f)
        // HeadTracker::GetRotation before gravity: sensorToDisplay * Identity * ekfToHead.
        val ekfToHead = listOf(floatArrayOf(halfRoot, 0f, 0f, halfRoot),
            floatArrayOf(0.5f, -0.5f, -0.5f, 0.5f),
            floatArrayOf(0f, -halfRoot, -halfRoot, 0f),
            floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f))
        val sensorToDisplay = listOf(floatArrayOf(0f, 0f, 0f, 1f),
            floatArrayOf(0f, 0f, halfRoot, halfRoot), floatArrayOf(0f, 0f, 1f, 0f),
            floatArrayOf(0f, 0f, -halfRoot, halfRoot))
        val gravity = listOf(floatArrayOf(0f, 9.8f, 0f), floatArrayOf(9.8f, 0f, 0f),
            floatArrayOf(0f, -9.8f, 0f), floatArrayOf(-9.8f, 0f, 0f))
        for (rotation in 0..3) {
            val initial = FloatArray(16)
            VrMatrix4.multiplyInto(initial, VrHeadPose.scaledRotation(sensorToDisplay[rotation], 1f),
                VrHeadPose.scaledRotation(ekfToHead[rotation], 1f))
            assertFalse("Finite SDK reset pose is not a gravity-ready pose: $rotation",
                VrGravityAlignment.matches(initial, gravity[rotation], rotation))
        }
    }

    @Test fun arbitrarilyDelayedNativeGravityInitializationCannotLatchTheEarlyCenter() {
        val gate = VrPoseStartupGate()
        val halfRoot = sqrt(0.5f)
        val initial = VrHeadPose.scaledRotation(floatArrayOf(halfRoot, 0f, 0f, halfRoot), 1f)
        val actual = FloatArray(16).also { VrMatrix4.identityInto(it) }
        val gravity = floatArrayOf(0f, 9.8f, 0f)
        for (time in 0L..2000L step 25) {
            assertFalse(gate.update(floatArrayOf(-halfRoot, 0f, 0f, halfRoot), time,
                VrGravityAlignment.matches(initial, gravity, 0), 0))
        }
        assertFalse(gate.ready)
        for (time in 2025L..2100L step 25) assertFalse(gate.update(floatArrayOf(0f, 0f, 0f, 1f), time,
            VrGravityAlignment.matches(actual, gravity, 0), 0))
        assertTrue(gate.update(floatArrayOf(0f, 0f, 0f, 1f), 2125,
            VrGravityAlignment.matches(actual, gravity, 0), 0))
    }

    @Test fun invalidGravityCannotOpenTheGate() {
        val identity = FloatArray(16).also { VrMatrix4.identityInto(it) }
        assertFalse(VrGravityAlignment.matches(identity, floatArrayOf(0f, 0f, 0f), 0))
        assertFalse(VrGravityAlignment.matches(identity, floatArrayOf(Float.NaN, 9.8f, 0f), 0))
        assertFalse(VrGravityAlignment.matches(identity, floatArrayOf(0f, 9.8f, 0f), 8))
    }
}
