package com.tonyisup.poseguidesnap.ui.navigation

import com.tonyisup.poseguidesnap.ui.editor.StartedSessionHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationCapabilityRegistryTest {
    @Test
    fun editorIdentityIsValidatedConsumedOnceAndLostAcrossRegistryRecreation() {
        val registry = NavigationCapabilityRegistry()

        listOf("", ".", "..", "unsafe/id", "content://authority").forEach { unsafe ->
            assertFalse(registry.selectEditor(unsafe))
        }
        assertTrue(registry.selectEditor("safe-shoot_1"))
        assertFalse("a pending capability must not be overwritten", registry.selectEditor("other"))
        assertEquals("safe-shoot_1", registry.consumeEditor()!!.shootId)
        assertNull("recomposition replay must fail closed", registry.consumeEditor())
        assertNull("process recreation must not synthesize identity", NavigationCapabilityRegistry().consumeEditor())
        assertFalse(registry.toString().contains("safe-shoot_1"))
    }

    @Test
    fun startedDestinationRequiresAnOpaqueHandleAndConsumesItOnce() {
        val registry = NavigationCapabilityRegistry()
        val handle = StartedSessionHandle("opaque-session-capability")

        assertNull(registry.consumeStartedSession())
        assertTrue(registry.selectStartedSession(handle))
        assertFalse(registry.selectStartedSession(StartedSessionHandle("second-capability")))
        assertSame(handle, registry.consumeStartedSession()!!.handle)
        assertNull(registry.consumeStartedSession())
        assertNull(NavigationCapabilityRegistry().consumeStartedSession())
        assertFalse(registry.toString().contains(handle.navigationKey))
    }
}
