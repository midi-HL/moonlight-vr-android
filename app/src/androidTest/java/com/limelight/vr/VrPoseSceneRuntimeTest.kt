package com.limelight.vr

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.opengl.GLES20
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
    private data class TagArea(
        val viewportX: Int, val viewportY: Int, val viewportWidth: Int, val viewportHeight: Int,
        val surfaceWidth: Int, val surfaceHeight: Int, val left: Int, val bottom: Int, val cell: Int,
    )
    private data class VisibleTag(val tag: Int?, val details: String)

    private class PoseScene(context: Context, source: View) : CardboardView(context) {
        private val capture = VrWindowCapture(source, VrPanelView(context), false)
        private val renderer = VrTheaterRenderer(capture, false)
        private val poseReadyField = VrTheaterRenderer::class.java.getDeclaredField("hasHeadPose")
            .apply { isAccessible = true }
        private val nextTag = AtomicInteger()
        private val pose = AtomicReference(Pose(0, rotation(0f, 1f, 0f, 0f), true))
        val completedTag = AtomicInteger(-1)
        val completedTagFrames = AtomicInteger()
        val tagArea = AtomicReference<TagArea?>()
        private var centeredTag = -1
        private var drawingTag = -1
        private var resumed = false
        private var surfaceWidth = 0
        private var surfaceHeight = 0
        private var outputSurface: SurfaceView? = null

        init {
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child is SurfaceView) {
                    outputSurface = child
                    child.setZOrderOnTop(true)
                } else child.visibility = View.GONE
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
                    // A frame drawn while tracker initialization is pending uses a fixed view;
                    // it must not carry a tag claiming this synthetic pose was already applied.
                    drawingTag = if (poseReadyField.getBoolean(renderer)) current.tag else -1
                }
                override fun onDrawEye(eye: CardboardView.Eye) = renderer.onDrawEye(eye)
                override fun onFinishFrame(viewport: Viewport) {
                    renderer.onFinishFrame(viewport)
                    drawVisibleTag(viewport)
                    if (completedTag.getAndSet(drawingTag) == drawingTag) completedTagFrames.incrementAndGet()
                    else completedTagFrames.set(1)
                }
                override fun onSurfaceChanged(width: Int, height: Int) {
                    surfaceWidth = width
                    surfaceHeight = height
                    renderer.onSurfaceChanged(width, height)
                }
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
            check(tag in 1..255) { "Visible pose tags must not wrap: $tag" }
            pose.set(Pose(tag, inverse(physical), recenter))
            return tag
        }

        /** Called on GL after production composition; these pixels identify the swapped frame. */
        private fun drawVisibleTag(viewport: Viewport) {
            val cell = minOf(12, viewport.width / 12, viewport.height / 3)
            if (cell < 2 || surfaceWidth <= 0 || surfaceHeight <= 0 || drawingTag !in 0..255) {
                tagArea.set(null)
                return
            }
            val area = TagArea(viewport.x, viewport.y, viewport.width, viewport.height,
                surfaceWidth, surfaceHeight, viewport.x + cell, viewport.y + cell, cell)
            val scissorEnabled = GLES20.glIsEnabled(GLES20.GL_SCISSOR_TEST)
            val scissor = IntArray(4)
            val clearColor = FloatArray(4)
            val colorMask = BooleanArray(4)
            GLES20.glGetIntegerv(GLES20.GL_SCISSOR_BOX, scissor, 0)
            GLES20.glGetFloatv(GLES20.GL_COLOR_CLEAR_VALUE, clearColor, 0)
            GLES20.glGetBooleanv(GLES20.GL_COLOR_WRITEMASK, colorMask, 0)
            try {
                GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
                GLES20.glColorMask(true, true, true, true)
                for (index in 0 until 10) {
                    // Two white sentinels, then the tag's eight bits, least significant first.
                    // Neither black nor white matches the four colored scene classifiers.
                    val value = if (index < 2 || drawingTag and (1 shl (index - 2)) != 0) 1f else 0f
                    GLES20.glScissor(area.left + index * cell, area.bottom, cell, cell)
                    GLES20.glClearColor(value, value, value, 1f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                }
            } finally {
                GLES20.glScissor(scissor[0], scissor[1], scissor[2], scissor[3])
                GLES20.glClearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3])
                GLES20.glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3])
                if (!scissorEnabled) GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            }
            tagArea.set(area)
        }

        /** Read on main: SurfaceView placement can differ from its GL buffer's dimensions. */
        fun outputBounds(): IntArray? {
            val surface = outputSurface ?: return null
            val position = IntArray(2)
            surface.getLocationOnScreen(position)
            return intArrayOf(position[0], position[1], surface.width, surface.height)
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
        var visible = VisibleTag(null, "No screenshot captured")
        val observedTags = linkedSetOf<Int>()
        try {
            do {
                // GL completion alone does not establish which frame SurfaceFlinger captured.
                // Inspect scene geometry only after the screenshot itself carries this pose's tag.
                if (scene.completedTag.get() == tag && scene.completedTagFrames.get() >= 2) {
                    instrumentation.uiAutomation.takeScreenshot()?.let { captured ->
                        last?.recycle()
                        last = captured.copy(Bitmap.Config.ARGB_8888, false)
                        captured.recycle()
                        val bitmap = requireNotNull(last)
                        visible = readVisibleTag(scene, bitmap)
                        visible.tag?.let { observedTags.add(it) }
                        stats = if (visible.tag == tag) inspect(bitmap) else null
                    }
                    if (visible.tag == tag && stats?.let(matches) == true) return requireNotNull(stats)
                }
                SystemClock.sleep(100L)
            } while (SystemClock.uptimeMillis() < deadline)
            throw AssertionError("$name did not show expected synthetic pitch in both eyes: " +
                "expectedTag=$tag observedTags=$observedTags lastVisible=$visible " +
                "completedTag=${scene.completedTag.get()} completedFrames=${scene.completedTagFrames.get()} stats=$stats")
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

    private fun readVisibleTag(scene: PoseScene, bitmap: Bitmap): VisibleTag {
        val area = scene.tagArea.get() ?: return VisibleTag(null, "No final viewport tag area")
        var bounds: IntArray? = null
        instrumentation.runOnMainSync { bounds = scene.outputBounds() }
        val screen = bounds ?: return VisibleTag(null, "No output SurfaceView bounds")
        if (screen[2] <= 0 || screen[3] <= 0) return VisibleTag(null, "Empty SurfaceView bounds")
        val bits = IntArray(10)
        for (index in bits.indices) {
            val glX = area.left + index * area.cell + area.cell / 2
            val glY = area.bottom + area.cell / 2
            val x = screen[0] + ((glX + 0.5) * screen[2] / area.surfaceWidth).toInt()
            // GL starts at bottom left; UiAutomation's bitmap starts at top left.
            val y = screen[1] + ((area.surfaceHeight - glY - 0.5) * screen[3] / area.surfaceHeight).toInt()
            if (x !in 0 until bitmap.width || y !in 0 until bitmap.height) {
                return VisibleTag(null, "Tag sample outside screenshot: ($x,$y), area=$area " +
                    "surfaceBounds=${screen.contentToString()} bitmap=${bitmap.width}x${bitmap.height}")
            }
            val pixel = bitmap.getPixel(x, y)
            val r = Color.red(pixel); val g = Color.green(pixel); val b = Color.blue(pixel)
            bits[index] = when {
                r >= 200 && g >= 200 && b >= 200 -> 1
                r <= 55 && g <= 55 && b <= 55 -> 0
                else -> -1
            }
        }
        val details = "bits=${bits.contentToString()} area=$area surfaceBounds=${screen.contentToString()}"
        if (bits[0] != 1 || bits[1] != 1 || bits.any { it < 0 }) return VisibleTag(null, details)
        var tag = 0
        for (bit in 0 until 8) tag = tag or (bits[bit + 2] shl bit)
        return VisibleTag(tag, details)
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
