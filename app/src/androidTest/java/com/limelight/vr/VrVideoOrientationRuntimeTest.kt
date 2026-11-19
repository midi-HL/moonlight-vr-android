package com.limelight.vr

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.PcView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/** Real decoder -> production SurfaceTexture/ST matrix/OES -> Cardboard -> zoom pixels. */
@RunWith(AndroidJUnit4::class)
class VrVideoOrientationRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun decodedVideoStaysUprightThroughStereoZoomAndExplicitFlip() {
        val original = VrSession.settings
        instrumentation.runOnMainSync { VrSession.setEnabled(context, false) }
        val scenario = ActivityScenario.launch<PcView>(Intent(context, PcView::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var theater: VrTheaterView? = null
        var codec: MediaCodec? = null
        var output: Surface? = null
        var originalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        var originalVisibility = 0
        try {
            scenario.onActivity { activity ->
                assertTrue("The real Cardboard runtime must be available", VrSession.isSupported())
                originalOrientation = activity.requestedOrientation
                originalVisibility = activity.window.decorView.systemUiVisibility
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            waitForUi("Landscape window did not settle") {
                var ready = false
                scenario.onActivity { ready = it.resources.configuration.orientation ==
                    Configuration.ORIENTATION_LANDSCAPE && it.window.decorView.width > it.window.decorView.height }
                ready
            }
            scenario.onActivity { activity ->
                VrSession.updateSettings(activity, VrSettings.DEFAULT.copy(enabled = false,
                    headTrackingEnabled = false, screenScale = 0.65f, screenDistanceMeters = 2.5f,
                    brightness = 1f, curvature = 0f, ipdScale = 1f, zoomScale = 1f,
                    verticalFlip = VrSettings.DEFAULT.verticalFlip, showCenterAlignmentLine = false))
                VrSession.setPanelOpen(false)
                @Suppress("DEPRECATION")
                activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                val root = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
                // Capture only transparent pixels, never the opaque Activity decor over the video.
                val source = View(activity)
                root.addView(source, FrameLayout.LayoutParams(256, 144))
                val video = VrTheaterView(activity, source, isVideoHost = true)
                theater = video
                root.addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT))
                activity.setContentView(root)
                assertFalse("The automatic host must stay disabled", VrSession.enabled)
            }
            val deadline = SystemClock.uptimeMillis() + 45000L
            while (output == null && SystemClock.uptimeMillis() < deadline) {
                output = synchronized(VrVideoSurfaceRegistry) {
                    VrVideoSurfaceRegistry.currentTexture()?.let { Surface(it) }
                }
                if (output == null) SystemClock.sleep(50L)
            }
            assertNotNull("Production renderer did not install its video SurfaceTexture", output)
            codec = decodeFixture(requireNotNull(output))
            val upright = awaitPixels("video-default-upright.png") { it.upright(false) }
            scenario.onActivity { VrSession.updateSettings(it, VrSession.settings.copy(zoomScale = 1.35f)) }
            val zoomed = awaitPixels("video-zoom-135-upright.png") { frame ->
                frame.upright(false) && frame.eyes.indices.all {
                    frame.separation(it) > upright.separation(it) * 1.1
                }
            }
            scenario.onActivity { VrSession.updateSettings(it, VrSession.settings.copy(
                verticalFlip = !VrSettings.DEFAULT.verticalFlip)) }
            awaitPixels("video-zoom-135-explicit-flip.png") { frame ->
                frame.upright(true) && frame.eyes.indices.all { eye ->
                    frame.eyes[eye].indices.all { color ->
                        abs(frame.eyes[eye][color].x - zoomed.eyes[eye][color].x) <
                            max(8.0, zoomed.separation(eye) * 0.2)
                    }
                }
            }
        } finally {
            // Stop the producer before releasing the consumer's texture or GL context.
            try {
                codec?.let { try { it.stop() } finally { it.release() } }
            } finally {
                try { output?.release() } finally {
                    try {
                        scenario.onActivity { activity ->
                            try {
                                theater?.release()
                                (theater?.parent as? ViewGroup)?.removeView(theater)
                            } finally {
                                activity.window.decorView.systemUiVisibility = originalVisibility
                                activity.requestedOrientation = originalOrientation
                                VrSession.updateSettings(activity, original)
                            }
                        }
                    } finally { scenario.close() }
                }
            }
        }
    }

