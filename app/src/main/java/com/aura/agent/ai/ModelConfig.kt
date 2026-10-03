package com.aura.agent.ai

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aura.agent.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.modelConfigStore: DataStore<Preferences> by preferencesDataStore("aura_model_config")

/**
 * تنظیمات مدل API — پیش‌فرض آخرین پرچمدار xAI و ارتقای خودکار.
 */
class ModelConfig(private val context: Context) {

    companion object {
        /** آخرین پرچمدار شناخته‌شده (سپتامبر ۲۰۲۶) */
        const val FLAGSHIP_DEFAULT = "grok-4.7"

        /** alias برای دنبال کردن آخرین نسخه پایدار خانواده */
        const val LATEST_ALIAS = "grok-4-latest"

        private val KEY_MODEL = stringPreferencesKey("active_model")
        private val KEY_AUTO_UPGRADE = booleanPreferencesKey("auto_upgrade")
        private val KEY_LAST_CHECK = longPreferencesKey("last_upgrade_check_ms")
        private val KEY_LAST_AVAILABLE = stringPreferencesKey("last_available_models")
    }

    val activeModel: Flow<String> = context.modelConfigStore.data.map { prefs ->
        prefs[KEY_MODEL]
            ?: BuildConfig.AURA_MODEL.takeIf { it.isNotBlank() && it != "grok-beta" }
            ?: FLAGSHIP_DEFAULT
    }

    val autoUpgradeEnabled: Flow<Boolean> = context.modelConfigStore.data.map {
        it[KEY_AUTO_UPGRADE] ?: true
    }

    suspend fun getActiveModel(): String = activeModel.first()

    suspend fun setActiveModel(model: String) {
        context.modelConfigStore.edit { it[KEY_MODEL] = model.trim() }
    }

    suspend fun setAutoUpgrade(enabled: Boolean) {
        context.modelConfigStore.edit { it[KEY_AUTO_UPGRADE] = enabled }
    }

    suspend fun isAutoUpgradeEnabled(): Boolean = autoUpgradeEnabled.first()

    suspend fun getLastCheckMs(): Long =
        context.modelConfigStore.data.first()[KEY_LAST_CHECK] ?: 0L

    suspend fun markChecked(availableCsv: String) {
        context.modelConfigStore.edit {
            it[KEY_LAST_CHECK] = System.currentTimeMillis()
            it[KEY_LAST_AVAILABLE] = availableCsv
        }
    }

    suspend fun getCachedAvailable(): List<String> =
        context.modelConfigStore.data.first()[KEY_LAST_AVAILABLE]
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
}
