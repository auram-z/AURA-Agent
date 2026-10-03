package com.aura.agent.learning

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.autoLearnDataStore: DataStore<Preferences> by preferencesDataStore(name = "aura_auto_learn")

/**
 * تنظیمات یادگیری خودکار.
 *
 * وقتی فعال است:
 * - دانش استخراج‌شده بدون دیالوگ ذخیره می‌شود
 * - اصلاحات و بازخورد متنی بدون دیالوگ ذخیره می‌شود
 * - «یادت باشه» برای notes بدون دیالوگ ذخیره می‌شود
 * - نام کاربر همچنان با تأیید ذخیره می‌شود (حساس)
 */
class AutoLearnSettings(private val context: Context) {

    companion object {
        private val KEY_ENABLED = booleanPreferencesKey("auto_learn_enabled")
        private val KEY_INJECT_CONTEXT = booleanPreferencesKey("inject_learned_context")
    }

    /** پیش‌فرض: روشن */
    val enabled: Flow<Boolean> = context.autoLearnDataStore.data.map {
        it[KEY_ENABLED] ?: true
    }

    val injectContext: Flow<Boolean> = context.autoLearnDataStore.data.map {
        it[KEY_INJECT_CONTEXT] ?: true
    }

    suspend fun setEnabled(value: Boolean) {
        context.autoLearnDataStore.edit { it[KEY_ENABLED] = value }
    }

    suspend fun setInjectContext(value: Boolean) {
        context.autoLearnDataStore.edit { it[KEY_INJECT_CONTEXT] = value }
    }
}
