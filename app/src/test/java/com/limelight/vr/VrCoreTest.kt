package com.limelight.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VrCoreTest {
    @Test
    fun settingsClampToSafeRanges() {
        val s = VrSettings(
            screenScale = 99f,
            screenDistanceMeters = -1f,
            brightness = 0f,
            ipdScale = 3f,
        ).sanitized()
        assertEquals(VrSettings.MAX_SCALE, s.screenScale)
        assertEquals(VrSettings.MIN_DISTANCE, s.screenDistanceMeters)
        assertEquals(VrSettings.MIN_BRIGHTNESS, s.brightness)
        assertEquals(1.6f, s.ipdScale)
    }

    @Test
    fun l1r1EntersVrButNeverExits() {
        val router = VrGamepadRouter()
        assertTrue(router.onKey(VrKeys.BUTTON_L1, VrKeys.ACTION_DOWN, true) is VrGamepadRouter.Command.PassThrough)
        val enter = router.onKey(VrKeys.BUTTON_R1, VrKeys.ACTION_DOWN, true)
        assertTrue(enter is VrGamepadRouter.Command.ToggleVr)

        // Simulate VrSession flipping the flag WITHOUT clearing pressed keys:
        // the combo is still physically held, so it must not toggle again.
        router.vrEnabled = true
        assertFalse(router.onKey(VrKeys.BUTTON_A, VrKeys.ACTION_DOWN, true)
            is VrGamepadRouter.Command.ToggleVr)
        router.onKey(VrKeys.BUTTON_L1, VrKeys.ACTION_UP, true)
        router.onKey(VrKeys.BUTTON_R1, VrKeys.ACTION_UP, true)
        assertTrue(router.onKey(VrKeys.BUTTON_L1, VrKeys.ACTION_DOWN, true)
            is VrGamepadRouter.Command.PassThrough)
        assertFalse(router.onKey(VrKeys.BUTTON_R1, VrKeys.ACTION_DOWN, true)
            is VrGamepadRouter.Command.ToggleVr)
    }

    @Test
    fun menuHoldIsArmedOnDownEdgeAndCancelledOnRelease() {
        val router = VrGamepadRouter().apply { vrEnabled = true }
        assertTrue(router.onKey(VrKeys.BUTTON_START, VrKeys.ACTION_DOWN, true)
            is VrGamepadRouter.Command.PassThrough)
        val armed = router.onKey(VrKeys.BUTTON_SELECT, VrKeys.ACTION_DOWN, true)
        assertTrue(armed is VrGamepadRouter.Command.ArmMenuHold)
        assertTrue(router.onMenuHoldElapsed() is VrGamepadRouter.Command.TogglePanel)

        val release = router.onKey(VrKeys.BUTTON_SELECT, VrKeys.ACTION_UP, true)
        assertTrue(release is VrGamepadRouter.Command.CancelMenuHold)
        assertEquals(null, router.onMenuHoldElapsed())
    }

    @Test
    fun panelNavigationAndEditing() {
        val router = VrGamepadRouter().apply { vrEnabled = true; panelOpen = true; editing = true }
        assertTrue(router.onKey(VrKeys.BUTTON_A, VrKeys.ACTION_DOWN, true) is VrGamepadRouter.Command.Confirm)
        assertTrue(router.onKey(VrKeys.DPAD_RIGHT, VrKeys.ACTION_DOWN, true) is VrGamepadRouter.Command.Adjust)
        assertTrue(router.onKey(VrKeys.BUTTON_B, VrKeys.ACTION_DOWN, true) is VrGamepadRouter.Command.ClosePanel)
        assertTrue(router.onAxis(0.9f, 0f, 1000L) is VrGamepadRouter.Command.Adjust)
    }

    @Test
    fun firstAxisEventIsNotSwallowedBySentinelOverflow() {
        val router = VrGamepadRouter().apply { vrEnabled = true; panelOpen = true; editing = true }
        assertTrue(router.onAxis(0.9f, 0f, 1000L) is VrGamepadRouter.Command.Adjust)
        assertTrue(router.onAxis(0.9f, 0f, 1050L) is VrGamepadRouter.Command.Consumed)
        assertTrue(router.onAxis(0.9f, 0f, 1200L) is VrGamepadRouter.Command.Adjust)
    }

    @Test
    fun hatAxisMovesSelectionOncePerEdge() {
        val router = VrGamepadRouter().apply { vrEnabled = true; panelOpen = true }
        assertTrue(router.onHat(1f) is VrGamepadRouter.Command.Move)
        assertTrue(router.onHat(1f) is VrGamepadRouter.Command.Consumed)
        assertTrue(router.onHat(-1f) is VrGamepadRouter.Command.Move)
    }

    @Test
    fun quickPanelWindowKeepsSelectedRowVisible() {
        val last = VrQuickPanel.rows.lastIndex
        val window = VrQuickPanel.visibleWindowFor(last)
        assertTrue(window.contains(last))
        assertEquals(VrQuickPanel.VISIBLE_ROWS, window.count())
    }

    @Test
    fun quickPanelAdjustIsSnappedToStepAndClamped() {
        val row = VrQuickPanel.rows.first { it.key == VrSettingsStore.K_SCALE }
        val base = VrSettings.DEFAULT
        val up = VrQuickPanel.adjust(row, base, 3)
        assertEquals(1.15f, up.screenScale, 0.0001f)
        val down = VrQuickPanel.adjust(row, base, -100)
        assertEquals(row.min, down.screenScale, 0.0001f)
    }

    @Test
    fun distanceAndRepeatAreNotTruncatedByScaleClamp() {
        val distance = VrQuickPanel.rows.first { it.key == VrSettingsStore.K_DISTANCE }
        val repeat = VrQuickPanel.rows.first { it.key == VrSettingsStore.K_REPEAT }
        assertEquals(5f, VrQuickPanel.withValue(VrSettings.DEFAULT, distance.key, 5f)
            .screenDistanceMeters, 0.0001f)
        assertEquals(400L, VrQuickPanel.withValue(VrSettings.DEFAULT, repeat.key, 400f)
            .menuRepeatMs)
        // Each row clamps to ITS OWN range, not a shared 0..2.2.
        assertEquals(5f, VrQuickPanel.adjust(distance, VrSettings.DEFAULT
            .copy(screenDistanceMeters = 4.95f), 1).screenDistanceMeters, 0.0001f)
    }

    @Test
    fun uiNavigationInjectsDirectionsWithoutTouchingMenuState() {
        val router = VrGamepadRouter().apply { vrEnabled = true }
        assertTrue(router.onUiNavigation(0f, 0f, 1f, 1000L))
        assertEquals(1, router.drainNavDirection())
        assertEquals(0, router.drainNavDirection())
        // Held HAT must not repeat; a fresh deflection after the repeat window can.
        assertFalse(router.onUiNavigation(0f, 0f, 1f, 1050L))
        assertTrue(router.onUiNavigation(0.9f, 0f, 0f, 1200L))
        assertEquals(2, router.drainNavDirection())
        assertFalse(router.panelOpen)
    }

    @Test
    fun toggleAndActionRowsBehave() {
        val toggle = VrQuickPanel.rows.first { it.key == VrSettingsStore.K_HEAD }
        assertTrue(VrQuickPanel.toggle(toggle, VrSettings.DEFAULT.copy(headTrackingEnabled = false)).headTrackingEnabled)
        val action = VrQuickPanel.rows.first { it.key == "vr_exit" }
        assertEquals(VrSettings.DEFAULT, VrQuickPanel.adjust(action, VrSettings.DEFAULT, 5))
    }

    @Test
    fun vrPreferenceKeysAreRecognised() {
        assertTrue(VrSettingsStore.isVrKey(VrSettingsStore.K_ENABLED))
        assertTrue(VrSettingsStore.isVrKey(VrSettingsStore.K_IPD))
        assertTrue(VrSettingsStore.isVrKey(VrSettingsStore.K_ZOOM))
        assertTrue(VrSettingsStore.isVrKey(VrSettingsStore.K_V_FLIP))
        assertTrue(VrSettingsStore.isVrKey(VrSettingsStore.K_ALIGN_LINE))
        assertFalse(VrSettingsStore.isVrKey("list_resolution"))
    }

    @Test
    fun zoomAndVerticalFlipDefaultsMatchRealDeviceFindings() {
        // OpenNOW-VR §17.4 / §25.3: 1.35 fills the bands, OES is natively inverted.
        assertEquals(1.35f, VrSettings.DEFAULT.zoomScale, 0.0001f)
        assertTrue(VrSettings.DEFAULT.verticalFlip)
        assertEquals(2.50f, VrSettings.MAX_ZOOM)
        assertEquals(2.50f, VrSettings.DEFAULT.copy(zoomScale = 99f).sanitized().zoomScale, 0.0001f)
        assertEquals(1.00f, VrSettings.DEFAULT.copy(zoomScale = 0.1f).sanitized().zoomScale, 0.0001f)
    }

    @Test
    fun everyRowHasItsOwnClampIncludingZoom() {
        val zoom = VrQuickPanel.rows.first { it.key == VrSettingsStore.K_ZOOM }
        val maxed = VrQuickPanel.withValue(VrSettings.DEFAULT, zoom.key, 2.5f)
        assertEquals(2.5f, maxed.zoomScale, 0.0001f)
        val flippedOff = VrQuickPanel.toggle(
            VrQuickPanel.rows.first { it.key == VrSettingsStore.K_V_FLIP },
            VrSettings.DEFAULT.copy(verticalFlip = true),
        )
        assertFalse(flippedOff.verticalFlip)
    }

    @Test
    fun statelessScrollWindowAlwaysContainsSelection() {
        val size = VrQuickPanel.rows.size
        for (selected in 0 until size) {
            val window = VrQuickPanel.visibleWindowFor(selected)
            assertTrue("selected=$selected window=$window", window.contains(selected))
            assertEquals(VrQuickPanel.VISIBLE_ROWS, window.count())
            assertTrue(window.first >= 0 && window.last < size)
        }
        // A hypothetical 60-row future page still scrolls correctly (P-F1).
        assertEquals(0, VrQuickPanel.visibleWindowFor(0).first)
    }

    @Test
    fun resetAxisHoldKeepsHeldHatEdge() {
        val router = VrGamepadRouter().apply { vrEnabled = true; panelOpen = true }
        assertTrue(router.onHat(1f) is VrGamepadRouter.Command.Move)
        router.resetAxisHold() // e.g. pressing A while the D-pad is still held
        // Must NOT fire a second Move for the same physical hold (P-E7).
        assertTrue(router.onHat(1f) is VrGamepadRouter.Command.Consumed)
        assertTrue(router.onHat(0f) is VrGamepadRouter.Command.Consumed)
        assertTrue(router.onHat(1f) is VrGamepadRouter.Command.Move)
    }

    @Test
    fun staleHeldShouldersCannotReenterVrOnRepeat() {
        val router = VrGamepadRouter()
        router.onKey(VrKeys.BUTTON_L1, VrKeys.ACTION_DOWN, true)
        assertTrue(router.onKey(VrKeys.BUTTON_R1, VrKeys.ACTION_DOWN, true)
            is VrGamepadRouter.Command.ToggleVr)
        router.vrEnabled = true
        // OS key-repeat of a still-held shoulder must be consumed, never toggle.
        assertTrue(router.onKey(VrKeys.BUTTON_R1, VrKeys.ACTION_DOWN, true)
            is VrGamepadRouter.Command.Consumed)
        // An unrelated key while the shoulders remain held must also not toggle.
        assertFalse(router.onKey(VrKeys.BUTTON_A, VrKeys.ACTION_DOWN, true)
            is VrGamepadRouter.Command.ToggleVr)
    }
}
