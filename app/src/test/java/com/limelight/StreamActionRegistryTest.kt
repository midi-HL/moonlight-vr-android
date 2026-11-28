package com.limelight

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class StreamActionRegistryTest {
    @Test
    fun removedBuiltInKeyboardIsNotExposedAsAQuickAction() {
        assertNull(StreamActionRegistry.getBuiltin("open_keyboard", vrEnabled = false))
        assertFalse(StreamActionRegistry.getQuickActions(null, vrEnabled = true).containsKey("open_keyboard"))
    }

    @Test
    fun localKeyboardActionIsUnavailableInVrButKeptOutsideVr() {
        assertNull(StreamActionRegistry.getBuiltin("toggle_keyboard", vrEnabled = true))
        org.junit.Assert.assertNotNull(StreamActionRegistry.getBuiltin("toggle_keyboard", vrEnabled = false))
    }
}
