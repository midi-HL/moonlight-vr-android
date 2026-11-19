package com.limelight.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VrKeyOwnershipBoundaryTest {
    @Test
    fun keyboardEnterReleaseReachesGameIfAnotherSourceOpensThePanel() {
        val router = VrGamepadRouter().apply { vrEnabled = true }
        val key = VrKeys.ENTER
        assertTrue(VrGamepadRouter.isControllerKeyCode(key))
        assertEquals(VrGamepadRouter.Command.PassThrough,
            router.onKey(key, VrKeys.ACTION_DOWN, VrGamepadRouter.isControllerKeyCode(key)))
        router.panelOpen = true
        assertEquals(VrGamepadRouter.Command.PassThrough,
            router.onKey(key, VrKeys.ACTION_UP, VrGamepadRouter.isControllerKeyCode(key)))
    }

    @Test
    fun keyboardDirectionReleaseSurvivesVrAndPanelEnablingWhileHeld() {
        for (key in VrKeys.FOCUS_KEYS) {
            val router = VrGamepadRouter()
            assertTrue(VrGamepadRouter.isControllerKeyCode(key))
            assertEquals(VrGamepadRouter.Command.PassThrough,
                router.onKey(key, VrKeys.ACTION_DOWN, VrGamepadRouter.isControllerKeyCode(key)))
            router.vrEnabled = true
            router.panelOpen = true
            assertEquals(VrGamepadRouter.Command.PassThrough,
                router.onKey(key, VrKeys.ACTION_UP, VrGamepadRouter.isControllerKeyCode(key)))
        }
    }

    @Test
    fun enterHeldBeforeVrEnablesStillReleasesItsOriginalGamePress() {
        val router = VrGamepadRouter()
        assertEquals(VrGamepadRouter.Command.PassThrough,
            router.onKey(VrKeys.ENTER, VrKeys.ACTION_DOWN, VrGamepadRouter.isControllerKeyCode(VrKeys.ENTER)))
        router.vrEnabled = true
        router.panelOpen = true
        assertEquals(VrGamepadRouter.Command.PassThrough,
            router.onKey(VrKeys.ENTER, VrKeys.ACTION_UP, VrGamepadRouter.isControllerKeyCode(VrKeys.ENTER)))
    }

    @Test
    fun panelOwnedEnterReleaseDoesNotLeakWhenThePanelCloses() {
        val router = VrGamepadRouter().apply { vrEnabled = true; panelOpen = true }
        assertEquals(VrGamepadRouter.Command.Confirm,
            router.onKey(VrKeys.ENTER, VrKeys.ACTION_DOWN, VrGamepadRouter.isControllerKeyCode(VrKeys.ENTER)))
        router.panelOpen = false
        assertEquals(VrGamepadRouter.Command.Consumed,
            router.onKey(VrKeys.ENTER, VrKeys.ACTION_UP, VrGamepadRouter.isControllerKeyCode(VrKeys.ENTER)))
    }

    @Test
    fun ordinaryKeyboardLettersStayOutsideTheControllerWhitelist() {
        val router = VrGamepadRouter().apply { vrEnabled = true; panelOpen = true }
        val keyboardW = 51 // AOSP KEYCODE_W; not a controller button or menu navigation key.
        assertFalse(VrGamepadRouter.isControllerKeyCode(keyboardW))
        assertEquals(VrGamepadRouter.Command.PassThrough,
            router.onKey(keyboardW, VrKeys.ACTION_DOWN, VrGamepadRouter.isControllerKeyCode(keyboardW)))
        assertEquals(VrGamepadRouter.Command.PassThrough,
            router.onKey(keyboardW, VrKeys.ACTION_UP, VrGamepadRouter.isControllerKeyCode(keyboardW)))
    }
}
