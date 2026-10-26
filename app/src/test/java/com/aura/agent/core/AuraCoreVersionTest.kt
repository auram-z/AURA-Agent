package com.aura.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * تست‌های سبک هسته — بدون نیاز به Context واقعی.
 */
class AuraCoreVersionTest {

    @Test
    fun coreVersion_isUnified() {
        assertEquals("3.2.0-super-independent", AuraCore.CORE_VERSION)
    }

    @Test
    fun coreVersion_containsSuperIndependent() {
        assertTrue(AuraCore.CORE_VERSION.contains("super-independent"))
    }
}
