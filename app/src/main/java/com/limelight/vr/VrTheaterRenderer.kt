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
 * One flat quad carries the captured Activity window (home page, settings,
 * dialogs and quick panel) as GL_TEXTURE_2D. When a stream is running its
 * frames arrive through a SurfaceTexture (GL_TEXTURE_EXTERNAL_OES) which is
 * drawn first; the window bitmap is then alpha-blended on top so overlays,
 * dialogs and the quick settings panel stay visible over the game picture.
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
    private var videoTextureId = 0
    private var videoSurfaceTexture: SurfaceTexture? = null
    private var videoSurface: Surface? = null
    private val stMatrix = FloatArray(16).also { VrMatrix4.identityInto(it) }

    private var positionBuffer: FloatBuffer? = null
    private var texCoordBuffer: FloatBuffer? = null
    private var videoTexCoordBuffer: FloatBuffer? = null
    private var vertexCount = 0
    private var textureWidth = 0
    private var textureHeight = 0
    private var uploaded: Bitmap? = null
    @Volatile private var hasVideoFrame = false

    private var lastScale = -1f
    private var lastDistance = -1f
    private var lastCurvature = -1f
    private var lastVerticalFlip: Boolean? = null
    @Volatile private var meshDirty = true

    private val headQuaternion = FloatArray(4)
    private val centerQuaternion = FloatArray(4).also { it[3] = 1f }
    private val headRotation = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val identity = FloatArray(16).also { VrMatrix4.identityInto(it) }
    private var loggedFirstFrame = false

    // P-D3: zoom-fill post-process. Cardboard's distortion mesh leaves vertical
    // black bands; we copy the SDK's FINAL default-framebuffer frame and blit it
    // back centre-zoomed in onFinishFrame(), with no FOV / projection / shader /
    // SDK changes. All GL objects are owned by the GL thread.
    private var zoomTextureId = 0
    private var zoomSnapshotMode = -1 // -1 = not probed, -2 = readPixels, 0..2 = copy format
    private var zoomTexW = 0
    private var zoomTexH = 0
    private var zoomQuad: FloatBuffer? = null
    private var zoomUv: FloatBuffer? = null
    private var zoomUnsupported = false
    private var zoomLoggedActive = false
    private var zoomLoggedSkip: String? = null
    private val ZOOM_COPY_FORMATS = arrayOf(
        intArrayOf(GLES20.GL_RGBA, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE),
        intArrayOf(GLES20.GL_RGB, GLES20.GL_RGB, GLES20.GL_UNSIGNED_SHORT_5_6_5),
        intArrayOf(GLES20.GL_RGB, GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE),
    )

    fun markMeshDirty() { meshDirty = true }

    override fun onSurfaceCreated(config: EGLConfig) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glDisable(GLES20.GL_DITHER)
        panel = runCatching { createHandles(PANEL_FRAGMENT_SHADER) }
            .onFailure { Log.e(TAG, "panel shader link failed", it) }.getOrNull()
        video = runCatching { createHandles(VIDEO_FRAGMENT_SHADER) }
            .onFailure { Log.e(TAG, "video shader link failed", it) }.getOrNull()
        panelTextureId = genTexture(GLES20.GL_TEXTURE_2D)
        zoomTextureId = genTexture(GLES20.GL_TEXTURE_2D)
        // Full-screen quad in clip space. glCopyTexSubImage2D reads the
        // framebuffer with a bottom-left origin, so UV (0,0) belongs at the
        // BOTTOM vertex — no V flip here (that would invert the whole picture).
        zoomQuad = direct(floatArrayOf(-1f, -1f, 0f, 1f, -1f, 0f, -1f, 1f, 0f, 1f, 1f, 0f))
        zoomUv = direct(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
        zoomSnapshotMode = -1
        zoomUnsupported = false
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
        headTransform.getQuaternion(headQuaternion, 0)
        // One capture per FRAME, not per eye. Both onDrawEye calls use the same
        // uploaded GL texture, so consuming on each eye would drop half the UI.
        val bitmap = capture.consume()
        if (bitmap != null && panelTextureId != 0) {
            upload(bitmap)
            if (!loggedFirstFrame) {
                loggedFirstFrame = true
                Log.i(TAG, "first window frame ${bitmap.width}x${bitmap.height} uploaded")
            }
        }
        if (isVideoHost && VrVideoSurfaceRegistry.consumeFrameAvailable()) {
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
    }

    /** Recentre is marshalled onto the GL thread; see VrTheaterView.recenter(). */
    fun recenter() {
        centerQuaternion[0] = headQuaternion[0]
        centerQuaternion[1] = headQuaternion[1]
        centerQuaternion[2] = headQuaternion[2]
        centerQuaternion[3] = headQuaternion[3]
    }

    override fun onDrawEye(eye: CardboardView.Eye) {
        val s = VrSession.settings
        if (s.screenScale != lastScale || s.screenDistanceMeters != lastDistance ||
            s.curvature != lastCurvature || s.verticalFlip != lastVerticalFlip) meshDirty = true
        if (meshDirty && textureWidth > 0) rebuildMesh(textureWidth.toFloat() / textureHeight.toFloat())
        if (vertexCount == 0) return

        val head = if (s.headTrackingEnabled) {
            VrHeadPose.viewRotationInto(headRotation, headQuaternion, centerQuaternion, s.headTrackingSensitivity)
        } else identity
        VrMatrix4.multiplyInto(viewMatrix, eye.getEyeFromHead(), head)
        VrMatrix4.multiplyInto(mvpMatrix, eye.getPerspective(0.1f, 100f), viewMatrix)
        if (s.ipdScale != 1f) {
            val sign = when (eye.getEyeType()) {
                CardboardView.Eye.LEFT -> 1f
                CardboardView.Eye.RIGHT -> -1f
                else -> 0f
            }
            VrMatrix4.applyIpdShiftInto(mvpMatrix, s.ipdScale, sign)
        }

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        val useVideo = hasVideoFrame && video != null && videoTextureId != 0
        if (useVideo) {
            drawQuad(video!!, mvpMatrix, s.brightness, 1f,
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId, videoTexCoordBuffer, stMatrix)
        }
        // The window bitmap is opaque black where the 2D UI is empty; draw it
        // with alpha so a live stream remains visible behind the UI chrome.
        panel?.let {
            drawQuad(it, mvpMatrix, s.brightness, 1f,
                GLES20.GL_TEXTURE_2D, panelTextureId, texCoordBuffer, null)
        }
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun drawQuad(
        h: Handles, mvp: FloatArray, brightness: Float, alpha: Float,
        target: Int, texture: Int, coords: FloatBuffer?, st: FloatArray?,
    ) {
        val pos = positionBuffer ?: return
        val tex = coords ?: return
        GLES20.glUseProgram(h.program)
        GLES20.glUniformMatrix4fv(h.mvp, 1, false, mvp, 0)
        GLES20.glUniform1f(h.brightness, brightness)
        if (h.alpha >= 0) GLES20.glUniform1f(h.alpha, alpha)
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
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, vertexCount)
        GLES20.glDisableVertexAttribArray(h.position)
        GLES20.glDisableVertexAttribArray(h.texCoord)
    }

    private fun upload(bitmap: Bitmap) {
        val changed = bitmap.width != textureWidth || bitmap.height != textureHeight
        if (changed) {
            textureWidth = bitmap.width
            textureHeight = bitmap.height
            meshDirty = true
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, panelTextureId)
        if (changed) {
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                textureWidth, textureHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        }
        GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
        uploaded?.recycle()
        uploaded = bitmap
    }

    private fun rebuildMesh(aspect: Float) {
        val s = VrSession.settings
        val mesh = VrScreenGeometry.build(
            scale = s.screenScale,
            distance = s.screenDistanceMeters,
            verticalOffset = 0f,
            curvature = s.curvature,
            aspect = aspect,
            segments = 1,
        )
        lastScale = s.screenScale
        lastDistance = s.screenDistanceMeters
        lastCurvature = s.curvature
        lastVerticalFlip = s.verticalFlip
        meshDirty = false
        vertexCount = mesh.vertexCount
        positionBuffer = direct(mesh.positions)
        // Canvas bitmaps are top-down; GL textures are bottom-up. Swap V so the
        // UI reads upright in the headset without changing any shader.
        val flipped = mesh.texCoords.copyOf()
        for (i in 1 until flipped.size step 2) flipped[i] = 1f - flipped[i]
        texCoordBuffer = direct(flipped)
        // P-C1: MediaCodec -> SurfaceTexture -> GL_TEXTURE_EXTERNAL_OES has a
        // natively inverted V axis relative to this quad. Flip ONLY the video
        // UVs on the CPU (never the shader / stMatrix, never the panel/menu
        // texture). User-toggleable because a different decoder path may differ.
        val videoUv = mesh.texCoords.copyOf()
        if (s.verticalFlip) {
            for (i in 1 until videoUv.size step 2) videoUv[i] = 1f - videoUv[i]
        }
        videoTexCoordBuffer = direct(videoUv)
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
        val s = VrSession.settings
        if (s.zoomScale > 1.001f && !VrSession.panelOpen) {
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
        if (zoomUnsupported) return noteZoomSkip("snapshot permanently unsupported")

        // GLES2 rejects glCopyTexSubImage2D when the window buffer format does
        // not match the texture format. Probe RGBA8 / RGB565 / RGB8 once, then
        // remember the accepted format. Every failure path returns BEFORE any
        // glClear so the SDK's original picture stays intact (no black screen).
        while (GLES20.glGetError() != GLES20.GL_NO_ERROR) { /* drain stale errors */ }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, zoomTextureId)
        if (zoomSnapshotMode == -1) {
            var accepted = false
            for (i in ZOOM_COPY_FORMATS.indices) {
                val f = ZOOM_COPY_FORMATS[i]
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, f[0], w, h, 0, f[0], f[2], null)
                GLES20.glCopyTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h)
                if (GLES20.glGetError() == GLES20.GL_NO_ERROR) {
                    zoomSnapshotMode = i
                    zoomTexW = w
                    zoomTexH = h
                    accepted = true
                    Log.i(TAG, "zoom snapshot: format[$i] accepted ${w}x$h")
                    break
                }
            }
            if (!accepted) {
                // Last resort: readPixels + upload (slower, but never black).
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                val bytes = w.toLong() * h * 4
                if (bytes in 1..32L * 1024 * 1024) {
                    val pixels = java.nio.ByteBuffer.allocateDirect(bytes.toInt())
                        .order(ByteOrder.nativeOrder())
                    GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
                    if (GLES20.glGetError() == GLES20.GL_NO_ERROR) {
                        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, w, h,
                            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
                        zoomSnapshotMode = -2
                        zoomTexW = w
                        zoomTexH = h
                        Log.i(TAG, "zoom snapshot: readPixels fallback ${w}x$h")
                    }
                }
            }
            if (zoomSnapshotMode == -1) {
                zoomUnsupported = true
                return noteZoomSkip("all snapshot formats failed")
            }
        } else {
            if (w != zoomTexW || h != zoomTexH) {
                val f = if (zoomSnapshotMode == -2) ZOOM_COPY_FORMATS[0] else ZOOM_COPY_FORMATS[zoomSnapshotMode]
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, f[0], w, h, 0, f[0], f[2], null)
                zoomTexW = w
                zoomTexH = h
            }
            if (zoomSnapshotMode == -2) {
                val bytes = w.toLong() * h * 4
                if (bytes !in 1..32L * 1024 * 1024) return noteZoomSkip("readback too large")
                val pixels = java.nio.ByteBuffer.allocateDirect(bytes.toInt()).order(ByteOrder.nativeOrder())
                GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
                GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, w, h,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            } else {
                GLES20.glCopyTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h)
                if (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
                    zoomSnapshotMode = -1 // re-probe next frame after a GL context change
                    return noteZoomSkip("copy failed on this frame")
                }
            }
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        // CENTRE zoom: GLES2 allows a viewport larger than the framebuffer; the
        // overflow is clipped symmetrically on all four edges. Starting at (0,0)
        // would push the picture to the top-right instead of cropping the bands.
        val zw = (w * zoom).toInt()
        val zh = (h * zoom).toInt()
        GLES20.glViewport((w - zw) / 2, (h - zh) / 2, zw, zh)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(hProgram.program)
        GLES20.glUniformMatrix4fv(hProgram.mvp, 1, false, identity, 0)
        GLES20.glUniform1f(hProgram.brightness, 1f)
        GLES20.glUniform1f(hProgram.alpha, 1f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, zoomTextureId)
        GLES20.glUniform1i(hProgram.texture, 0)
        val pos = zoomQuad ?: return
        val uv = zoomUv ?: return
        pos.position(0)
        uv.position(0)
        GLES20.glEnableVertexAttribArray(hProgram.position)
        GLES20.glVertexAttribPointer(hProgram.position, 3, GLES20.GL_FLOAT, false, 12, pos)
        GLES20.glEnableVertexAttribArray(hProgram.texCoord)
        GLES20.glVertexAttribPointer(hProgram.texCoord, 2, GLES20.GL_FLOAT, false, 8, uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(hProgram.position)
        GLES20.glDisableVertexAttribArray(hProgram.texCoord)
        GLES20.glViewport(0, 0, w, h)
        if (!zoomLoggedActive) {
            zoomLoggedActive = true
            zoomLoggedSkip = null
            Log.i(TAG, "zoom FILL active: ${w}x${h} @ ${zoom}x")
        }
    }

    /** P-D7: one fixed white vertical line at the physical screen centre. */
    private fun drawAlignmentLine(viewport: Viewport) {
        val h = panel ?: return
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, viewport.width, viewport.height)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(h.program)
        GLES20.glUniformMatrix4fv(h.mvp, 1, false, identity, 0)
        GLES20.glUniform1f(h.brightness, 1f)
        GLES20.glUniform1f(h.alpha, 1f)
        // Reuse a 1x1 white texture by binding the zoom texture is unsafe (it is
        // a snapshot); instead use a tiny solid colour via the panel program with
        // the video texture disabled: draw a thin quad with a constant white texel.
        val px = 0.003f
        val line = direct(floatArrayOf(-px, -1f, 0f, px, -1f, 0f, -px, 0f, 0f, px, 0f, 0f))
        val uv = direct(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, whiteTextureId())
        GLES20.glUniform1i(h.texture, 0)
        line.position(0); uv.position(0)
        GLES20.glEnableVertexAttribArray(h.position)
        GLES20.glVertexAttribPointer(h.position, 3, GLES20.GL_FLOAT, false, 12, line)
        GLES20.glEnableVertexAttribArray(h.texCoord)
        GLES20.glVertexAttribPointer(h.texCoord, 2, GLES20.GL_FLOAT, false, 8, uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(h.position)
        GLES20.glDisableVertexAttribArray(h.texCoord)
    }

    private var whiteTex = 0
    private fun whiteTextureId(): Int {
        if (whiteTex == 0) {
            val ids = intArrayOf(0)
            GLES20.glGenTextures(1, ids, 0)
            whiteTex = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, whiteTex)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
                java.nio.ByteBuffer.allocateDirect(4)
                    .put(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
                    .apply { position(0) })
        }
        return whiteTex
    }

    override fun onRendererShutdown() = release()

    /**
     * Idempotent cleanup. CardboardView.onDestroy() only stops the GL thread, so
     * VrTheaterView also calls this through queueEvent() before shutting down.
     */
    fun release() {
        VrVideoSurfaceRegistry.clear(this)
        videoSurface?.release()
        videoSurfaceTexture?.release()
        videoSurface = null
        videoSurfaceTexture = null
        if (panelTextureId != 0) GLES20.glDeleteTextures(1, intArrayOf(panelTextureId), 0)
        if (videoTextureId != 0) GLES20.glDeleteTextures(1, intArrayOf(videoTextureId), 0)
        if (zoomTextureId != 0) GLES20.glDeleteTextures(1, intArrayOf(zoomTextureId), 0)
        if (whiteTex != 0) GLES20.glDeleteTextures(1, intArrayOf(whiteTex), 0)
        zoomTextureId = 0
        whiteTex = 0
        zoomSnapshotMode = -1
        zoomUnsupported = false
        panel?.let { GLES20.glDeleteProgram(it.program) }
        video?.let { GLES20.glDeleteProgram(it.program) }
        panelTextureId = 0
        videoTextureId = 0
        panel = null
        video = null
        uploaded?.recycle()
        uploaded = null
        capture.releasePending()
        hasVideoFrame = false
    }
}
