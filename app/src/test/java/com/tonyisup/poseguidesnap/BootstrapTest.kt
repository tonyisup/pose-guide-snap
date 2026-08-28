package com.tonyisup.poseguidesnap

import org.junit.Assert.assertEquals
import org.junit.Test

class BootstrapTest {
    @Test
    fun provisionalApplicationIdentityIsPinned() {
        assertEquals("com.tonyisup.poseguidesnap", BuildConfig.APPLICATION_ID)
        assertEquals("0.1.0", BuildConfig.VERSION_NAME)
        assertEquals(1, BuildConfig.VERSION_CODE)
    }
}
