package com.limelight.vr

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.opengl.Matrix
import android.os.SystemClock
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.cardboard.sdk.CardboardView
import com.google.cardboard.sdk.HeadTransform
import com.google.cardboard.sdk.Viewport
import com.limelight.PcView
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import kotlin.math.abs
import kotlin.math.max
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic SDK poses, not sensor injection: production capture/renderer and real stereo GL output. */
@RunWith(AndroidJUnit4::class)
class VrPoseSceneRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun syntheticLocalPitchMovesBothEyesVerticallyAfterLandscapeRecenter() {
        val original = VrSession.settings
        instrumentation.runOnMainSync { VrSession.setEnabled(context, false) }
        val scenario = ActivityScenario.launch<PcView>(Intent(context, PcView::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var scene: PoseScene? = null
        var originalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        var originalVisibility = 0
        val records = mutableListOf("roll,pitch,eye,redX,redY,greenX,greenY,blueX,blueY,yellowX,yellowY")
        try {
            scenario.onActivity { activity ->
                assertTrue("The real Cardboard runtime must be available", VrSession.isSupported())
                originalOrientation = activity.requestedOrientation
                originalVisibility = activity.window.decorView.systemUiVisibility
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            await("Landscape window did not settle") {
                var ready = false
                scenario.onActivity { ready = it.resources.configuration.orientation ==
                    Configuration.ORIENTATION_LANDSCAPE && it.window.decorView.width > it.window.decorView.height }
                ready
            }
            scenario.onActivity { activity ->
                VrSession.updateSettings(activity, VrSettings.DEFAULT.copy(enabled = false,
                    headTrackingEnabled = true, headTrackingSensitivity = 1f, recenterOnEnter = true,
                    screenScale = 0.8f, screenDistanceMeters = 2f, brightness = 1f, curvature = 0f,
                    ipdScale = 1f, zoomScale = 1f, showCenterAlignmentLine = false))
                VrSession.setPanelOpen(false)
                @Suppress("DEPRECATION")
                activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                val root = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
                val marker = Marker(activity)
                root.addView(marker, FrameLayout.LayoutParams(256, 144))
                scene = PoseScene(activity, marker).also {
                    root.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT))
                }
                activity.setContentView(root)
                assertFalse("The automatic session host must remain disabled", VrSession.enabled)
            }
            val output = requireNotNull(scene)
            for (roll in floatArrayOf(-90f, 90f)) {
                val center = product(rotation(37f, 0f, 1f, 0f), rotation(roll, 0f, 0f, 1f))
                val prefix = if (roll < 0) "pose-roll-minus90" else "pose-roll-plus90"
                val baselineTag = output.setPhysicalPose(center, recenter = true)
                val baseline = awaitFrame(output, baselineTag, "$prefix-center.png") { it.upright() }
                var previous: Stereo? = null
                for (pitch in intArrayOf(-12, -6, 0, 6, 12)) {
                    val physical = product(center, rotation(pitch.toFloat(), 1f, 0f, 0f))
                    val tag = output.setPhysicalPose(physical, recenter = false)
                    val label = if (pitch < 0) "minus${-pitch}" else "plus$pitch"
                    val before = previous
                    val frame = awaitFrame(output, tag, "$prefix-pitch-$label.png") { candidate ->
                        candidate.upright() && candidate.eyes.indices.all { eye ->
                            val c = candidate.eyes[eye]
                            val base = baseline.eyes[eye]
                            val horizontalTolerance = max(4.0, base.width() * 0.12)
                            abs(c.centerX() - base.centerX()) < horizontalTolerance &&
                                abs(c[0].y - c[1].y) < max(4.0, c.height() * 0.12) &&
                                abs(c[2].y - c[3].y) < max(4.0, c.height() * 0.12) &&
                                if (before == null) c.centerY() < base.centerY() - 3.0
                                else c.centerY() > before.eyes[eye].centerY() + 3.0
                        }
                    }
                    frame.eyes.forEachIndexed { eye, colors ->
                        records += "$roll,$pitch,$eye," + colors.joinToString(",") { "${it.x},${it.y}" }
                    }
                    previous = frame
                }
            }
        } finally {
            File(directory(), "synthetic-pose-centroids.csv").writeText(records.joinToString("\n") + "\n")
            try {
                scenario.onActivity { activity ->
                    try {
                        scene?.release()
                        (scene?.parent as? ViewGroup)?.removeView(scene)
                    } finally {
                        activity.window.decorView.systemUiVisibility = originalVisibility
                        activity.requestedOrientation = originalOrientation
                        VrSession.updateSettings(activity, original)
                    }
                }
            } finally { scenario.close() }
        }
    }

