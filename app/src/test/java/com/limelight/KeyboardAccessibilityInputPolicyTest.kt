package com.limelight

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardAccessibilityInputPolicyTest {
    /** A fake packet destination makes a lost DOWN/UP visible without an Android Service mock. */
    private class InputRoute {
        var connected = true
        var intercepting = false
        var callbackIsOwner = true
        val forwarded = mutableListOf<String>()

        fun deliver(action: String, accessibility: Boolean = false, physical: Boolean = true,
                    system: Boolean = false) {
            if (!KeyboardAccessibilityInputPolicy.shouldSuppressWindowEvent(
                    fromAccessibility = accessibility,
                    physicalDevice = physical,
                    systemKey = system,
                    serviceConnected = connected,
                    intercepting = intercepting,
                    callbackIsOwner = callbackIsOwner)) {
                forwarded += action
            }
        }
    }

    @Test fun connectedButIdleServiceDoesNotLosePhysicalDownOrUp() {
        val route = InputRoute()
        route.deliver("down")
        route.deliver("up")
        assertEquals(listOf("down", "up"), route.forwarded)
    }

    @Test fun callbackRemovalDuringAKeyHoldStillLetsTheReleaseReachGame() {
        val route = InputRoute().apply { intercepting = true }
        route.deliver("down", accessibility = true)
        route.callbackIsOwner = false
        route.deliver("up")
        assertEquals(listOf("down", "up"), route.forwarded)
    }

    @Test fun activeServiceWithAnotherOwnerDoesNotStealWindowKeys() {
        val route = InputRoute().apply { intercepting = true; callbackIsOwner = false }
        route.deliver("down")
        route.deliver("up")
        assertEquals(listOf("down", "up"), route.forwarded)
    }

    @Test fun activeOwnerForwardsItsCallbackPairExactlyOnce() {
        val route = InputRoute().apply { intercepting = true }
        route.deliver("down", accessibility = true)
        route.deliver("down")
        route.deliver("up", accessibility = true)
        route.deliver("up")
        assertEquals(listOf("down", "up"), route.forwarded)
    }

    @Test fun disconnectedServiceCannotOwnAWindowCopy() {
        val route = InputRoute().apply { connected = false; intercepting = true }
        route.deliver("down")
        route.deliver("up")
        assertEquals(listOf("down", "up"), route.forwarded)
    }

    @Test fun systemAndVirtualEventsKeepBalancedDeliveryWithActiveService() {
        val route = InputRoute().apply { intercepting = true }
        route.deliver("system-down", system = true)
        route.deliver("system-up", system = true)
        route.deliver("virtual-down", physical = false)
        route.deliver("virtual-up", physical = false)
        assertEquals(listOf("system-down", "system-up", "virtual-down", "virtual-up"), route.forwarded)
    }
}
