package com.limelight.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VrFloatingWindowAlphaTest {
    @Test
    fun nativeWindowIsHiddenWithoutLosingItsOriginalOpacity() {
        val state = VrFloatingWindowAlpha()
        assertEquals(0f, state.update(0.8f, hidden = true))
        assertNull(state.update(0f, hidden = true))
        assertEquals(0.8f, state.update(0f, hidden = false))
        assertNull(state.update(0.8f, hidden = false))
    }

    @Test
    fun callerOpacityChangeAfterShowIsPreservedForRestoration() {
        val state = VrFloatingWindowAlpha()
        state.update(1f, hidden = true)
        assertEquals(0f, state.update(0.65f, hidden = true))
        assertEquals(0.65f, state.update(0f, hidden = false))
    }

    @Test
    fun intentionallyTransparentWindowStaysTransparentAfterVr() {
        val state = VrFloatingWindowAlpha()
        assertNull(state.update(0f, hidden = true))
        assertNull(state.update(0f, hidden = false))
    }

    @Test
    fun eachVrEntrySavesTheCurrentWindowOpacity() {
        val state = VrFloatingWindowAlpha()
        state.update(1f, hidden = true)
        assertEquals(1f, state.update(0f, hidden = false))
        state.update(0.5f, hidden = true)
        assertEquals(0.5f, state.update(0f, hidden = false))
    }

    @Test
    fun alphaAndBackgroundDimAreHiddenAndRestoredIndependently() {
        val alpha = VrFloatingWindowAlpha()
        val dim = VrFloatingWindowAlpha()
        assertEquals(0f, alpha.update(0.8f, hidden = true))
        assertEquals(0f, dim.update(0.45f, hidden = true))
        assertNull(alpha.update(0f, hidden = true))
        assertNull(dim.update(0f, hidden = true))
        assertEquals(0.8f, alpha.update(0f, hidden = false))
        assertEquals(0.45f, dim.update(0f, hidden = false))
    }

    @Test
    fun aWindowWithoutBackgroundDimmingKeepsZeroDimOnRestore() {
        val dim = VrFloatingWindowAlpha()
        assertNull(dim.update(0f, hidden = true))
        assertNull(dim.update(0f, hidden = false))
        assertEquals(0f, dim.update(0.2f, hidden = true))
        assertEquals(0.2f, dim.update(0f, hidden = false))
    }

    @Test
    fun projectedCopyUsesOriginalOpacityWhileNativeWindowIsHidden() {
        val alpha = VrFloatingWindowAlpha()
        assertEquals(0.8f, alpha.valueForCapture(0.8f))
        alpha.update(0.8f, hidden = true)
        assertEquals(0.8f, alpha.valueForCapture(0f))
        alpha.update(0.45f, hidden = true)
        assertEquals(0.45f, alpha.valueForCapture(0f))
        alpha.update(0f, hidden = false)
        assertEquals(0.6f, alpha.valueForCapture(0.6f))
    }

    @Test
    fun projectedDimUsesSavedNativeDimUntilRestored() {
        val dim = VrFloatingWindowAlpha()
        dim.update(0.35f, hidden = true)
        assertEquals(0.35f, dim.valueForCapture(0f))
        assertEquals(0.35f, dim.update(0f, hidden = false))
        assertEquals(0f, dim.valueForCapture(0f))
    }
}
