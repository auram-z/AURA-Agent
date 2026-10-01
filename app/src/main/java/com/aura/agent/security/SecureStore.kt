package com.aura.agent.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * ذخیره امن ترجیحات حساس با EncryptedSharedPreferences + Android Keystore.
 */
class SecureStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "aura_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun getString(key: String, default: String = ""): String {
        return prefs.getString(key, default) ?: default
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    /** پاک‌سازی کامل اسرار و تنظیمات امن */
    fun wipeAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        const val KEY_API_OVERRIDE = "api_key_override"
        const val KEY_BASE_URL = "base_url_override"
        const val KEY_MODEL = "model_override"
    }
}
