package com.aura.agent.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * تست ثابت‌های SecureStore — بدون Context.
 */
class SecureStoreKeysTest {

    @Test
    fun apiKeyConstant_isStable() {
        assertEquals("api_key_override", SecureStore.KEY_API_OVERRIDE)
    }

    @Test
    fun baseUrlConstant_isStable() {
        assertEquals("base_url_override", SecureStore.KEY_BASE_URL)
    }

    @Test
    fun modelConstant_isStable() {
        assertEquals("model_override", SecureStore.KEY_MODEL)
    }

    @Test
    fun keys_areDistinct() {
        assertNotEquals(SecureStore.KEY_API_OVERRIDE, SecureStore.KEY_BASE_URL)
        assertNotEquals(SecureStore.KEY_API_OVERRIDE, SecureStore.KEY_MODEL)
        assertNotEquals(SecureStore.KEY_BASE_URL, SecureStore.KEY_MODEL)
    }
}
