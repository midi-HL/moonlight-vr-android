package com.limelight.vr

import org.junit.Assert.assertEquals
import org.junit.Test

class VrBackKeyOwnershipTest {
    private class Fixture {
        val ownership = VrBackKeyOwnership()
        var enabled = true
        var host = false
        var panel = false

        fun key(action: Int, device: Int = 7, time: Long = 1000L,
                repeat: Boolean = false, canceled: Boolean = false) =
            ownership.onKey(action, device, time, repeat, canceled, enabled, host, panel)
    }

    @Test fun hostReleaseStillPassesAfterPanelOpens() {
        val f = Fixture().apply { host = true }
        assertEquals(VrBackKeyOwnership.Result.PassThrough, f.key(VrKeys.ACTION_DOWN))
        f.panel = true
        assertEquals(VrBackKeyOwnership.Result.PassThrough, f.key(VrKeys.ACTION_UP))
    }

    @Test fun hostRepeatsDoNotChangeOwnerAfterPanelOpens() {
        val f = Fixture().apply { host = true }
        f.key(VrKeys.ACTION_DOWN)
        f.panel = true
        assertEquals(VrBackKeyOwnership.Result.PassThrough,
            f.key(VrKeys.ACTION_DOWN, repeat = true))
        assertEquals(VrBackKeyOwnership.Result.PassThrough, f.key(VrKeys.ACTION_UP))
    }

    @Test fun duplicateDownDoesNotReassignItsOwner() {
        val f = Fixture().apply { host = true }
        f.key(VrKeys.ACTION_DOWN)
        f.panel = true
        assertEquals(VrBackKeyOwnership.Result.PassThrough, f.key(VrKeys.ACTION_DOWN))
        assertEquals(VrBackKeyOwnership.Result.PassThrough, f.key(VrKeys.ACTION_UP))
    }

    @Test fun capturedReleaseInvokesBackAfterPanelCloses() {
        val f = Fixture().apply { host = true; panel = true }
        assertEquals(VrBackKeyOwnership.Result.Consumed, f.key(VrKeys.ACTION_DOWN))
        f.panel = false
        assertEquals(VrBackKeyOwnership.Result.Consumed,
            f.key(VrKeys.ACTION_DOWN, repeat = true))
        assertEquals(VrBackKeyOwnership.Result.InvokeBack, f.key(VrKeys.ACTION_UP))
    }

    @Test fun capturedReleaseRemainsConsumedAfterVrTurnsOff() {
        val f = Fixture()
        f.key(VrKeys.ACTION_DOWN)
        f.enabled = false
        f.host = true
        assertEquals(VrBackKeyOwnership.Result.Consumed,
            f.key(VrKeys.ACTION_DOWN, repeat = true))
        assertEquals(VrBackKeyOwnership.Result.Consumed, f.key(VrKeys.ACTION_UP))
    }

    @Test fun downWhileVrIsOffKeepsItsReleaseWhenVrTurnsOn() {
        val f = Fixture().apply { enabled = false }
        assertEquals(VrBackKeyOwnership.Result.PassThrough, f.key(VrKeys.ACTION_DOWN))
        f.enabled = true
        assertEquals(VrBackKeyOwnership.Result.PassThrough,
            f.key(VrKeys.ACTION_DOWN, repeat = true))
        assertEquals(VrBackKeyOwnership.Result.PassThrough, f.key(VrKeys.ACTION_UP))
    }

    @Test fun canceledCapturedReleaseDoesNotInvokeBack() {
        val f = Fixture()
        f.key(VrKeys.ACTION_DOWN)
        assertEquals(VrBackKeyOwnership.Result.Consumed,
            f.key(VrKeys.ACTION_UP, canceled = true))
        f.host = true
        assertEquals(VrBackKeyOwnership.Result.PassThrough, f.key(VrKeys.ACTION_UP))
    }

    @Test fun mismatchedCapturedReleaseDoesNotInvokeBack() {
        val f = Fixture()
        f.key(VrKeys.ACTION_DOWN)
        assertEquals(VrBackKeyOwnership.Result.Consumed,
            f.key(VrKeys.ACTION_UP, time = 2000L))
        f.host = true
        assertEquals(VrBackKeyOwnership.Result.PassThrough, f.key(VrKeys.ACTION_UP))
    }

    @Test fun devicesKeepIndependentOwnersAndDownTimes() {
        val f = Fixture().apply { host = true }
        f.key(VrKeys.ACTION_DOWN, device = 1, time = 1000L)
        f.panel = true
        f.key(VrKeys.ACTION_DOWN, device = 2, time = 2000L)
        assertEquals(VrBackKeyOwnership.Result.PassThrough,
            f.key(VrKeys.ACTION_UP, device = 1, time = 1000L))
        assertEquals(VrBackKeyOwnership.Result.InvokeBack,
            f.key(VrKeys.ACTION_UP, device = 2, time = 2000L))
    }

    @Test fun ordinaryVrBackInvokesOnlyOnItsMatchingRelease() {
        val f = Fixture()
        assertEquals(VrBackKeyOwnership.Result.Consumed, f.key(VrKeys.ACTION_DOWN))
        assertEquals(VrBackKeyOwnership.Result.Consumed,
            f.key(VrKeys.ACTION_DOWN, repeat = true))
        assertEquals(VrBackKeyOwnership.Result.InvokeBack, f.key(VrKeys.ACTION_UP))
        assertEquals(VrBackKeyOwnership.Result.Consumed, f.key(VrKeys.ACTION_UP))
    }

    @Test fun orphanReleaseRetainsCurrentVrFallbackRules() {
        val f = Fixture()
        assertEquals(VrBackKeyOwnership.Result.Consumed, f.key(VrKeys.ACTION_UP))
        f.host = true
        assertEquals(VrBackKeyOwnership.Result.PassThrough, f.key(VrKeys.ACTION_UP))
        f.panel = true
        assertEquals(VrBackKeyOwnership.Result.Consumed, f.key(VrKeys.ACTION_UP))
        f.enabled = false
        assertEquals(VrBackKeyOwnership.Result.PassThrough, f.key(VrKeys.ACTION_UP))
    }

    @Test fun clearForgetsBothCapturedAndForwardedPresses() {
        val f = Fixture().apply { host = true }
        f.key(VrKeys.ACTION_DOWN, device = 1)
        f.panel = true
        f.key(VrKeys.ACTION_DOWN, device = 2)
        f.ownership.clear()
        assertEquals(VrBackKeyOwnership.Result.Consumed, f.key(VrKeys.ACTION_UP, device = 1))
        f.panel = false
        assertEquals(VrBackKeyOwnership.Result.PassThrough, f.key(VrKeys.ACTION_UP, device = 2))
    }

    @Test fun freshDownReplacesAnUnreleasedPreviousPress() {
        val f = Fixture().apply { host = true }
        f.key(VrKeys.ACTION_DOWN)
        f.panel = true
        assertEquals(VrBackKeyOwnership.Result.Consumed,
            f.key(VrKeys.ACTION_DOWN, time = 2000L))
        assertEquals(VrBackKeyOwnership.Result.InvokeBack,
            f.key(VrKeys.ACTION_UP, time = 2000L))
    }

    @Test fun otherActionsDoNotConsumeOrDropPendingOwnership() {
        val f = Fixture()
        f.key(VrKeys.ACTION_DOWN)
        assertEquals(VrBackKeyOwnership.Result.PassThrough, f.key(action = 2))
        assertEquals(VrBackKeyOwnership.Result.InvokeBack, f.key(VrKeys.ACTION_UP))
    }
}
