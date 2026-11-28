package com.limelight.vr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VrRenderingMathTest {
    @Test
    fun panelCompensationIncludesOffAxisOffsetsAndPreservesDepth() {
        // Asymmetric per-eye projection must shrink about that eye's optical centre.
        val original = floatArrayOf(2f, .2f, .3f, .4f, .5f, 3f, .7f, .8f,
            .9f, 1f, 1.1f, -1f, 1.3f, 1.4f, 1.5f, 1.6f)
        for (zoom in floatArrayOf(1f, 1.35f, 2.5f)) {
            val compensated = VrMatrix4.compensateZoomInto(original.copyOf(), zoom)
            for (column in 0..3) {
                assertEquals(original[column * 4], compensated[column * 4] * zoom, .000001f)
                assertEquals(original[column * 4 + 1], compensated[column * 4 + 1] * zoom, .000001f)
                assertEquals(original[column * 4 + 2], compensated[column * 4 + 2], 0f)
                assertEquals(original[column * 4 + 3], compensated[column * 4 + 3], 0f)
            }
        }
        assertArrayEquals(original, VrMatrix4.compensateZoomInto(original.copyOf(), 1f), 0f)
    }

    @Test
    fun failedZoomCopyKeepsCompensatedPanelInsideItsUnzoomedBounds() {
        val projection = VrMatrix4.identity()
        val compensated = VrMatrix4.compensateZoomInto(projection.copyOf(), 2.5f)
        for (x in floatArrayOf(-.8f, .8f)) for (y in floatArrayOf(-.7f, .7f)) {
            assertTrue(kotlin.math.abs(compensated[0] * x) <= kotlin.math.abs(x))
            assertTrue(kotlin.math.abs(compensated[5] * y) <= kotlin.math.abs(y))
        }
        assertEquals(.4f, compensated[0], .000001f)
        assertEquals(.4f, compensated[5], .000001f)
    }

    @Test
    fun bitmapTopRowStaysAtTopWhenVideoIsFlipped() {
        val mesh = VrScreenGeometry.build(1f, 2f, 0f, 0f, 16f / 9f)
        val bitmapCoords = floatArrayOf(0f, 1f, 0f, 0f, 1f, 1f, 1f, 0f)
        val flippedVideoCoords = floatArrayOf(0f, 0f, 0f, 1f, 1f, 0f, 1f, 1f)

        assertArrayEquals(bitmapCoords, mesh.videoTexCoords(verticalFlip = false), 0f)
        assertArrayEquals(flippedVideoCoords, mesh.videoTexCoords(verticalFlip = true), 0f)
        assertArrayEquals(bitmapCoords, mesh.texCoords, 0f)
    }

    @Test
    fun curvedScreenHasInteriorVerticesBehindBothEdges() {
        val distance = 2f
        val mesh = VrScreenGeometry.build(1f, distance, 0f, 1f, 16f / 9f)
        val leftZ = mesh.positions[2]
        val middleZ = mesh.positions[(mesh.vertexCount / 4) * 6 + 2]
        val rightZ = mesh.positions[(mesh.vertexCount - 1) * 3 + 2]

        assertTrue(mesh.vertexCount > 4)
        assertEquals(-distance, middleZ, 0.001f)
        assertTrue(leftZ > middleZ)
        assertEquals(leftZ, rightZ, 0.001f)
    }

    @Test
    fun narrowerVideoUsesTheSameCurvedSurfaceWithoutStretching() {
        val scene = VrScreenGeometry.build(1f, 2f, 0f, 1f, 2f)
        val video = VrScreenGeometry.buildFittedVideo(1f, 2f, 1f, 2f, 1f)
        val sceneQuarterVertex = (scene.vertexCount / 8) * 2
        val x = scene.positions[sceneQuarterVertex * 3]
        val z = scene.positions[sceneQuarterVertex * 3 + 2]

        assertEquals(x, video.positions[0], 0.001f)
        assertEquals(z, video.positions[2], 0.001f)
        assertEquals(scene.positions[1], video.positions[1], 0.001f)
    }

    @Test
    fun widerVideoFitsWidthAndRetainsItsAspectRatio() {
        val video = VrScreenGeometry.buildFittedVideo(1f, 2f, 0f, 4f / 3f, 16f / 9f)
        val width = video.positions[6] - video.positions[0]
        val height = video.positions[4] - video.positions[1]

        assertEquals(VrScreenGeometry.halfWidth(1f, 4f / 3f) * 2f, width, 0.001f)
        assertEquals(16f / 9f, width / height, 0.001f)
    }
}
