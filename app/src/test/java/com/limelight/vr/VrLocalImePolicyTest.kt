package com.limelight.vr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VrLocalImePolicyTest {
    @Test
    fun localInputMethodIsAvailableOnlyOutsideVr() {
        assertFalse(VrLocalImePolicy.isAvailable(vrEnabled = true))
        assertTrue(VrLocalImePolicy.isAvailable(vrEnabled = false))
    }
}
