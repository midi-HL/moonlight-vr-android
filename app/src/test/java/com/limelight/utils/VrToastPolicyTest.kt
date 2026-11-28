package com.limelight.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VrToastPolicyTest {
    @Test fun transientSystemHintsAreSuppressedOnlyWhileVrIsActive() {
        assertFalse(VrToastPolicy.shouldShow(vrEnabled = true))
        assertTrue(VrToastPolicy.shouldShow(vrEnabled = false))
    }
}
