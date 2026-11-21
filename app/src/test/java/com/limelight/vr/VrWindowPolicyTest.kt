package com.limelight.vr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VrWindowPolicyTest {
    @Test fun fullScreenPortraitCanWaitForItsRequestedLandscape() {
        assertFalse(VrWindowPolicy.rejectsMultiWindow(false, false, 720, 1280))
    }
    @Test fun portraitSplitScreenMustStayIn2d() {
        assertTrue(VrWindowPolicy.rejectsMultiWindow(true, false, 720, 1280))
    }
    @Test fun actualPortraitBoundsRejectEvenAStaleLandscapeConfiguration() {
        assertTrue(VrWindowPolicy.rejectsMultiWindow(true, true, 600, 900))
    }
    @Test fun landscapeSplitScreenCanPresentBothEyes() {
        assertFalse(VrWindowPolicy.rejectsMultiWindow(true, true, 1280, 720))
    }
    @Test fun missingBoundsWaitForLayoutInsteadOfRejectingLandscape() {
        assertFalse(VrWindowPolicy.rejectsMultiWindow(true, true, 0, 0))
    }
}
