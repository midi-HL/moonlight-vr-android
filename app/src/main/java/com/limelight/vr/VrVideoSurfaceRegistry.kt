package com.limelight.vr

import android.graphics.SurfaceTexture
import android.view.Surface
import com.limelight.binding.video.MediaCodecDecoderRenderer
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridge between Game's MediaCodec decoder and the VR GL thread.
 *
 * [target] is set by VrTheaterRenderer.onSurfaceCreated on the GL thread and
 * delivered to whatever decoder Game currently owns. [clear] switches the decoder
 * back to its original SurfaceHolder surface. The stream is never restarted.
 */
internal object VrVideoSurfaceRegistry {
    @Volatile private var surfaceTexture: SurfaceTexture? = null
    @Volatile private var surface: Surface? = null
    @Volatile private var textureId: Int = 0
    @Volatile private var decoder: WeakReference<MediaCodecDecoderRenderer>? = null
    @Volatile private var surfaceOwner: Any? = null
    private val frameAvailable = AtomicBoolean(false)

    val isActive: Boolean get() = surface != null
    fun currentTexture(): SurfaceTexture? = surfaceTexture
    fun currentTextureId(): Int = textureId

    fun markFrameAvailable() { frameAvailable.set(true) }
    fun consumeFrameAvailable(): Boolean = frameAvailable.compareAndSet(true, false)

    /** Decoder registers itself from Game; immediately adopts an active VR surface. */
    fun attachDecoder(renderer: MediaCodecDecoderRenderer) {
        decoder = WeakReference(renderer)
        renderer.setVrOutputSurface(surface)
    }

    fun detachDecoder(renderer: MediaCodecDecoderRenderer) {
        if (decoder?.get() === renderer) decoder = null
        renderer.setVrOutputSurface(null)
    }

    /** GL thread only. */
    fun install(id: Int, texture: SurfaceTexture, output: Surface, owner: Any) {
        clear()
        textureId = id
        surfaceTexture = texture
        surface = output
        surfaceOwner = owner
        texture.setOnFrameAvailableListener { markFrameAvailable() }
        decoder?.get()?.setVrOutputSurface(output)
    }

    /**
     * Drops references and restores the 2D surface. [owner] guards against a
     * stale non-video Activity stopping its GL thread after a newer Game host
     * has already installed its own surface.
     */
    fun clear(owner: Any? = null) {
        if (owner != null && surfaceOwner !== owner) return
        surface = null
        surfaceTexture = null
        surfaceOwner = null
        textureId = 0
        frameAvailable.set(false)
        decoder?.get()?.setVrOutputSurface(null)
    }
}
