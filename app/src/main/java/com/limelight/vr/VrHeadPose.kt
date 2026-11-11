package com.limelight.vr

import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Converts a Cardboard HeadTransform quaternion into a view rotation matrix with an adjustable
 * sensitivity. Pure Kotlin so it can be unit tested.
 *
 * IMPORTANT: head pose is used ONLY to build the view matrix (i.e. looking around).
 * It must never drive selection, confirmation, clicking or any other control decision.
 * All in-VR control is physical gamepad input, per the user's explicit requirement.
 *
 * PERFORMANCE: [scaledRotationInto] is the zero-allocation path used by the render loop.
 */
object VrHeadPose {
    /**
     * @param quaternion (x, y, z, w) as produced by HeadTransform.getQuaternion(float[], 0)
     * @param sensitivity 0 = fixed view (identity), 1 = exact head rotation, >1 = amplified
     * @return column-major 4x4 rotation matrix suitable for eyeFromHead * rotation
     */
    fun scaledRotation(quaternion: FloatArray, sensitivity: Float): FloatArray =
        scaledRotationInto(FloatArray(VrMatrix4.SIZE), quaternion, sensitivity)

    /**
     * Zero-allocation VIEW rotation = inverse of [scaledRotationInto].
     *
     * Cardboard's `HeadTransform.getHeadView()` is the camera-to-head (inverse) transform, and
     * `CardboardView.Eye.applyHeadView()` computes `eyeFromHead * headView`. Using the FORWARD
     * rotation as the view matrix makes the screen follow the head instead of staying fixed in
     * space (no depth / "stuck to your face"). VrTheaterRenderer must use this function.
     */
    fun viewRotationInto(out: FloatArray, quaternion: FloatArray, sensitivity: Float): FloatArray {
        scaledRotationInto(out, quaternion, sensitivity)
        return VrMatrix4.transposeRotationInto(out, out)
    }

    /**
     * Computes the relative rotation from [centerQuaternion] to [currentQuaternion], applies
     * [sensitivity], and writes the VIEW matrix (inverse of the forward rotation) into [out].
     * This is used for "recenter" — the stored center orientation becomes the new forward.
     */
    fun viewRotationInto(out: FloatArray,
                         currentQuaternion: FloatArray,
                         centerQuaternion: FloatArray,
                         sensitivity: Float): FloatArray {
        // relative = inverse(center) * current  =>  current * centerConj
        val tmp = FloatArray(4)
        quatMultiplyConj(tmp, currentQuaternion, centerQuaternion)
        return viewRotationInto(out, tmp, sensitivity)
    }

    /** Zero-allocation variant. Writes the rotation into [out] and returns it. */
    fun scaledRotationInto(
        out: FloatArray,
        quaternion: FloatArray,
        sensitivity: Float,
    ): FloatArray {
        require(quaternion.size >= 4) { "quaternion needs 4 components" }
        require(out.size == VrMatrix4.SIZE) { "4x4 matrix required" }
        if (sensitivity <= 0f) return VrMatrix4.identityInto(out)

        var x = quaternion[0]
        var y = quaternion[1]
        var z = quaternion[2]
        var w = quaternion[3]
        val length = sqrt(x * x + y * y + z * z + w * w)
        if (length < 1e-7f) return VrMatrix4.identityInto(out)
        x /= length
        y /= length
        z /= length
        w /= length

        // Shortest-arc convention: a quaternion and its negation describe the same rotation.
        if (w < 0f) {
            x = -x
            y = -y
            z = -z
            w = -w
        }

        val clampedW = w.coerceIn(-1f, 1f)
        val angle = 2f * acos(clampedW)
        val sinHalf = sqrt((1f - clampedW * clampedW).coerceAtLeast(0f))
        if (sinHalf < 1e-6f || angle < 1e-6f) return VrMatrix4.identityInto(out)

        return VrMatrix4.rotationInto(out, x / sinHalf, y / sinHalf, z / sinHalf, angle * sensitivity)
    }

    /** q1 * conjugate(q2) into [out] (both length-4: x,y,z,w). */
    private fun quatMultiplyConj(out: FloatArray, q1: FloatArray, q2: FloatArray) {
        val x1 = q1[0]; val y1 = q1[1]; val z1 = q1[2]; val w1 = q1[3]
        val x2 = q2[0]; val y2 = q2[1]; val z2 = q2[2]; val w2 = q2[3]
        // conjugate(q2) = (-x2, -y2, -z2, w2)
        out[0] =  w1 * -x2 + x1 * w2 + y1 * -z2 - z1 * -y2  // w1*(-x2) + x1*w2 + y1*(-z2) - z1*(-y2)
        out[1] =  w1 * -y2 - x1 * -z2 + y1 * w2 + z1 * -x2  // w1*(-y2) - x1*(-z2) + y1*w2 + z1*(-x2)
        out[2] =  w1 * -z2 + x1 * -y2 - y1 * -x2 + z1 * w2  // w1*(-z2) + x1*(-y2) - y1*(-x2) + z1*w2
        out[3] =  w1 * w2  - x1 * -x2 - y1 * -y2 - z1 * -z2  // w1*w2 - x1*(-x2) - y1*(-y2) - z1*(-z2)
    }
}
