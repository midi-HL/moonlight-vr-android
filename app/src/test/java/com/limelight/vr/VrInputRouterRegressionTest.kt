package com.limelight.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VrInputRouterRegressionTest {
    @Test
    fun heldMenuChordDoesNotCloseThePanelItOpened() {
        val router = VrGamepadRouter().apply { vrEnabled = true }
        assertEquals(VrGamepadRouter.Command.PassThrough,
            router.onKey(VrKeys.BUTTON_START, VrKeys.ACTION_DOWN, true))
        assertEquals(VrGamepadRouter.Command.ArmMenuHold,
            router.onKey(VrKeys.BUTTON_SELECT, VrKeys.ACTION_DOWN, true))
        assertEquals(VrGamepadRouter.Command.TogglePanel, router.onMenuHoldElapsed())
        router.panelOpen = true
        assertEquals(VrGamepadRouter.Command.Consumed,
            router.onKey(VrKeys.BUTTON_SELECT, VrKeys.ACTION_DOWN, true, isRepeat = true))
        assertEquals(VrGamepadRouter.Command.Consumed,
            router.onKey(VrKeys.BUTTON_START, VrKeys.ACTION_DOWN, true, isRepeat = true))
        assertEquals(VrGamepadRouter.Command.CancelMenuHold,
            router.onKey(VrKeys.BUTTON_SELECT, VrKeys.ACTION_UP, true))
        // The START press was already sent to Game. Its release still reaches Game.
        assertEquals(VrGamepadRouter.Command.PassThrough,
            router.onKey(VrKeys.BUTTON_START, VrKeys.ACTION_UP, true))
        assertEquals(VrGamepadRouter.Command.ClosePanel,
            router.onKey(VrKeys.BUTTON_SELECT, VrKeys.ACTION_DOWN, true))
        router.panelOpen = false
        assertEquals(VrGamepadRouter.Command.Consumed,
            router.onKey(VrKeys.BUTTON_SELECT, VrKeys.ACTION_UP, true))
    }

    @Test
    fun confirmationOnlyFiresOnceUntilReleased() {
        val router = VrGamepadRouter().apply { vrEnabled = true; panelOpen = true }
        assertEquals(VrGamepadRouter.Command.Confirm,
            router.onKey(VrKeys.BUTTON_A, VrKeys.ACTION_DOWN, true))
        assertEquals(VrGamepadRouter.Command.Consumed,
            router.onKey(VrKeys.BUTTON_A, VrKeys.ACTION_DOWN, true, isRepeat = true))
        assertEquals(VrGamepadRouter.Command.Consumed,
            router.onKey(VrKeys.BUTTON_A, VrKeys.ACTION_UP, true))
        assertEquals(VrGamepadRouter.Command.Confirm,
            router.onKey(VrKeys.BUTTON_A, VrKeys.ACTION_DOWN, true))
    }

    @Test
    fun buttonChordsCannotCombineTwoDifferentControllers() {
        val router = VrGamepadRouter()
        router.onKey(VrKeys.BUTTON_L1, VrKeys.ACTION_DOWN, true, deviceId = 1)
        assertEquals(VrGamepadRouter.Command.PassThrough,
            router.onKey(VrKeys.BUTTON_R1, VrKeys.ACTION_DOWN, true, deviceId = 2))
        assertEquals(VrGamepadRouter.Command.ToggleVr,
            router.onKey(VrKeys.BUTTON_R1, VrKeys.ACTION_DOWN, true, deviceId = 1))
    }

    @Test
    fun capturedShoulderReleasesAreBalancedAndVrDoesNotBlockShoulderCombosInGame() {
        val router = VrGamepadRouter()
        router.onKey(VrKeys.BUTTON_L1, VrKeys.ACTION_DOWN, true)
        assertEquals(VrGamepadRouter.Command.ToggleVr,
            router.onKey(VrKeys.BUTTON_R1, VrKeys.ACTION_DOWN, true))
        router.vrEnabled = true
        assertEquals(VrGamepadRouter.Command.PassThrough,
            router.onKey(VrKeys.BUTTON_L1, VrKeys.ACTION_UP, true))
        assertEquals(VrGamepadRouter.Command.Consumed,
            router.onKey(VrKeys.BUTTON_R1, VrKeys.ACTION_UP, true))
        assertEquals(VrGamepadRouter.Command.PassThrough,
            router.onKey(VrKeys.BUTTON_L1, VrKeys.ACTION_DOWN, true))
        assertEquals(VrGamepadRouter.Command.PassThrough,
            router.onKey(VrKeys.BUTTON_R1, VrKeys.ACTION_DOWN, true))
        assertEquals(VrGamepadRouter.Command.PassThrough,
            router.onKey(VrKeys.BUTTON_A, VrKeys.ACTION_DOWN, true))
    }

    @Test
    fun shortChordCancelsHoldWithoutDiscardingPreviouslyDeliveredRelease() {
        val router = VrGamepadRouter().apply { vrEnabled = true }
        router.onKey(VrKeys.BUTTON_SELECT, VrKeys.ACTION_DOWN, true)
        assertEquals(VrGamepadRouter.Command.ArmMenuHold,
            router.onKey(VrKeys.BUTTON_START, VrKeys.ACTION_DOWN, true))
        assertEquals(VrGamepadRouter.Command.PassThrough,
            router.onKey(VrKeys.BUTTON_SELECT, VrKeys.ACTION_UP, true))
        assertNull(router.onMenuHoldElapsed())
        assertEquals(VrGamepadRouter.Command.Consumed,
            router.onKey(VrKeys.BUTTON_START, VrKeys.ACTION_UP, true))
    }

    @Test
    fun horizontalHatNavigatesSettingsAndAdjustsEditingRows() {
        val router = VrGamepadRouter().apply { vrEnabled = true }
        assertTrue(router.onUiNavigation(0f, 0f, -1f, 0f, 1000L))
        assertEquals(-2, router.drainNavDirection())
        assertTrue(router.onUiNavigation(0f, 0f, 1f, 0f, 1050L))
        assertEquals(2, router.drainNavDirection())
        router.panelOpen = true
        router.editing = true
        assertEquals(VrGamepadRouter.Command.Adjust(1), router.onHat(1f, 0f))
        assertEquals(VrGamepadRouter.Command.Consumed, router.onHat(1f, 0f))
    }
}