    private fun decodeFixture(surface: Surface): MediaCodec {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            instrumentation.context.assets.openFd("vr-orientation.mp4").use {
                extractor.setDataSource(it.fileDescriptor, it.startOffset, it.declaredLength)
            }
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "video/avc"
            }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            decoder = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            decoder.configure(format, surface, null, 0)
            decoder.start()
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var rendered = 0
            val deadline = SystemClock.uptimeMillis() + 15000L
            while (!outputEnded && SystemClock.uptimeMillis() < deadline) {
                if (!inputEnded) {
                    val index = decoder.dequeueInputBuffer(10000L)
                    if (index >= 0) {
                        val buffer = requireNotNull(decoder.getInputBuffer(index)).apply { clear() }
                        val size = extractor.readSampleData(buffer, 0)
                        inputEnded = size < 0
                        decoder.queueInputBuffer(index, 0, maxOf(0, size),
                            if (inputEnded) 0L else extractor.sampleTime,
                            if (inputEnded) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        if (!inputEnded) extractor.advance()
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 10000L)
                if (index >= 0) {
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val render = !outputEnded || info.size > 0
                    decoder.releaseOutputBuffer(index, render)
                    if (render) rendered++
                }
            }
            assertTrue("AVC fixture must reach decoder EOS", outputEnded)
            assertTrue("The real decoder must produce video frames", rendered > 0)
            return decoder
        } catch (failure: Throwable) {
            decoder?.let { runCatching { it.stop() }; it.release() }
            throw failure
        } finally { extractor.release() }
    }

    private data class Cloud(val count: Int, val x: Double, val y: Double)
    private data class Stereo(val eyes: List<List<Cloud>>, val landscape: Boolean) {
        fun upright(flipped: Boolean): Boolean = landscape && eyes.all { c ->
            c.all { it.count > 20 } && c[0].x + 3 < c[1].x && c[2].x + 3 < c[3].x &&
                if (flipped) c[2].y + 3 < c[0].y && c[3].y + 3 < c[1].y
                else c[0].y + 3 < c[2].y && c[1].y + 3 < c[3].y
        }
        fun separation(eye: Int): Double = eyes[eye].let { ((it[1].x - it[0].x) + (it[3].x - it[2].x)) / 2 }
    }

    private fun inspect(bitmap: Bitmap): Stereo {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val count = Array(2) { IntArray(4) }
        val sx = Array(2) { DoubleArray(4) }
        val sy = Array(2) { DoubleArray(4) }
        for (y in 0 until bitmap.height step 2) for (x in 0 until bitmap.width step 2) {
            val p = pixels[y * bitmap.width + x]
            val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
            val color = when {
                r > 140 && r > g * 1.5 && r > b * 1.5 -> 0
                g > 115 && g > r * 1.45 && g > b * 1.25 -> 1
                b > 130 && b > r * 1.5 && b > g * 1.5 -> 2
                r > 140 && g > 125 && b < 100 && r > b * 1.7 && g > b * 1.7 -> 3
                else -> continue
            }
            val eye = if (x < bitmap.width / 2) 0 else 1
            count[eye][color]++; sx[eye][color] += x; sy[eye][color] += y
        }
        return Stereo((0..1).map { e -> (0..3).map { c ->
            Cloud(count[e][c], sx[e][c] / maxOf(1, count[e][c]), sy[e][c] / maxOf(1, count[e][c]))
        } }, bitmap.width > bitmap.height)
    }

    private fun awaitPixels(name: String, matches: (Stereo) -> Boolean): Stereo {
        val deadline = SystemClock.uptimeMillis() + 45000L
        var last: Bitmap? = null
        var stats: Stereo? = null
        try {
            do {
                instrumentation.uiAutomation.takeScreenshot()?.let { captured ->
                    last?.recycle()
                    last = captured.copy(Bitmap.Config.ARGB_8888, false)
                    captured.recycle()
                    stats = last?.let(::inspect)
                }
                if (stats?.let(matches) == true) return requireNotNull(stats)
                SystemClock.sleep(150L)
            } while (SystemClock.uptimeMillis() < deadline)
            throw AssertionError("$name did not show expected quadrant order/zoom in both eyes: $stats")
        } finally {
            last?.let { frame ->
                try {
                    val directory = File(context.getExternalFilesDir(null), "vr-review").apply { mkdirs() }
                    File(directory, name).outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { frame.recycle() }
            }
        }
    }

    private fun waitForUi(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50L)
        }
        throw AssertionError(message)
    }
}
