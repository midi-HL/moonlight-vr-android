package com.limelight.vr

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Minimal OpenGL column-major 4x4 matrix helpers, in pure Kotlin.
 *
 * Kept independent of android.opengl.Matrix so the maths can be verified by JVM unit tests.
 * Layout matches OpenGL: element [column * 4 + row].
 *
 * PERFORMANCE (low-end phones): the *Into variants write into a caller-owned array and allocate
 * nothing. VrTheaterRenderer reuses a fixed set of matrices for every eye of every frame, so the
 * render loop produces zero garbage. The allocating variants are kept for tests and callers that
 * genuinely need a fresh array.
 */
object VrMatrix4 {
    const val SIZE = 16

    fun identity(): FloatArray = identityInto(FloatArray(SIZE))

    /** Zero-allocation variant: resets [out] to the identity matrix and returns it. */
    fun identityInto(out: FloatArray): FloatArray {
        require(out.size == SIZE) { "4x4 matrix required" }
        for (i in 0 until SIZE) out[i] = 0f
        out[0] = 1f
        out[5] = 1f
        out[10] = 1f
        out[15] = 1f
        return out
    }

    /** Returns lhs * rhs in column-major order (same semantics as Matrix.multiplyMM). */
    fun multiply(lhs: FloatArray, rhs: FloatArray): FloatArray =
        multiplyInto(FloatArray(SIZE), lhs, rhs)

    /**
     * Zero-allocation transpose of the upper-left 3x3 block of a pure rotation matrix, i.e. its
     * inverse (R^-1 == R^T for rotations). [out] may alias [src].
     *
     * Cardboard SDK's `HeadTransform.getHeadView()` is the inverse (camera-to-head) transform;
     * VrHeadPose.viewRotationInto() uses this to build a correct view matrix.
     */
    fun transposeRotationInto(out: FloatArray, src: FloatArray): FloatArray {
        require(out.size == SIZE && src.size == SIZE) { "4x4 matrices required" }
        if (out !== src) src.copyInto(out)
        val t01 = out[1]; out[1] = out[4]; out[4] = t01
        val t02 = out[2]; out[2] = out[8]; out[8] = t02
        val t12 = out[6]; out[6] = out[9]; out[9] = t12
        return out
    }

    /**
     * Zero-allocation variant of [multiply]. [out] must not alias [lhs] or [rhs]: the product is
     * written element by element, so writing into an input would corrupt later reads.
     */
    fun multiplyInto(out: FloatArray, lhs: FloatArray, rhs: FloatArray): FloatArray {
        require(out.size == SIZE && lhs.size == SIZE && rhs.size == SIZE) { "4x4 matrices required" }
        require(out !== lhs && out !== rhs) { "output must not alias an input matrix" }
        for (column in 0..3) {
            for (row in 0..3) {
                var sum = 0f
                for (k in 0..3) sum += lhs[k * 4 + row] * rhs[column * 4 + k]
                out[column * 4 + row] = sum
            }
        }
        return out
    }

    /**
     * NDC horizontal shift per 1.0 of IPD-scale change, applied to EACH eye in opposite
     * directions. 0.08 means the widest settings (0.5x / 1.6x) move each eye's image by about
     * 2-2.4% of the full screen width, which is clearly visible but never tears the stereo pair.
     */
    const val IPD_SHIFT_GAIN = 0.08f

    /**
     * Applies an EFFECTIVE interpupillary-distance adjustment to a finished per-eye MVP matrix.
     *
     * Do NOT try to scale `Eye.getEyeFromHead()`'s x translation instead: in Cardboard SDK the
     * eyeFromHead translation, the off-axis projection (`LensDistortion::CalculateFov`) and the
     * distortion mesh (`CalculateViewportParameters`) are ALL derived from the very same
     * `inter_lens_distance` device parameter. Changing only one of them leaves the camera position
     * inconsistent with its frustum, so the two eyes drift apart and cannot fuse -- this is what
     * made the original IPD slider look broken.
     *
     * Instead we leave the whole SDK view+projection+distortion chain untouched and shift each
     * eye's final clip-space x by an equal-and-opposite amount: `clip.x += k * clip.w`. For our
     * single flat cinema quad at a fixed depth this is visually equivalent to changing the IPD
     * (it changes the horizontal parallax between the two eyes), but it can never break stereo
     * self-consistency. `ipdScale == 1` gives k == 0, so the default path is bit-identical to the
     * unmodified SDK rendering.
     *
     * [eyeSign] must be +1 for the left eye, -1 for the right eye, 0 for monocular (no-op).
     * [out] is modified in place; zero allocation, safe on the GL render thread.
     */
    fun applyIpdShiftInto(out: FloatArray, ipdScale: Float, eyeSign: Float): FloatArray {
        require(out.size == SIZE) { "4x4 matrix required" }
        if (eyeSign == 0f || ipdScale == 1f) return out
        val k = (1f - ipdScale) * IPD_SHIFT_GAIN * eyeSign
        // clip.x is row 0 (indices 0,4,8,12); clip.w is row 3 (indices 3,7,11,15).
        // Adding k * row3 to row0 implements clip.x += k * clip.w, i.e. a screen-space
        // horizontal shift. Row 3 is never written, so in-place aliasing is safe.
        out[0] += k * out[3]
        out[4] += k * out[7]
        out[8] += k * out[11]
        out[12] += k * out[15]
        return out
    }

    /** Rotation matrix around an arbitrary axis, equivalent to glRotatef(angleRadians). */
    fun rotation(axisX: Float, axisY: Float, axisZ: Float, angleRadians: Float): FloatArray =
        rotationInto(FloatArray(SIZE), axisX, axisY, axisZ, angleRadians)

    /** Zero-allocation variant of [rotation]. All 16 elements are overwritten, so no clear is needed. */
    fun rotationInto(
        out: FloatArray,
        axisX: Float,
        axisY: Float,
        axisZ: Float,
        angleRadians: Float,
    ): FloatArray {
        require(out.size == SIZE) { "4x4 matrix required" }
        val length = sqrt(axisX * axisX + axisY * axisY + axisZ * axisZ)
        if (length < 1e-7f) return identityInto(out)
        val x = axisX / length
        val y = axisY / length
        val z = axisZ / length
        val c = cos(angleRadians)
        val s = sin(angleRadians)
        val t = 1f - c
        out[0] = t * x * x + c
        out[1] = t * x * y + s * z
        out[2] = t * x * z - s * y
        out[3] = 0f
        out[4] = t * x * y - s * z
        out[5] = t * y * y + c
        out[6] = t * y * z + s * x
        out[7] = 0f
        out[8] = t * x * z + s * y
        out[9] = t * y * z - s * x
        out[10] = t * z * z + c
        out[11] = 0f
        out[12] = 0f
        out[13] = 0f
        out[14] = 0f
        out[15] = 1f
        return out
    }
}
