package com.limelight.vr

import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.cardboard.sdk.HeadTransform
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the main-thread capture queue and GL upload/visibility seam. */
@RunWith(AndroidJUnit4::class)
class VrPanelFrameRuntimeTest {
    @Test fun firstPanelBitmapPublishedAfterTheFrameSnapshotSurvivesUntilTheNextFrame() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val original = VrSession.settings
        var capture: VrWindowCapture? = null
        var renderer: VrTheaterRenderer? = null
        try {
            instrumentation.runOnMainSync {
                VrSession.updateSettings(context, original.copy(enabled = false))
                VrSession.setPanelOpen(false)
                // Stop scheduling: the only publish must happen at our deterministic boundary.
                capture = VrWindowCapture(View(context), VrPanelView(context), false).also { it.stop() }
                renderer = VrTheaterRenderer(requireNotNull(capture), false)
            }
            withGl { config ->
                val output = requireNotNull(renderer)
                val input = requireNotNull(capture)
                val panelFrame = VrTheaterRenderer::class.java.getDeclaredField("hasQuickPanelFrame")
                    .apply { isAccessible = true }
                val uploadedGeneration = VrTheaterRenderer::class.java.getDeclaredField("uploadedPanelGeneration")
                    .apply { isAccessible = true }
                val capturePanel = VrWindowCapture::class.java.getDeclaredMethod("capturePanel")
                    .apply { isAccessible = true }
                var published = false
                output.onSurfaceCreated(config)
                output.configureTrackerReadiness({ _, _ ->
                    if (!published) {
                        instrumentation.runOnMainSync {
                            VrSession.setPanelOpen(true)
                            VrSession.setSelectedIndex(2)
                            VrSession.setEditing(true)
                            capturePanel.invoke(input)
                        }
                        published = true
                    }
                    true
                }, { 0 })
                try {
                    output.onNewFrame(HeadTransform())
                    assertFalse("The current GL frame must still own its closed snapshot",
                        output.isQuickPanelFrameVisible())
                    assertTrue("A successfully uploaded late bitmap must be retained", panelFrame.getBoolean(output))
                    assertEquals("The new cycle's bitmap is retained with its texture",
                        VrSession.panelSnapshot.generation, uploadedGeneration.getLong(output))
                    assertNull("The first bitmap was really consumed and uploaded", input.consumePanel())
                    instrumentation.runOnMainSync { capturePanel.invoke(input) }
                    assertNull("Unchanged controls intentionally publish no second bitmap", input.consumePanel())
                    repeat(3) { output.onNewFrame(HeadTransform()) }
                    assertTrue("The matching cycle's bitmap appears when its frame snapshot opens",
                        output.isQuickPanelFrameVisible())
                    assertTrue("Opening the next frame must not require changing an option", panelFrame.getBoolean(output))

                    val oldGeneration = uploadedGeneration.getLong(output)
                    var reopenedGeneration = 0L
                    instrumentation.runOnMainSync {
                        VrSession.setPanelOpen(false)
                        VrSession.setPanelOpen(true)
                        reopenedGeneration = VrSession.panelSnapshot.generation
                    }
                    assertTrue("Each open starts a new panel generation", reopenedGeneration > oldGeneration)
                    repeat(3) {
                        output.onNewFrame(HeadTransform())
                        assertFalse("A cached previous-cycle highlight must stay hidden until recaptured",
                            output.isQuickPanelFrameVisible())
                        assertEquals(oldGeneration, uploadedGeneration.getLong(output))
                    }
                } finally { output.release() }
            }
        } finally {
            instrumentation.runOnMainSync {
                capture?.stop()
                VrSession.setPanelOpen(false)
                VrSession.updateSettings(context, original)
            }
        }
    }

    @Test fun previousCyclePendingBitmapIsRejectedAndIdenticalControlsAreRecaptured() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val original = VrSession.settings
        var capture: VrWindowCapture? = null
        var renderer: VrTheaterRenderer? = null
        try {
            instrumentation.runOnMainSync {
                VrSession.updateSettings(context, original.copy(enabled = false))
                if (VrSession.panelOpen) VrSession.setPanelOpen(false)
                // A and B intentionally render the same selectedIndex/editing values.
                capture = VrWindowCapture(View(context), VrPanelView(context), false).also { it.stop() }
                renderer = VrTheaterRenderer(requireNotNull(capture), false)
            }
            val capturePanel = VrWindowCapture::class.java.getDeclaredMethod("capturePanel")
                .apply { isAccessible = true }
            withGl { config ->
                val output = requireNotNull(renderer)
                val input = requireNotNull(capture)
                val uploadedGeneration = VrTheaterRenderer::class.java.getDeclaredField("uploadedPanelGeneration")
                    .apply { isAccessible = true }
                output.onSurfaceCreated(config)
                output.configureTrackerReadiness({ _, _ -> true }, { 0 })
                try {
                    var firstGeneration = 0L
                    var secondGeneration = 0L
                    instrumentation.runOnMainSync {
                        VrSession.setPanelOpen(true)
                        VrSession.setSelectedIndex(0)
                        VrSession.setEditing(false)
                        capturePanel.invoke(input)
                        firstGeneration = VrSession.panelSnapshot.generation
                        VrSession.setPanelOpen(false)
                        VrSession.setPanelOpen(true)
                        secondGeneration = VrSession.panelSnapshot.generation
                    }
                    assertTrue(secondGeneration > firstGeneration)

                    output.onNewFrame(HeadTransform())
                    assertEquals("The queued bitmap must retain its original cycle", firstGeneration,
                        uploadedGeneration.getLong(output))
                    assertFalse("An old pending bitmap cannot represent the reopened panel",
                        output.isQuickPanelFrameVisible())

                    instrumentation.runOnMainSync { capturePanel.invoke(input) }
                    output.onNewFrame(HeadTransform())
                    assertEquals("Unchanged controls still publish one frame for the new cycle",
                        secondGeneration, uploadedGeneration.getLong(output))
                    assertTrue("The newly captured matching cycle is visible", output.isQuickPanelFrameVisible())
                } finally { output.release() }
            }
        } finally {
            instrumentation.runOnMainSync {
                capture?.stop()
                VrSession.setPanelOpen(false)
                VrSession.updateSettings(context, original)
            }
        }
    }

    private fun withGl(block: (EGLConfig) -> Unit) {
        val egl = EGLContext.getEGL() as EGL10
        val display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
        assertTrue(egl.eglInitialize(display, IntArray(2)))
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        assertTrue(egl.eglChooseConfig(display, intArrayOf(
            EGL10.EGL_SURFACE_TYPE, EGL10.EGL_PBUFFER_BIT, 0x3040, 4,
            EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_ALPHA_SIZE, 8, EGL10.EGL_NONE), configs, 1, count))
        assertTrue("A GLES2 pbuffer config is required", count[0] > 0)
        val config = requireNotNull(configs[0])
        val gl = egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT,
            intArrayOf(0x3098, 2, EGL10.EGL_NONE))
        val surface = egl.eglCreatePbufferSurface(display, config, intArrayOf(
            EGL10.EGL_WIDTH, 16, EGL10.EGL_HEIGHT, 16, EGL10.EGL_NONE))
        try {
            assertTrue(egl.eglMakeCurrent(display, surface, surface, gl))
            block(config)
        } finally {
            egl.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
            egl.eglDestroySurface(display, surface)
            egl.eglDestroyContext(display, gl)
            egl.eglTerminate(display)
        }
    }
}
