package com.limelight.vr

import android.opengl.Matrix
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.cardboard.sdk.HeadTransform
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Locks the pose contract to the Cardboard classes actually bundled in the application AAR. */
@RunWith(AndroidJUnit4::class)
class VrHeadPoseRuntimeTest {
    @Test
    fun bundledSdkQuaternionDescribesTheForwardPoseRatherThanItsHeadView() {
        val physical = product(rotation(29f, 0f, 1f, 0f), rotation(-23f, 1f, 0f, 0f))
        val view = inverse(physical)
        val quaternion = sdkQuaternionForPhysicalPose(physical)

        // HeadTransform.getQuaternion extracts x=(m[9]-m[6])/s, y=(m[2]-m[8])/s,
        // z=(m[4]-m[1])/s in its positive-trace branch. With Android/OpenGL column-major
        // headView this is the inverse of that view matrix, not the view itself. Obtaining
        // the quaternion from the shipped AAR here protects against guessing its convention.
        assertArrayEquals(physical, VrHeadPose.scaledRotation(quaternion, 1f), EPSILON)
        assertArrayEquals(view, VrHeadPose.viewRotationInto(FloatArray(16), quaternion, 1f), EPSILON)
    }

    @Test
    fun localPitchAfterLandscapeRecenterMovesTheScreenDownWithoutRollingIt() {
        for (landscapeRoll in floatArrayOf(-90f, 90f)) {
            val centerPhysical = product(rotation(37f, 0f, 1f, 0f),
                rotation(landscapeRoll, 0f, 0f, 1f))
            val pitch = rotation(15f, 1f, 0f, 0f)
            val currentPhysical = product(centerPhysical, pitch)
            val actual = VrHeadPose.viewRotationInto(FloatArray(16),
                sdkQuaternionForPhysicalPose(currentPhysical),
                sdkQuaternionForPhysicalPose(centerPhysical), 1f)
            assertArrayEquals("Local pitch must not become world-axis yaw or roll: $landscapeRoll",
                inverse(pitch), actual, EPSILON)

            // A stationary screen in front of a camera moves down when the camera pitches up.
            val center = transform(actual, floatArrayOf(0f, 0f, -2f, 1f))
            val right = transform(actual, floatArrayOf(1f, 0f, -2f, 1f))
            assertEquals("Pitch must not move the screen center sideways", 0f, center[0], EPSILON)
            assertTrue("An upward pitch must move the screen below the forward axis", center[1] < -0.2f)
            assertTrue("Screen must remain in front of the camera", center[2] < 0f)
            assertEquals("Pitch must not roll the horizontal screen edge", center[1], right[1], EPSILON)
        }
    }

    @Test
    fun recenteredRotationMatchesCurrentViewTimesInverseCenterView() {
        val centerPhysical = product(rotation(43f, 0f, 1f, 0f), rotation(71f, 0f, 0f, 1f))
        val localDelta = product(rotation(-18f, 1f, 0f, 0f), rotation(12f, 0f, 1f, 0f))
        val currentPhysical = product(centerPhysical, localDelta)
        val currentView = inverse(currentPhysical)
        val centerView = inverse(centerPhysical)
        val expected = product(currentView, inverse(centerView))
        val actual = VrHeadPose.viewRotationInto(FloatArray(16),
            sdkQuaternionForPhysicalPose(currentPhysical),
            sdkQuaternionForPhysicalPose(centerPhysical), 1f)
        assertArrayEquals(expected, actual, EPSILON)
    }

    private fun sdkQuaternionForPhysicalPose(physical: FloatArray): FloatArray {
        val transform = HeadTransform()
        // getHeadView() intentionally exposes the SDK backing array; the renderer fills the same
        // array in CardboardView. This fixture uses Android Matrix as an independent convention.
        inverse(physical).copyInto(transform.headView)
        return FloatArray(4).also { transform.getQuaternion(it, 0) }
    }

    private fun rotation(degrees: Float, x: Float, y: Float, z: Float): FloatArray =
        FloatArray(16).also { Matrix.setRotateM(it, 0, degrees, x, y, z) }

    private fun product(lhs: FloatArray, rhs: FloatArray): FloatArray =
        FloatArray(16).also { Matrix.multiplyMM(it, 0, lhs, 0, rhs, 0) }

    private fun inverse(value: FloatArray): FloatArray = FloatArray(16).also {
        assertTrue("Pose fixture must be invertible", Matrix.invertM(it, 0, value, 0))
    }

    private fun transform(matrix: FloatArray, point: FloatArray): FloatArray =
        FloatArray(4).also { Matrix.multiplyMV(it, 0, matrix, 0, point, 0) }

    private companion object {
        const val EPSILON = 0.0001f
    }
}
