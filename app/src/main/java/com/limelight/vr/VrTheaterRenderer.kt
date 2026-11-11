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
    @Volatile private var meshDirty = true

    private val headQuaternion = FloatArray(4)
    private val centerQuaternion = FloatArray(4).also { it[3] = 1f }
    private val headRotation = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val identity = FloatArray(16).also { VrMatrix4.identityInto(it) }
    private var loggedFirstFrame = false

    fun markMeshDirty() { meshDirty = true }

    override fun onSurfaceCreated(config: EGLConfig) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glDisable(GLES20.GL_DITHER)
        panel = runCatching { createHandles(PANEL_FRAGMENT_SHADER) }
            .onFailure { Log.e(TAG, "panel shader link failed", it) }.getOrNull()
        video = runCatching { createHandles(VIDEO_FRAGMENT_SHADER) }
            .onFailure { Log.e(TAG, "video shader link failed", it) }.getOrNull()
        panelTextureId = genTexture(GLES20.GL_TEXTURE_2D)
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
            s.curvature != lastCurvature) meshDirty = true
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
        meshDirty = false
        vertexCount = mesh.vertexCount
        positionBuffer = direct(mesh.positions)
        // Canvas bitmaps are top-down; GL textures are bottom-up. Swap V so the
        // UI reads upright in the headset without changing any shader.
        val flipped = mesh.texCoords.copyOf()
        for (i in 1 until flipped.size step 2) flipped[i] = 1f - flipped[i]
        texCoordBuffer = direct(flipped)
        // SurfaceTexture already delivers top-down UVs; do NOT flip those.
        videoTexCoordBuffer = direct(mesh.texCoords)
    }

    private fun direct(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(values); position(0) }

    override fun onFinishFrame(viewport: Viewport) = Unit

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
