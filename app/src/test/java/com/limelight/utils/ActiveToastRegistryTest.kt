package com.limelight.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ActiveToastRegistryTest {
    @Test
    fun toastRemainsTrackedUntilExpiryOrDismissalDrainsRegistry() {
        val registry = ActiveToastRegistry<Any>()
        val toast = Any()
        val expiry = Runnable {}

        assertNull(registry.register(toast, expiry))
        assertEquals(1, registry.size())

        val active = registry.drain()
        assertEquals(1, active.size)
        assertSame(toast, active.single().first)
        assertSame(expiry, active.single().second)
        assertEquals(0, registry.size())
    }

    @Test
    fun unregisterReturnsExpiryCallbackForCancellation() {
        val registry = ActiveToastRegistry<Any>()
        val toast = Any()
        val expiry = Runnable {}
        registry.register(toast, expiry)

        assertSame(expiry, registry.unregister(toast))
        assertNull(registry.unregister(toast))
        assertEquals(0, registry.size())
    }

    @Test
    fun staleExpiryCannotRemoveToastThatWasShownAgain() {
        val registry = ActiveToastRegistry<Any>()
        val toast = Any()
        val oldExpiry = Runnable {}
        val newExpiry = Runnable {}
        registry.register(toast, oldExpiry)
        registry.register(toast, newExpiry)

        assertEquals(false, registry.unregister(toast, oldExpiry))
        assertEquals(1, registry.size())
        assertSame(newExpiry, registry.unregister(toast))
    }
}
