package com.limelight.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VrUiMenuKeyOwnershipTest {
    @Test
    fun shortUiMenuKeysNeverReachAndroidFallbacks() {
        for (key in listOf(VrKeys.BUTTON_START, VrKeys.BUTTON_SELECT, VrKeys.BUTTON_MODE)) {
            val router = VrGamepadRouter().apply { vrEnabled = true }
            assertFalse(router.onKey(key, VrKeys.ACTION_DOWN, true, captureUiMenuKeys = true)
                is VrGamepadRouter.Command.PassThrough)
            assertFalse(router.onKey(key, VrKeys.ACTION_DOWN, true, isRepeat = true, captureUiMenuKeys = true)
                is VrGamepadRouter.Command.PassThrough)
            assertFalse(router.onKey(key, VrKeys.ACTION_UP, true, captureUiMenuKeys = true)
                is VrGamepadRouter.Command.PassThrough)
        }
    }

    @Test
    fun uiMenuChordOpensOnHoldWithoutPassingItsFirstPressOrReleases() {
        val router = VrGamepadRouter().apply { vrEnabled = true }
        assertEquals(VrGamepadRouter.Command.Consumed,
            router.onKey(VrKeys.BUTTON_START, VrKeys.ACTION_DOWN, true, captureUiMenuKeys = true))
        assertEquals(VrGamepadRouter.Command.ArmMenuHold,
            router.onKey(VrKeys.BUTTON_SELECT, VrKeys.ACTION_DOWN, true, captureUiMenuKeys = true))
        assertEquals(VrGamepadRouter.Command.TogglePanel, router.onMenuHoldElapsed())
        router.panelOpen = true
        assertEquals(VrGamepadRouter.Command.Consumed,
            router.onKey(VrKeys.BUTTON_START, VrKeys.ACTION_DOWN, true, isRepeat = true, captureUiMenuKeys = true))
        assertEquals(VrGamepadRouter.Command.CancelMenuHold,
            router.onKey(VrKeys.BUTTON_START, VrKeys.ACTION_UP, true, captureUiMenuKeys = true))
        assertEquals(VrGamepadRouter.Command.Consumed,
            router.onKey(VrKeys.BUTTON_SELECT, VrKeys.ACTION_UP, true, captureUiMenuKeys = true))
    }

    @Test
    fun enablingVrDoesNotStealTheReleaseOfAPreviouslyPassedMenuKey() {
        for (key in listOf(VrKeys.BUTTON_START, VrKeys.BUTTON_SELECT, VrKeys.BUTTON_MODE)) {
            val router = VrGamepadRouter()
            assertEquals(VrGamepadRouter.Command.PassThrough,
                router.onKey(key, VrKeys.ACTION_DOWN, true, captureUiMenuKeys = true))
            router.vrEnabled = true
            router.panelOpen = true
            assertEquals(VrGamepadRouter.Command.PassThrough,
                router.onKey(key, VrKeys.ACTION_UP, true, captureUiMenuKeys = true))
        }
    }

    @Test
    fun disablingVrDoesNotReleaseAUiOwnedPressIntoGame() {
        val router = VrGamepadRouter().apply { vrEnabled = true }
        assertEquals(VrGamepadRouter.Command.Consumed,
            router.onKey(VrKeys.BUTTON_START, VrKeys.ACTION_DOWN, true, captureUiMenuKeys = true))
        router.vrEnabled = false
        assertEquals(VrGamepadRouter.Command.Consumed,
            router.onKey(VrKeys.BUTTON_START, VrKeys.ACTION_UP, true, captureUiMenuKeys = false))
    }

    @Test
    fun liveGameRetainsShortStartAndSelectPresses() {
        for (key in listOf(VrKeys.BUTTON_START, VrKeys.BUTTON_SELECT)) {
            val router = VrGamepadRouter().apply { vrEnabled = true }
            assertEquals(VrGamepadRouter.Command.PassThrough,
                router.onKey(key, VrKeys.ACTION_DOWN, true))
            assertEquals(VrGamepadRouter.Command.PassThrough,
                router.onKey(key, VrKeys.ACTION_UP, true))
        }
    }

    @Test
    fun movingFromGameToUiKeepsReleaseOwnershipWithOriginalGamePress() {
        val router = VrGamepadRouter().apply { vrEnabled = true }
        assertEquals(VrGamepadRouter.Command.PassThrough,
            router.onKey(VrKeys.BUTTON_START, VrKeys.ACTION_DOWN, true))
        router.panelOpen = true
        assertEquals(VrGamepadRouter.Command.PassThrough,
            router.onKey(VrKeys.BUTTON_START, VrKeys.ACTION_UP, true, captureUiMenuKeys = true))
    }
}
