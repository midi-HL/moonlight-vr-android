package com.limelight.vr

import kotlin.math.cos
import kotlin.math.sin

/**
 * Geometry for the FLAT 2D stream screen placed in VR space.
 *
 * The video is a single texture mapped onto one quad (optionally bent into a gentle arc).
 * Both eyes draw the SAME quad from slightly different viewpoints — that is ordinary stereo of a
 * virtual cinema screen, NOT stereoscopic 3D video. This matches the user's requirement that the
 * stream stays a flat 2D picture.
 *
 * Vertices are emitted as a GL_TRIANGLE_STRIP: for each column, bottom vertex then top vertex.
 * The camera sits at the origin looking down -Z, so every z is negative.
 */
object VrScreenGeometry {
    /** Half-height of the screen at screenScale = 1.0, in metres. */
    const val BASE_HALF_HEIGHT = 0.62f
    const val DEFAULT_ASPECT = 16f / 9f
    const val DEFAULT_SEGMENTS = 24

    /** Maximum wrap angle at curvature = 1.0, in radians (~34 degrees). */
    const val MAX_BEND_RADIANS = 0.60f

    data class Mesh(
        val positions: FloatArray,
        val texCoords: FloatArray,
        val vertexCount: Int,
    )

    fun halfHeight(scale: Float): Float = BASE_HALF_HEIGHT * scale.coerceAtLeast(0.01f)

    fun halfWidth(scale: Float, aspect: Float): Float =
        halfHeight(scale) * (if (aspect > 0f) aspect else DEFAULT_ASPECT)

    /**
     * @param scale         screenScale from VrSettings (the headline "make it bigger" control)
     * @param distance      distance in metres in front of the camera
     * @param verticalOffset vertical shift in metres
     * @param curvature     0 = perfectly flat plane, 1 = fully wrapped arc
     * @param aspect        stream width / height
     * @param segments      horizontal subdivisions; must be >= 1
     */
    fun build(
        scale: Float,
        distance: Float,
        verticalOffset: Float,
        curvature: Float,
        aspect: Float,
        segments: Int = DEFAULT_SEGMENTS,
    ): Mesh {
        val count = segments.coerceAtLeast(1)
        val safeAspect = if (aspect > 0f) aspect else DEFAULT_ASPECT
        val h = halfHeight(scale)
        val w = halfWidth(scale, safeAspect)
        val bend = curvature.coerceIn(0f, 1f) * MAX_BEND_RADIANS
        val radius = if (bend > 1e-5f) w / bend else 0f
        val d = distance.coerceAtLeast(0.1f)

        val vertexCount = (count + 1) * 2
        val positions = FloatArray(vertexCount * 3)
        val texCoords = FloatArray(vertexCount * 2)

        for (i in 0..count) {
            val u = i.toFloat() / count
            val n = u * 2f - 1f
            val x: Float
            val z: Float
            if (bend > 1e-5f) {
                val theta = n * bend
                x = radius * sin(theta)
                // Edges curve towards the viewer (z becomes less negative), wrapping the FOV.
                z = -d + radius * (1f - cos(theta))
            } else {
                x = n * w
                z = -d
            }
            val bottom = i * 2
            val top = bottom + 1

            positions[bottom * 3] = x
            positions[bottom * 3 + 1] = verticalOffset - h
            positions[bottom * 3 + 2] = z
            positions[top * 3] = x
            positions[top * 3 + 1] = verticalOffset + h
            positions[top * 3 + 2] = z

            texCoords[bottom * 2] = u
            texCoords[bottom * 2 + 1] = 1f
            texCoords[top * 2] = u
            texCoords[top * 2 + 1] = 0f
        }
        return Mesh(positions, texCoords, vertexCount)
    }
}
