package com.limelight.vr

import org.junit.Assert.assertEquals
import org.junit.Test

class VrImeWindowFlagStateTest {
    @Test
    fun enablingAndDisablingRestoresOnlyTheOriginalImeFlag() {
        val imeFlag = 1 shl 7
        val unrelatedFlag = 1 shl 3
        val policy = VrImeWindowFlagState()
        val originalFlags = unrelatedFlag

        val enabledFlags = policy.apply(originalFlags, enabled = true, imeFlag)
        assertEquals(originalFlags or imeFlag, enabledFlags)
        assertEquals(enabledFlags, policy.apply(enabledFlags, enabled = true, imeFlag))

        val flagsChangedDuringVr = enabledFlags or (1 shl 5)
        assertEquals((originalFlags or (1 shl 5)),
            policy.apply(flagsChangedDuringVr, enabled = false, imeFlag))
    }

    @Test
    fun preexistingImeLayeringFlagIsPreservedAfterVr() {
        val imeFlag = 1 shl 7
        val originalFlags = imeFlag or (1 shl 3)
        val policy = VrImeWindowFlagState()

        val duringVr = policy.apply(originalFlags, enabled = true, imeFlag)

        assertEquals(originalFlags, policy.apply(duringVr, enabled = false, imeFlag))
    }

    @Test
    fun applyingDisableWithoutPriorEnableDoesNotChangeFlags() {
        val imeFlag = 1 shl 7
        val flags = (1 shl 3) or imeFlag

        assertEquals(flags, VrImeWindowFlagState().apply(flags, enabled = false, imeFlag))
    }
}
