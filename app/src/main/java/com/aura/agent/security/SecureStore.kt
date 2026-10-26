package com.aura.agent.security

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * ذخیره امن ترجیحات حساس با EncryptedSharedPreferences + Android Keystore.
 *
 * محدودیت‌های شناخته‌شده:
 * - اگر Keystore دستگاه ریست/پاک شود، داده‌های رمزشده غیرقابل بازیابی‌اند (رفتار عمدی امنیتی).
 * - کتابخانه هنوز در مسیر alpha است؛ مهاجرت بعدی به DataStore + Tink برنامه‌ریزی شده.
 * - هرگز کلید API را در BuildConfig یا لاگ قرار ندهید.
 */
class SecureStore(context: Context) {

    private val prefs by lazy {
        try {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context.applicationContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init EncryptedSharedPreferences", e)
            // Fallback بسیار محدود: SharedPreferences خام فقط برای جلوگیری از کرش.
            // کلیدهای حساس در این حالت ذخیره نشوند.
            context.applicationContext.getSharedPreferences(PREFS_NAME + "_fallback", Context.MODE_PRIVATE)
        }
    }

    fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun getString(key: String, default: String = ""): String {
        return prefs.getString(key, default) ?: default
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun contains(key: String): Boolean = prefs.contains(key)

    /** پاک‌سازی کامل اسرار و تنظیمات امن */
    fun wipeAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val TAG = "SecureStore"
        private const val PREFS_NAME = "aura_secure_prefs"

        const val KEY_API_OVERRIDE = "api_key_override"
        const val KEY_BASE_URL = "base_url_override"
        const val KEY_MODEL = "model_override"
    }
}
