package com.limelight.vr

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import android.view.Surface
import com.google.cardboard.sdk.CardboardView
import com.google.cardboard.sdk.HeadTransform
import com.google.cardboard.sdk.Viewport
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig

/**
 * Stereo renderer for the whole-window projection.
 *
 * A screen mesh carries the captured Activity window and its dialogs. When a stream is running its
 * frames arrive through a SurfaceTexture (GL_TEXTURE_EXTERNAL_OES).
 * The window bitmap blends over the video. The quick settings panel is a separate flat layer
 * fixed relative to the headset, so its controls stay readable while the screen changes.
 *
 * Both eyes sample the SAME quads from slightly different viewpoints — ordinary
 * stereo of a virtual screen, not SBS 3D video. Head pose only changes the view
 * matrix; it never selects or clicks anything.
 */
class VrTheaterRenderer(
    private val capture: VrWindowCapture,
    private val isVideoHost: Boolean,
) : CardboardView.Renderer {
    companion object {
        private const val TAG = "VrTheaterRenderer"
        private const val QUICK_PANEL_DISTANCE = 1.55f
        private const val QUICK_PANEL_SCALE = 0.78f
        private const val MAX_ZOOM_READBACK_BYTES = 32L * 1024 * 1024
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uMVP;
            varying vec2 vTexCoord;
            void main() {
                vTexCoord = aTexCoord;
                gl_Position = uMVP * aPosition;
            }
        """
        private const val PANEL_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform float uBrightness;
            uniform float uAlpha;
            varying vec2 vTexCoord;
            void main() {
                vec4 c = texture2D(uTexture, vTexCoord);
                gl_FragColor = vec4(c.rgb * uBrightness, c.a * uAlpha);
            }
        """
        private const val VIDEO_FRAGMENT_SHADER = """#extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            uniform mat4 uSTMatrix;
            uniform float uBrightness;
            varying vec2 vTexCoord;
            void main() {
                vec2 uv = (uSTMatrix * vec4(vTexCoord.x, vTexCoord.y, 0.0, 1.0)).xy;
                gl_FragColor = vec4(texture2D(uTexture, uv).rgb * uBrightness, 1.0);
            }
        """
    }

    private class Handles(
        val program: Int,
        val position: Int,
        val texCoord: Int,
        val mvp: Int,
        val brightness: Int,
        val alpha: Int,
        val texture: Int,
        val stMatrix: Int,
    )

    private var panel: Handles? = null
    private var video: Handles? = null
    private var panelTextureId = 0
    private var quickPanelTextureId = 0
    private var videoTextureId = 0
    private var videoSurfaceTexture: SurfaceTexture? = null
    private var videoSurface: Surface? = null
    private val stMatrix = FloatArray(16).also { VrMatrix4.identityInto(it) }

    private var positionBuffer: FloatBuffer? = null
    private var texCoordBuffer: FloatBuffer? = null
    private var videoPositionBuffer: FloatBuffer? = null
    private var videoTexCoordBuffer: FloatBuffer? = null
    private var vertexCount = 0
    private var videoVertexCount = 0
    private var textureWidth = 0
    private var textureHeight = 0
    private var videoAspect = 0f
    private var quickPanelTextureWidth = 0
    private var quickPanelTextureHeight = 0
    private var hasQuickPanelFrame = false
    private var hasVideoFrame = false

    private val quickPanelMesh = VrScreenGeometry.build(
        scale = QUICK_PANEL_SCALE,
        distance = QUICK_PANEL_DISTANCE,
        verticalOffset = 0f,
        curvature = 0f,
        aspect = VrScreenGeometry.DEFAULT_ASPECT,
    )
    private val quickPanelPositions = direct(quickPanelMesh.positions)
    private val quickPanelTexCoords = direct(quickPanelMesh.texCoords)

    private var lastScale = -1f
    private var lastDistance = -1f
    private var lastCurvature = -1f
    private var lastVerticalFlip: Boolean? = null
    private var meshDirty = true
    private var frameSettings = VrSession.settings
    private var framePanelOpen = false

    private val headQuaternion = FloatArray(4)
    private val nextHeadQuaternion = FloatArray(4)
    private val centerQuaternion = FloatArray(4).also { it[3] = 1f }
    private var hasHeadPose = false
    private var recenterPending = false
    private val headRotation = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val identity = FloatArray(16).also { VrMatrix4.identityInto(it) }
    private var loggedFirstFrame = false
    private var loggedBitmapUploadError = false

    // P-D3: zoom-fill post-process. Cardboard's distortion mesh leaves vertical
    // black bands; we copy the SDK's FINAL default-framebuffer frame and blit it
    // back centre-zoomed in onFinishFrame(), with no FOV / projection / shader /
    // SDK changes. All GL objects are owned by the GL thread.
    private var zoomTextureId = 0
    private var zoomSnapshotMode = -1 // -1 = not probed, -2 = readPixels, 0..2 = copy format
    private var zoomTexW = 0
    private var zoomTexH = 0
    // Framebuffer copies start at the bottom-left. Each half-screen eye is cropped around its own center.
    private val zoomQuad = direct(floatArrayOf(-1f, -1f, 0f, 1f, -1f, 0f, -1f, 1f, 0f, 1f, 1f, 0f))
    private val zoomUv = direct(FloatArray(8))
    private val alignmentLine = direct(floatArrayOf(-.003f, -1f, 0f, .003f, -1f, 0f, -.003f, 0f, 0f, .003f, 0f, 0f))
    private val unitUv = direct(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
    private var zoomReadback: ByteBuffer? = null
    private var unsupportedZoomWidth = 0
    private var unsupportedZoomHeight = 0
    private var zoomLoggedActive = false
    private var zoomLoggedSkip: String? = null
    private val ZOOM_COPY_FORMATS = arrayOf(
        intArrayOf(GLES20.GL_RGBA, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE),
        intArrayOf(GLES20.GL_RGB, GLES20.GL_RGB, GLES20.GL_UNSIGNED_SHORT_5_6_5),
        intArrayOf(GLES20.GL_RGB, GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE),
    )

    override fun onSurfaceCreated(config: EGLConfig) {
        // EGL can recreate the context without calling onRendererShutdown. Keep only CPU state.
        releaseVideoSurface()
        videoTextureId = 0
        hasVideoFrame = false
        textureWidth = 0
        textureHeight = 0
        quickPanelTextureWidth = 0
        quickPanelTextureHeight = 0
        hasQuickPanelFrame = false
        vertexCount = 0
        videoVertexCount = 0
        whiteTex = 0
        loggedFirstFrame = false
        loggedBitmapUploadError = false
        capture.releasePending()
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glDisable(GLES20.GL_DITHER)
        panel = runCatching { createHandles(PANEL_FRAGMENT_SHADER) }
            .onFailure { Log.e(TAG, "panel shader link failed", it) }.getOrNull()
        video = runCatching { createHandles(VIDEO_FRAGMENT_SHADER) }
            .onFailure { Log.e(TAG, "video shader link failed", it) }.getOrNull()
        panelTextureId = genTexture(GLES20.GL_TEXTURE_2D)
        quickPanelTextureId = genTexture(GLES20.GL_TEXTURE_2D)
        zoomTextureId = genTexture(GLES20.GL_TEXTURE_2D)
        zoomSnapshotMode = -1
        zoomTexW = 0
        zoomTexH = 0
        unsupportedZoomWidth = 0
        unsupportedZoomHeight = 0
        zoomReadback = null
        zoomLoggedActive = false
        zoomLoggedSkip = null
        if (isVideoHost) setupVideoTexture()
    }

    private fun createHandles(fragment: String): Handles {
        val program = VrGlProgram.createProgram(VERTEX_SHADER, fragment)
        return Handles(
            program,
            GLES20.glGetAttribLocation(program, "aPosition"),
            GLES20.glGetAttribLocation(program, "aTexCoord"),
            GLES20.glGetUniformLocation(program, "uMVP"),
            GLES20.glGetUniformLocation(program, "uBrightness"),
            GLES20.glGetUniformLocation(program, "uAlpha"),
            GLES20.glGetUniformLocation(program, "uTexture"),
            GLES20.glGetUniformLocation(program, "uSTMatrix"),
        )
    }

    private fun genTexture(target: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(target, ids[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun setupVideoTexture() {
        if (video == null) return
        videoTextureId = genTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        val texture = SurfaceTexture(videoTextureId)
        val output = Surface(texture)
        videoSurfaceTexture = texture
        videoSurface = output
        VrVideoSurfaceRegistry.install(videoTextureId, texture, output, this)
    }

    override fun onSurfaceChanged(width: Int, height: Int) = Unit

    override fun onNewFrame(headTransform: HeadTransform) {
        // Both eyes and final composition must use one settings snapshot.
        frameSettings = VrSession.settings
        framePanelOpen = VrSession.panelOpen
        headTransform.getQuaternion(nextHeadQuaternion, 0)
        val x = nextHeadQuaternion[0]
        val y = nextHeadQuaternion[1]
        val z = nextHeadQuaternion[2]
        val w = nextHeadQuaternion[3]
        val normSquared = x * x + y * y + z * z + w * w
        if (normSquared.isFinite() && normSquared > 1e-8f) {
            nextHeadQuaternion.copyInto(headQuaternion)
            val firstPose = !hasHeadPose
            hasHeadPose = true
            if (recenterPending || (firstPose && frameSettings.recenterOnEnter)) recenter()
        }
        // One capture per FRAME, not per eye. Both onDrawEye calls use the same
        // uploaded GL texture, so consuming on each eye would drop half the UI.
        capture.consume()?.let { bitmap ->
            try {
                if (panelTextureId != 0) uploadWindow(bitmap)
            } finally {
                capture.returnScene(bitmap)
            }
        }
        capture.consumePanel()?.let { bitmap ->
            try {
                if (quickPanelTextureId != 0) uploadQuickPanel(bitmap)
            } finally {
                capture.returnPanel(bitmap)
            }
        }
        if (!framePanelOpen) hasQuickPanelFrame = false
        if (isVideoHost && VrVideoSurfaceRegistry.consumeFrameAvailable(this)) {
            val texture = videoSurfaceTexture ?: return
            try {
                texture.updateTexImage()
                texture.getTransformMatrix(stMatrix)
                hasVideoFrame = true
            } catch (t: RuntimeException) {
                Log.w(TAG, "updateTexImage failed", t)
                hasVideoFrame = false
            }
        }
        if (hasVideoFrame) {
            val aspect = VrVideoSurfaceRegistry.videoAspect().takeIf { it.isFinite() && it > 0f } ?: 0f
            if (aspect != videoAspect) {
                videoAspect = aspect
                meshDirty = true
            }
        }
    }

    /** Recentre is marshalled onto the GL thread; see VrTheaterView.recenter(). */
    fun recenter() {
        if (!hasHeadPose) {
            recenterPending = true
            return
        }
        headQuaternion.copyInto(centerQuaternion)
        recenterPending = false
    }

    override fun onDrawEye(eye: CardboardView.Eye) {
        val s = frameSettings
        if (s.screenScale != lastScale || s.screenDistanceMeters != lastDistance ||
            s.curvature != lastCurvature || s.verticalFlip != lastVerticalFlip) meshDirty = true
        if (meshDirty && textureWidth > 0) rebuildMesh(textureWidth.toFloat() / textureHeight.toFloat())
        val eyeFromHead = eye.getEyeFromHead()
        val projection = eye.getPerspective(0.1f, 100f)
        val panelProgram = panel

        // The SDK owns the framebuffer and eye viewport. These layers are composited in order.
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        if (vertexCount != 0) {
            val head = if (s.headTrackingEnabled && hasHeadPose) {
                VrHeadPose.viewRotationInto(headRotation, headQuaternion, centerQuaternion, s.headTrackingSensitivity)
            } else identity
            VrMatrix4.multiplyInto(viewMatrix, eyeFromHead, head)
            VrMatrix4.multiplyInto(mvpMatrix, projection, viewMatrix)
            if (s.ipdScale != 1f) {
                val sign = when (eye.getEyeType()) {
                    CardboardView.Eye.LEFT -> 1f
                    CardboardView.Eye.RIGHT -> -1f
                    else -> 0f
                }
                VrMatrix4.applyIpdShiftInto(mvpMatrix, s.ipdScale, sign)
            }

            val videoProgram = video
            if (hasVideoFrame && videoProgram != null && videoTextureId != 0) {
                drawQuad(videoProgram, mvpMatrix, s.brightness,
                    GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId, videoPositionBuffer,
                    videoTexCoordBuffer, videoVertexCount, stMatrix)
            }
            if (panelProgram != null) {
                enableBitmapBlending()
                drawQuad(panelProgram, mvpMatrix, s.brightness, GLES20.GL_TEXTURE_2D, panelTextureId,
                    positionBuffer, texCoordBuffer, vertexCount)
            }
        }

        if (framePanelOpen && hasQuickPanelFrame && panelProgram != null) {
            VrMatrix4.multiplyInto(mvpMatrix, projection, eyeFromHead)
            enableBitmapBlending()
            drawQuad(panelProgram, mvpMatrix, 1f, GLES20.GL_TEXTURE_2D, quickPanelTextureId,
                quickPanelPositions, quickPanelTexCoords, quickPanelMesh.vertexCount)
        }
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun drawQuad(
        h: Handles,
        mvp: FloatArray,
        brightness: Float,
        target: Int,
        texture: Int,
        positions: FloatBuffer?,
        coords: FloatBuffer?,
        count: Int,
        st: FloatArray? = null,
    ) {
        val pos = positions ?: return
        val tex = coords ?: return
        GLES20.glUseProgram(h.program)
        GLES20.glUniformMatrix4fv(h.mvp, 1, false, mvp, 0)
        GLES20.glUniform1f(h.brightness, brightness)
        if (h.alpha >= 0) GLES20.glUniform1f(h.alpha, 1f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, texture)
        GLES20.glUniform1i(h.texture, 0)
        if (h.stMatrix >= 0 && st != null) {
            GLES20.glUniformMatrix4fv(h.stMatrix, 1, false, st, 0)
        }
        pos.position(0)
        tex.position(0)
        GLES20.glEnableVertexAttribArray(h.position)
        GLES20.glVertexAttribPointer(h.position, 3, GLES20.GL_FLOAT, false, 12, pos)
        GLES20.glEnableVertexAttribArray(h.texCoord)
        GLES20.glVertexAttribPointer(h.texCoord, 2, GLES20.GL_FLOAT, false, 8, tex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, count)
        GLES20.glDisableVertexAttribArray(h.position)
        GLES20.glDisableVertexAttribArray(h.texCoord)
    }

    private fun enableBitmapBlending() {
        // Android's Canvas writes premultiplied RGB. SRC_ALPHA would darken transparent edges.
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    private fun uploadWindow(bitmap: Bitmap) {
        val changed = bitmap.width != textureWidth || bitmap.height != textureHeight
        if (!uploadBitmap(panelTextureId, bitmap, changed)) return
        if (changed) {
            textureWidth = bitmap.width
            textureHeight = bitmap.height
            meshDirty = true
        }
        if (!loggedFirstFrame) {
            loggedFirstFrame = true
            Log.i(TAG, "first window frame ${bitmap.width}x${bitmap.height} uploaded")
        }
    }

    private fun uploadQuickPanel(bitmap: Bitmap) {
        val changed = bitmap.width != quickPanelTextureWidth || bitmap.height != quickPanelTextureHeight
        if (!uploadBitmap(quickPanelTextureId, bitmap, changed)) return
        quickPanelTextureWidth = bitmap.width
        quickPanelTextureHeight = bitmap.height
        hasQuickPanelFrame = true
    }

    private fun uploadBitmap(textureId: Int, bitmap: Bitmap, allocate: Boolean): Boolean {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        if (allocate) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        } else {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
        }
        if (!takeGlErrors()) return true
        if (!loggedBitmapUploadError) {
            loggedBitmapUploadError = true
            Log.w(TAG, "could not upload a VR bitmap (${bitmap.width}x${bitmap.height})")
        }
        return false
    }

    private fun rebuildMesh(aspect: Float) {
        val s = frameSettings
        val mesh = VrScreenGeometry.build(
            scale = s.screenScale,
            distance = s.screenDistanceMeters,
            verticalOffset = 0f,
            curvature = s.curvature,
            aspect = aspect,
        )
        lastScale = s.screenScale
        lastDistance = s.screenDistanceMeters
        lastCurvature = s.curvature
        lastVerticalFlip = s.verticalFlip
        meshDirty = false
        vertexCount = mesh.vertexCount
        positionBuffer = direct(mesh.positions)
        // GLUtils uploads bitmap row zero to V=0. The bitmap mesh already maps its top to V=0.
        texCoordBuffer = direct(mesh.texCoords)
        if (isVideoHost) {
            val videoMesh = VrScreenGeometry.buildFittedVideo(
                scale = s.screenScale,
                distance = s.screenDistanceMeters,
                curvature = s.curvature,
                sceneAspect = aspect,
                videoAspect = videoAspect,
            )
            videoVertexCount = videoMesh.vertexCount
            videoPositionBuffer = direct(videoMesh.positions)
            // Transform or flip video independently of the Android UI and framebuffer copy.
            videoTexCoordBuffer = direct(videoMesh.videoTexCoords(s.verticalFlip))
        }
    }

    private fun direct(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(values); position(0) }

    /**
     * P-B6 / P-D3: called AFTER the SDK rendered both eyes into the shared FBO
     * and composited them (with distortion) to the default framebuffer. The
     * viewport here is the WHOLE physical screen, so anything drawn now is the
     * final image and is not affected by per-eye GL state.
     */
    override fun onFinishFrame(viewport: Viewport) {
        val s = frameSettings
        if (s.zoomScale > 1.001f && !framePanelOpen) {
            drawZoomFill(viewport, s.zoomScale)
        }
        // P-D8: the fixed screen-space line is drawn LAST, never scaled.
        if (s.showCenterAlignmentLine) drawAlignmentLine(viewport)
    }

    private fun noteZoomSkip(reason: String) {
        if (zoomLoggedSkip != reason) {
            zoomLoggedSkip = reason
            zoomLoggedActive = false
            Log.w(TAG, "zoom FILL skipped: $reason")
        }
    }

    private fun drawZoomFill(viewport: Viewport, zoom: Float) {
        val w = viewport.width
        val h = viewport.height
        if (w <= 0 || h <= 0 || zoomTextureId == 0) {
            noteZoomSkip("invalid viewport $w x $h or texture not created")
            return
        }
        val hProgram = panel ?: return noteZoomSkip("panel program unavailable")
        if (w == unsupportedZoomWidth && h == unsupportedZoomHeight) return

        // Snapshot the SDK's completed stereo frame. A failed copy leaves that frame untouched.
        takeGlErrors()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, zoomTextureId)
        if (w != zoomTexW || h != zoomTexH) zoomSnapshotMode = -1
        if (zoomSnapshotMode == -1) {
            for (i in ZOOM_COPY_FORMATS.indices) {
                if (allocateZoomImage(w, h, ZOOM_COPY_FORMATS[i]) && copyZoomImage(viewport)) {
                    zoomSnapshotMode = i
                    Log.i(TAG, "zoom snapshot: format[$i] accepted ${w}x$h")
                    break
                }
            }
            if (zoomSnapshotMode == -1 && w.toLong() * h * 4 <= MAX_ZOOM_READBACK_BYTES &&
                allocateZoomImage(w, h, ZOOM_COPY_FORMATS[0]) && readZoomImage(viewport)
            ) {
                zoomSnapshotMode = -2
                Log.i(TAG, "zoom snapshot: readPixels fallback ${w}x$h")
            }
            if (zoomSnapshotMode == -1) {
                unsupportedZoomWidth = w
                unsupportedZoomHeight = h
                zoomReadback = null
                return noteZoomSkip("all snapshot formats failed")
            }
            zoomTexW = w
            zoomTexH = h
        } else {
            val copied = if (zoomSnapshotMode == -2) readZoomImage(viewport) else copyZoomImage(viewport)
            if (!copied) {
                zoomSnapshotMode = -1
                return noteZoomSkip("copy failed on this frame")
            }
        }

        val leftWidth = w / 2
        val rightWidth = w - leftWidth
        if (leftWidth <= 0 || rightWidth <= 0) return noteZoomSkip("viewport is too narrow for stereo")
        val verticalInset = (1f - 1f / zoom) / 2f
        val v0 = verticalInset
        val v1 = 1f - verticalInset
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        // The composed framebuffer contains left and right eyes side by side. A single full-width
        // crop moves both eye centers outward; crop each half around its own optical center instead.
        var eyeStart = 0
        for (eyeWidth in intArrayOf(leftWidth, rightWidth)) {
            val u0 = eyeStart.toFloat() / w
            val u1 = (eyeStart + eyeWidth).toFloat() / w
            val eyeCenter = (u0 + u1) / 2f
            val halfRange = (u1 - u0) / (2f * zoom)
            val left = eyeCenter - halfRange
            val right = eyeCenter + halfRange
            zoomUv.put(0, left).put(1, v0).put(2, right).put(3, v0)
            zoomUv.put(4, left).put(5, v1).put(6, right).put(7, v1)
            GLES20.glViewport(viewport.x + eyeStart, viewport.y, eyeWidth, h)
            drawQuad(hProgram, identity, 1f, GLES20.GL_TEXTURE_2D, zoomTextureId, zoomQuad, zoomUv, 4)
            eyeStart += eyeWidth
        }
        if (takeGlErrors()) return noteZoomSkip("snapshot draw failed")
        if (!zoomLoggedActive) {
            zoomLoggedActive = true
            zoomLoggedSkip = null
            Log.i(TAG, "zoom FILL active: ${w}x${h} @ ${zoom}x")
        }
    }

    private fun allocateZoomImage(width: Int, height: Int, format: IntArray): Boolean {
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, format[0], width, height, 0, format[1], format[2], null)
        return !takeGlErrors()
    }

    private fun copyZoomImage(viewport: Viewport): Boolean {
        GLES20.glCopyTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0,
            viewport.x, viewport.y, viewport.width, viewport.height)
        return !takeGlErrors()
    }

    private fun readZoomImage(viewport: Viewport): Boolean {
        val byteCount = viewport.width * viewport.height * 4
        val pixels = zoomReadback?.takeIf { it.capacity() == byteCount }
            ?: ByteBuffer.allocateDirect(byteCount).also { zoomReadback = it }
        pixels.position(0)
        GLES20.glReadPixels(viewport.x, viewport.y, viewport.width, viewport.height,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        if (takeGlErrors()) return false

        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, viewport.width, viewport.height,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        return !takeGlErrors()
    }

    private fun takeGlErrors(): Boolean {
        var sawError = false
        while (GLES20.glGetError() != GLES20.GL_NO_ERROR) sawError = true
        return sawError
    }

    /** P-D7: one fixed white vertical line at the physical screen centre. */
    private fun drawAlignmentLine(viewport: Viewport) {
        val h = panel ?: return
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(viewport.x, viewport.y, viewport.width, viewport.height)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        drawQuad(h, identity, 1f, GLES20.GL_TEXTURE_2D, whiteTextureId(), alignmentLine, unitUv, 4)
    }

    private var whiteTex = 0
    private fun whiteTextureId(): Int {
        if (whiteTex == 0) {
            whiteTex = genTexture(GLES20.GL_TEXTURE_2D)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
                java.nio.ByteBuffer.allocateDirect(4)
                    .put(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
                    .apply { position(0) })
        }
        return whiteTex
    }

    override fun onRendererShutdown() = release()

    private fun releaseVideoSurface() {
        if (videoSurface == null) return
        videoSurfaceTexture?.setOnFrameAvailableListener(null)
        VrVideoSurfaceRegistry.clear(this)
        videoSurface?.release()
        videoSurfaceTexture?.release()
        videoSurface = null
        videoSurfaceTexture = null
        hasVideoFrame = false
    }

    /**
     * Idempotent cleanup. CardboardView.onDestroy() only stops the GL thread, so
     * VrTheaterView also calls this through queueEvent() before shutting down.
     */
    fun release() {
        releaseVideoSurface()
        if (panelTextureId != 0) GLES20.glDeleteTextures(1, intArrayOf(panelTextureId), 0)
        if (quickPanelTextureId != 0) GLES20.glDeleteTextures(1, intArrayOf(quickPanelTextureId), 0)
        if (videoTextureId != 0) GLES20.glDeleteTextures(1, intArrayOf(videoTextureId), 0)
        if (zoomTextureId != 0) GLES20.glDeleteTextures(1, intArrayOf(zoomTextureId), 0)
        if (whiteTex != 0) GLES20.glDeleteTextures(1, intArrayOf(whiteTex), 0)
        zoomTextureId = 0
        whiteTex = 0
        zoomSnapshotMode = -1
        unsupportedZoomWidth = 0
        unsupportedZoomHeight = 0
        zoomReadback = null
        panel?.let { GLES20.glDeleteProgram(it.program) }
        video?.let { GLES20.glDeleteProgram(it.program) }
        panelTextureId = 0
        quickPanelTextureId = 0
        videoTextureId = 0
        panel = null
        video = null
        capture.releasePending()
        hasQuickPanelFrame = false
        hasVideoFrame = false
    }
}
