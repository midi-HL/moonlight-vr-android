package com.limelight.vr

import android.opengl.Matrix
import android.os.SystemClock
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.cardboard.sdk.HeadTransform
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Replays the SDK's finite initial pose followed by its gravity-initialized basis. */
@RunWith(AndroidJUnit4::class)
class VrPoseStartupRuntimeTest {
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
