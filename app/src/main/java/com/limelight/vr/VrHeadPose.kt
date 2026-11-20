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
     * @return column-major 4x4 forward rotation matrix. Renderers that build a view matrix
     * should use [viewRotationInto], which inverts this rotation first.
     */
    fun scaledRotation(quaternion: FloatArray, sensitivity: Float): FloatArray =
        scaledRotationInto(FloatArray(VrMatrix4.SIZE), quaternion, sensitivity)

    /**
     * Zero-allocation view rotation = inverse of the forward head-pose quaternion.
     *
     * The bundled HeadTransform extracts its quaternion with the opposite off-diagonal signs
     * to OpenGL's column-major view matrix. It therefore describes head-to-world rotation.
     * Invert it once to obtain the world-to-head view used by Eye.applyHeadView().
     */
    fun viewRotationInto(out: FloatArray, quaternion: FloatArray, sensitivity: Float): FloatArray {
        scaledRotationInto(out, quaternion, sensitivity)
        return VrMatrix4.transposeRotationInto(out, out)
    }

    /**
     * Computes the relative rotation from [centerQuaternion] to [currentQuaternion], applies
     * [sensitivity], and writes the relative VIEW matrix into [out].
     * This is used for "recenter" — the stored center orientation becomes the new forward.
     */
    fun viewRotationInto(out: FloatArray,
                         currentQuaternion: FloatArray,
                         centerQuaternion: FloatArray,
                         sensitivity: Float): FloatArray {
        return viewRotationInto(out, currentQuaternion, centerQuaternion, sensitivity, FloatArray(4))
    }

    /**
     * Zero-allocation overload for a renderer that owns a scratch quaternion on its GL thread.
     * [scratchQuaternion] must not be shared with another concurrent render invocation.
     */
    fun viewRotationInto(out: FloatArray,
                         currentQuaternion: FloatArray,
                         centerQuaternion: FloatArray,
                         sensitivity: Float,
                         scratchQuaternion: FloatArray): FloatArray {
        require(scratchQuaternion.size >= 4) { "scratchQuaternion needs 4 components" }
        // Relative forward pose = inverse(center) * current; its inverse gives
        // currentView * inverse(centerView). Reversing this order rotates the motion axes
        // into the old world basis (e.g. pitch becomes roll after a 90-degree center yaw).
        quatConjugateMultiply(scratchQuaternion, centerQuaternion, currentQuaternion)
        return viewRotationInto(out, scratchQuaternion, sensitivity)
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

    /** conjugate(q1) * q2 into [out] (both length-4: x,y,z,w). */
    private fun quatConjugateMultiply(out: FloatArray, q1: FloatArray, q2: FloatArray) {
        val x1 = q1[0]; val y1 = q1[1]; val z1 = q1[2]; val w1 = q1[3]
        val x2 = q2[0]; val y2 = q2[1]; val z2 = q2[2]; val w2 = q2[3]
        out[0] = w1 * x2 - x1 * w2 - y1 * z2 + z1 * y2
        out[1] = w1 * y2 + x1 * z2 - y1 * w2 - z1 * x2
        out[2] = w1 * z2 - x1 * y2 + y1 * x2 - z1 * w2
        out[3] = w1 * w2 + x1 * x2 + y1 * y2 + z1 * z2
    }
}
