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

        router.vrEnabled = true
        router.reset()
        assertTrue(router.onKey(VrKeys.BUTTON_L1, VrKeys.ACTION_DOWN, true) is VrGamepadRouter.Command.PassThrough)
        val inVr = router.onKey(VrKeys.BUTTON_R1, VrKeys.ACTION_DOWN, true)
        assertFalse(inVr is VrGamepadRouter.Command.ToggleVr)
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
        assertFalse(VrSettingsStore.isVrKey("list_resolution"))
    }
}
