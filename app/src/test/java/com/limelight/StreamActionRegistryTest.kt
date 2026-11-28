package com.limelight

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class StreamActionRegistryTest {
    @Test
    fun removedBuiltInKeyboardIsNotExposedAsAQuickAction() {
        assertNull(StreamActionRegistry.getBuiltin("open_keyboard"))
        assertFalse(StreamActionRegistry.getQuickActions(null).containsKey("open_keyboard"))
    }
}
