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
}