    private class Marker(context: Context) : View(context) {
        private val paint = Paint()
        override fun onDraw(canvas: Canvas) {
            val colors = intArrayOf(Color.rgb(235, 35, 35), Color.rgb(30, 205, 65),
                Color.rgb(35, 60, 235), Color.rgb(235, 215, 25))
            for (i in colors.indices) {
                paint.color = colors[i]
                val left = if (i % 2 == 0) 0f else width / 2f
                val top = if (i < 2) 0f else height / 2f
                canvas.drawRect(left, top, left + width / 2f, top + height / 2f, paint)
            }
        }
    }

    private data class Pose(val tag: Int, val view: FloatArray, val recenter: Boolean)

    private class PoseScene(context: Context, source: View) : CardboardView(context) {
        private val capture = VrWindowCapture(source, VrPanelView(context), false)
        private val renderer = VrTheaterRenderer(capture, false)
        private val nextTag = AtomicInteger()
        private val pose = AtomicReference(Pose(0, rotation(0f, 1f, 0f, 0f), true))
        val completedTag = AtomicInteger(-1)
        val completedTagFrames = AtomicInteger()
        private var centeredTag = -1
        private var drawingTag = -1
        private var resumed = false

        init {
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child is SurfaceView) child.setZOrderOnTop(true) else child.visibility = View.GONE
            }
            setRenderer(object : CardboardView.Renderer {
                private val synthetic = HeadTransform()
                override fun onNewFrame(ignoredSensorPose: HeadTransform) {
                    val current = pose.get()
                    current.view.copyInto(synthetic.headView)
                    renderer.onNewFrame(synthetic)
                    if (current.recenter && centeredTag != current.tag) {
                        renderer.recenter()
                        centeredTag = current.tag
                    }
                    drawingTag = current.tag
                }
                override fun onDrawEye(eye: CardboardView.Eye) = renderer.onDrawEye(eye)
                override fun onFinishFrame(viewport: Viewport) {
                    renderer.onFinishFrame(viewport)
                    if (completedTag.getAndSet(drawingTag) == drawingTag) completedTagFrames.incrementAndGet()
                    else completedTagFrames.set(1)
                }
                override fun onSurfaceChanged(width: Int, height: Int) = renderer.onSurfaceChanged(width, height)
                override fun onSurfaceCreated(config: EGLConfig) = renderer.onSurfaceCreated(config)
                override fun onRendererShutdown() = renderer.onRendererShutdown()
            })
            setStereoRenderMode(true)
            keepScreenOn = true
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    removeOnAttachStateChangeListener(this)
                    onResume()
                    resumed = true
                    capture.markDirty()
                }
                override fun onViewDetachedFromWindow(v: View) = Unit
            })
        }

        fun setPhysicalPose(physical: FloatArray, recenter: Boolean): Int {
            val tag = nextTag.incrementAndGet()
            pose.set(Pose(tag, inverse(physical), recenter))
            return tag
        }

        fun release() {
            capture.stop()
            shutdownCalled()
            if (resumed) {
                queueEvent { renderer.release() }
                onPause()
                resumed = false
            }
            onDestroy()
        }
    }

    private data class Cloud(val count: Int, val x: Double, val y: Double)
    private data class Stereo(val eyes: List<List<Cloud>>, val landscape: Boolean) {
        fun upright() = landscape && eyes.all { c -> c.all { it.count > 20 } &&
            c[0].x + 3 < c[1].x && c[2].x + 3 < c[3].x &&
            c[0].y + 3 < c[2].y && c[1].y + 3 < c[3].y }
    }

    private fun List<Cloud>.centerX() = map { it.x }.average()
    private fun List<Cloud>.centerY() = map { it.y }.average()
    private fun List<Cloud>.width() = ((this[1].x - this[0].x) + (this[3].x - this[2].x)) / 2
    private fun List<Cloud>.height() = ((this[2].y - this[0].y) + (this[3].y - this[1].y)) / 2

    private fun inspect(bitmap: Bitmap): Stereo {
        val count = Array(2) { IntArray(4) }
        val sx = Array(2) { DoubleArray(4) }
        val sy = Array(2) { DoubleArray(4) }
        for (y in 0 until bitmap.height step 2) for (x in 0 until bitmap.width step 2) {
            val pixel = bitmap.getPixel(x, y)
            val r = Color.red(pixel); val g = Color.green(pixel); val b = Color.blue(pixel)
            val color = when {
                r > 140 && r > g * 1.5 && r > b * 1.5 -> 0
                g > 115 && g > r * 1.45 && g > b * 1.25 -> 1
                b > 130 && b > r * 1.5 && b > g * 1.5 -> 2
                r > 140 && g > 125 && b < 100 -> 3
                else -> continue
            }
            val eye = if (x < bitmap.width / 2) 0 else 1
            count[eye][color]++; sx[eye][color] += x; sy[eye][color] += y
        }
        return Stereo((0..1).map { eye -> (0..3).map { color ->
            Cloud(count[eye][color], sx[eye][color] / maxOf(1, count[eye][color]),
                sy[eye][color] / maxOf(1, count[eye][color]))
        } }, bitmap.width > bitmap.height)
    }

    private fun awaitFrame(scene: PoseScene, tag: Int, name: String, matches: (Stereo) -> Boolean): Stereo {
        val deadline = SystemClock.uptimeMillis() + 20000L
        var last: Bitmap? = null
        var stats: Stereo? = null
        try {
            do {
                // Wait for a second finished frame of this pose so a screenshot of the compositor
                // cannot reuse the previous pose before the first tagged GL swap completes.
                if (scene.completedTag.get() >= tag && scene.completedTagFrames.get() >= 2) {
                    instrumentation.uiAutomation.takeScreenshot()?.let { captured ->
                        last?.recycle()
                        last = captured.copy(Bitmap.Config.ARGB_8888, false)
                        captured.recycle()
                        stats = last?.let(::inspect)
                    }
                    if (stats?.let(matches) == true) return requireNotNull(stats)
                }
                SystemClock.sleep(100L)
            } while (SystemClock.uptimeMillis() < deadline)
            throw AssertionError("$name did not show expected synthetic pitch in both eyes: $stats")
        } finally {
            last?.let { captured ->
                try {
                    val width = minOf(640, captured.width)
                    val scaled = Bitmap.createScaledBitmap(captured, width, captured.height * width / captured.width, true)
                    try { File(directory(), name).outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                    finally { if (scaled !== captured) scaled.recycle() }
                } finally { captured.recycle() }
            }
        }
    }

    private fun directory() = File(context.getExternalFilesDir(null), "vr-review").apply { mkdirs() }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50L)
        }
        fail(message)
    }

    private companion object {
        fun rotation(degrees: Float, x: Float, y: Float, z: Float): FloatArray =
            FloatArray(16).also { Matrix.setRotateM(it, 0, degrees, x, y, z) }
        fun product(lhs: FloatArray, rhs: FloatArray): FloatArray =
            FloatArray(16).also { Matrix.multiplyMM(it, 0, lhs, 0, rhs, 0) }
        fun inverse(value: FloatArray): FloatArray = FloatArray(16).also {
            assertTrue("Pose must be invertible", Matrix.invertM(it, 0, value, 0))
        }
    }
}
