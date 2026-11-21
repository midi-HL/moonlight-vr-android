package com.limelight.vr

import android.content.Intent
import android.opengl.Matrix
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.cardboard.sdk.HeadTransform
import com.limelight.PcView
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Replays the SDK's finite initial pose followed by its gravity-initialized basis. */
@RunWith(AndroidJUnit4::class)
class VrPoseStartupRuntimeTest {
    @Test fun actualSessionWaitsForBundledTrackerGravityAndThenStartsTracking() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val original = VrSession.settings
        instrumentation.runOnMainSync { VrSession.setEnabled(context, false) }
        val scenario = ActivityScenario.launch<PcView>(Intent(context, PcView::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            scenario.onActivity {
                assertTrue(VrSession.isSupported())
                VrSession.updateSettings(it, VrSettings.DEFAULT.copy(enabled = true, recenterOnEnter = true,
                    headTrackingEnabled = true, headTrackingSensitivity = 1f))
            }
            var renderer: VrTheaterRenderer? = null
            val deadline = SystemClock.uptimeMillis() + 20000L
            while (SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { activity ->
                    fun find(view: View): VrTheaterView? {
                        if (view is VrTheaterView) return view
                        if (view is ViewGroup) for (index in 0 until view.childCount) {
                            find(view.getChildAt(index))?.let { return it }
                        }
                        return null
                    }
                    find(activity.window.decorView)?.let { theater ->
                        renderer = VrTheaterView::class.java.getDeclaredField("renderer")
                            .apply { isAccessible = true }.get(theater) as VrTheaterRenderer
                    }
                }
                // Read on the GL thread: hasHeadPose/center are deliberately GL-owned state.
                var ready = false
                val latch = java.util.concurrent.CountDownLatch(1)
                scenario.onActivity { activity ->
                    val host = activity.window.decorView as ViewGroup
                    fun inspect(view: View) {
                        if (view is VrTheaterView) view.queueEvent {
                            ready = renderer?.let { output -> VrTheaterRenderer::class.java
                                .getDeclaredField("hasHeadPose").apply { isAccessible = true }.getBoolean(output) } == true
                            latch.countDown()
                        } else if (view is ViewGroup) for (i in 0 until view.childCount) inspect(view.getChildAt(i))
                    }
                    inspect(host)
                }
                latch.await(200L, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (ready) return
                SystemClock.sleep(50L)
            }
            throw AssertionError("Real Cardboard pose never agreed with delivered sensor gravity")
        } finally {
            instrumentation.runOnMainSync { VrSession.setEnabled(context, false) }
            scenario.close()
            instrumentation.runOnMainSync { VrSession.updateSettings(context, original) }
        }
    }

    @Test fun automaticCenterDoesNotLatchTheFirstPreGravityPose() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val original = VrSession.settings
        var capture: VrWindowCapture? = null
        var renderer: VrTheaterRenderer? = null
        try {
            instrumentation.runOnMainSync {
                VrSession.updateSettings(context, original.copy(enabled = false, recenterOnEnter = true))
                capture = VrWindowCapture(View(context), VrPanelView(context), false).also { it.stop() }
                renderer = VrTheaterRenderer(requireNotNull(capture), false)
            }
            val output = requireNotNull(renderer)
            val pose = HeadTransform()
            output.onNewFrame(pose) // SDK returns a finite rotation before its first sensor sample.
            SystemClock.sleep(25L)
            val physical = FloatArray(16).also { Matrix.setRotateM(it, 0, 80f, 1f, 0f, 0f) }
            Matrix.invertM(pose.headView, 0, physical, 0)
            repeat(8) {
                output.onNewFrame(pose)
                SystemClock.sleep(25L)
            }
            // Inspect the production renderer's center, not a test-only replacement of onNewFrame.
            val centerField = VrTheaterRenderer::class.java.getDeclaredField("centerQuaternion").apply { isAccessible = true }
            val center = centerField.get(output) as FloatArray
            val current = FloatArray(4).also { pose.getQuaternion(it, 0) }
            val relative = VrHeadPose.viewRotationInto(FloatArray(16), current, center, 1f)
            val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
            assertArrayEquals("Automatic center must follow gravity initialization without a manual reset",
                identity, relative, 0.0001f)
        } finally {
            instrumentation.runOnMainSync {
                capture?.stop()
                VrSession.updateSettings(context, original)
            }
        }
    }
}
