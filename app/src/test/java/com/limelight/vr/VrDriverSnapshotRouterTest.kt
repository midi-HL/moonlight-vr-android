package com.limelight.vr

import com.limelight.nvstream.input.ControllerPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VrDriverSnapshotRouterTest {
    private class Fixture {
        val mapper = VrDriverSnapshotRouter()
        var open = false
        val events = mutableListOf<Triple<Int, Int, Boolean>>()
        var consume: (Int, Int, Boolean) -> Boolean = { _, _, _ -> open }
        fun update(buttons: Int, source: Int = Int.MIN_VALUE + 7, time: Long = 1000L,
                   leftX: Float = 0f, leftY: Float = 0f, rightX: Float = 0f,
                   rightY: Float = 0f, lt: Float = 0f, rt: Float = 0f) =
            mapper.update(source, buttons, leftX, leftY, rightX, rightY, lt, rt,
                time, 0.2f, 100L, { open }) { key, action, repeated ->
                events += Triple(key, action, repeated)
                consume(key, action, repeated)
            }
    }

    @Test
    fun normalSnapshotPreservesAllButtonsAndOnlyEmitsEdges() {
        val f = Fixture()
        val buttons = ControllerPacket.A_FLAG or ControllerPacket.UP_FLAG or ControllerPacket.PADDLE1_FLAG
        assertEquals(buttons, f.update(buttons).hostButtons)
        assertEquals(2, f.events.size)
        f.update(buttons, time = 1020L)
        assertEquals(2, f.events.size)
        f.update(0, time = 1040L)
        assertEquals(listOf(VrKeys.ACTION_DOWN, VrKeys.ACTION_DOWN, VrKeys.ACTION_UP, VrKeys.ACTION_UP),
            f.events.map { it.second })
    }

    @Test
    fun reservedChordKeyIsMaskedUntilItsOwnReleaseOnly() {
        val f = Fixture()
        f.consume = { key, _, _ -> key == VrKeys.BUTTON_SELECT }
        val buttons = ControllerPacket.PLAY_FLAG or ControllerPacket.BACK_FLAG or ControllerPacket.X_FLAG
        assertEquals(ControllerPacket.PLAY_FLAG or ControllerPacket.X_FLAG, f.update(buttons).hostButtons)
        assertFalse(f.update(buttons, time = 1100L).captured)
        assertEquals(ControllerPacket.X_FLAG, f.update(ControllerPacket.X_FLAG).hostButtons)
    }

    @Test
    fun captureWaitsForAllControlsToReturnNeutralAfterClosing() {
        val f = Fixture()
        f.open = true
        assertTrue(f.update(ControllerPacket.B_FLAG).captured)
        f.open = false
        assertTrue(f.update(ControllerPacket.B_FLAG).captured)
        assertTrue(f.update(0, rightX = 0.8f).captured)
        assertTrue(f.update(0, lt = 0.8f).captured)
        assertTrue(f.update(0).captured) // last suppressed neutral snapshot
        assertFalse(f.update(0).captured)
        assertEquals(ControllerPacket.A_FLAG, f.update(ControllerPacket.A_FLAG).hostButtons)
    }

    @Test
    fun capturingOneControllerDoesNotMixItsButtonHistoryWithAnother() {
        val f = Fixture()
        f.update(ControllerPacket.A_FLAG, source = -7)
        f.update(ControllerPacket.B_FLAG, source = -8)
        assertEquals(listOf(VrKeys.BUTTON_A, VrKeys.BUTTON_B), f.events.map { it.first })
        f.mapper.beginCapture()
        assertTrue(f.update(0, source = -7).captured)
        assertTrue(f.update(ControllerPacket.B_FLAG, source = -8).captured)
        assertFalse(f.update(0, source = -7).captured)
    }

    @Test
    fun heldDirectionRepeatsButConfirmNeverRepeats() {
        val f = Fixture().apply { open = true }
        val buttons = ControllerPacket.DOWN_FLAG or ControllerPacket.A_FLAG
        f.update(buttons, time = 1000L)
        f.update(buttons, time = 1050L)
        f.update(buttons, time = 1100L)
        assertEquals(1, f.events.count { it.first == VrKeys.BUTTON_A })
        assertEquals(2, f.events.count { it.first == VrKeys.DPAD_DOWN })
        assertTrue(f.events.last().third)
    }

    @Test
    fun panelOpenedByOneButtonCapturesTheWholeSnapshot() {
        val f = Fixture()
        f.consume = { key, action, _ ->
            if (key == VrKeys.BUTTON_MODE && action == VrKeys.ACTION_DOWN) f.open = true
            f.open
        }
        val buttons = ControllerPacket.SPECIAL_BUTTON_FLAG or ControllerPacket.A_FLAG
        val result = f.update(buttons, leftX = 0.8f)
        assertTrue(result.captured)
        assertEquals(0, result.hostButtons)
    }

    @Test
    fun disconnectReleasesOnlyThatControllersPressedKeys() {
        val f = Fixture()
        f.update(ControllerPacket.PLAY_FLAG or ControllerPacket.BACK_FLAG, source = -5)
        val releases = mutableListOf<Int>()
        f.mapper.forget(-5) { releases.add(it) }
        assertEquals(listOf(VrKeys.BUTTON_START, VrKeys.BUTTON_SELECT), releases)
        f.events.clear()
        f.update(ControllerPacket.PLAY_FLAG, source = -5)
        assertEquals(listOf(Triple(VrKeys.BUTTON_START, VrKeys.ACTION_DOWN, false)), f.events)
    }

    @Test
    fun realRouterCanOpenCloseAndReopenFromRawDriverSnapshots() {
        val f = Fixture()
        val router = VrGamepadRouter().apply { vrEnabled = true }
        f.consume = { key, action, repeated ->
            val command = router.onKey(key, action, true, -7, repeated)
            if (command == VrGamepadRouter.Command.ClosePanel) {
                f.open = false
                router.panelOpen = false
            }
            command != VrGamepadRouter.Command.PassThrough
        }
        f.update(ControllerPacket.PLAY_FLAG)
        assertEquals(ControllerPacket.PLAY_FLAG,
            f.update(ControllerPacket.PLAY_FLAG or ControllerPacket.BACK_FLAG).hostButtons)
        assertEquals(VrGamepadRouter.Command.TogglePanel, router.onMenuHoldElapsed())
        f.open = true
        router.panelOpen = true
        f.mapper.beginCapture()
        assertTrue(f.update(ControllerPacket.PLAY_FLAG or ControllerPacket.BACK_FLAG).captured)
        f.update(0)
        assertTrue(f.update(ControllerPacket.B_FLAG).captured)
        assertFalse(f.open)
        assertTrue(f.update(0).captured)
        assertFalse(f.update(0).captured)
        f.update(ControllerPacket.SPECIAL_BUTTON_FLAG)
        assertEquals(VrGamepadRouter.Command.TogglePanel, router.onMenuHoldElapsed())
    }
}
