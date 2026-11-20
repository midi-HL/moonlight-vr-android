package com.limelight.vr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class VrHeadPoseTest {
    @Test
    fun relativeViewAppliesCurrentInverseThenCenter() {
        val center = quaternion(0f, 1f, 0f, 0.55f)
        val current = quaternion(1f, 0f, 0f, -0.35f)
        val centerMatrix = VrHeadPose.scaledRotation(center, 1f)
        val currentMatrix = VrHeadPose.scaledRotation(current, 1f)
        val expected = FloatArray(VrMatrix4.SIZE)
        val inverseCurrent = currentMatrix.copyOf()
        VrMatrix4.transposeRotationInto(inverseCurrent, inverseCurrent)
        VrMatrix4.multiplyInto(expected, inverseCurrent, centerMatrix)

        val actual = VrHeadPose.viewRotationInto(FloatArray(VrMatrix4.SIZE), current, center, 1f)
        assertArrayEquals(expected, actual, 0.00001f)
    }

    @Test
    fun recenteringTheSameHeadViewProducesIdentity() {
        val pose = quaternion(0.3f, 0.7f, 0.2f, 0.8f)
        val actual = VrHeadPose.viewRotationInto(FloatArray(VrMatrix4.SIZE), pose, pose, 1f)
        assertEquals(1f, actual[0], 0.00001f)
        assertEquals(1f, actual[5], 0.00001f)
        assertEquals(1f, actual[10], 0.00001f)
        assertEquals(1f, actual[15], 0.00001f)
        for (index in listOf(1, 2, 4, 6, 8, 9)) assertEquals(0f, actual[index], 0.00001f)
    }

    @Test
    fun scratchQuaternionOverloadMatchesAllocatingConveniencePath() {
        val center = quaternion(0f, 1f, 0f, 0.55f)
        val current = quaternion(1f, 0f, 0f, -0.35f)
        val expected = VrHeadPose.viewRotationInto(FloatArray(VrMatrix4.SIZE), current, center, 1.25f)
        val actual = VrHeadPose.viewRotationInto(
            FloatArray(VrMatrix4.SIZE), current, center, 1.25f, FloatArray(4),
        )
        assertArrayEquals(expected, actual, 0.00001f)
    }

    private fun quaternion(axisX: Float, axisY: Float, axisZ: Float, angle: Float): FloatArray {
        val half = angle / 2f
        val scale = sin(half)
        return floatArrayOf(axisX * scale, axisY * scale, axisZ * scale, cos(half))
    }
}
