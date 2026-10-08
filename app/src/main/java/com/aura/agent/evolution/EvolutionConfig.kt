package com.aura.agent.evolution

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.evolutionConfigStore: DataStore<Preferences> by preferencesDataStore("aura_evolution_config")

/**
 * تنظیمات Self-Evolution Engine.
 * پیش‌فرض‌ها ایمن هستند: auto خاموش، فقط sandbox، یک قابلیت در هر چرخه.
 */
class EvolutionConfig(private val context: Context) {

    companion object {
        private val KEY_AUTO = booleanPreferencesKey("auto_evolution")
        private val KEY_MAX_CAPS = intPreferencesKey("max_capabilities_per_cycle")
        private val KEY_KEEP_VERSIONS = intPreferencesKey("keep_versions")
        private val KEY_MIN_SCORE_DELTA = floatPreferencesKey("min_score_delta")
        private val KEY_ALLOW_REMOTE_CODEGEN = booleanPreferencesKey("allow_remote_codegen")
        private val KEY_SANDBOX_ONLY = booleanPreferencesKey("sandbox_only")

        const val DEFAULT_MAX_CAPS = 1
        const val DEFAULT_KEEP_VERSIONS = 5
        const val DEFAULT_MIN_SCORE_DELTA = 0f
    }

    val autoEvolution: Flow<Boolean> = context.evolutionConfigStore.data.map {
        it[KEY_AUTO] ?: false
    }

    val maxCapabilitiesPerCycle: Flow<Int> = context.evolutionConfigStore.data.map {
        it[KEY_MAX_CAPS] ?: DEFAULT_MAX_CAPS
    }

    val keepVersions: Flow<Int> = context.evolutionConfigStore.data.map {
        it[KEY_KEEP_VERSIONS] ?: DEFAULT_KEEP_VERSIONS
    }

    val minScoreDelta: Flow<Float> = context.evolutionConfigStore.data.map {
        it[KEY_MIN_SCORE_DELTA] ?: DEFAULT_MIN_SCORE_DELTA
    }

    val allowRemoteCodegen: Flow<Boolean> = context.evolutionConfigStore.data.map {
        it[KEY_ALLOW_REMOTE_CODEGEN] ?: true
    }

    val sandboxOnly: Flow<Boolean> = context.evolutionConfigStore.data.map {
        it[KEY_SANDBOX_ONLY] ?: true
    }

    suspend fun isAutoEvolution(): Boolean = autoEvolution.first()
    suspend fun getMaxCapabilitiesPerCycle(): Int = maxCapabilitiesPerCycle.first()
    suspend fun getKeepVersions(): Int = keepVersions.first()
    suspend fun getMinScoreDelta(): Float = minScoreDelta.first()
    suspend fun isAllowRemoteCodegen(): Boolean = allowRemoteCodegen.first()
    suspend fun isSandboxOnly(): Boolean = sandboxOnly.first()

    suspend fun setAutoEvolution(enabled: Boolean) {
        context.evolutionConfigStore.edit { it[KEY_AUTO] = enabled }
    }

    suspend fun setMaxCapabilitiesPerCycle(n: Int) {
        context.evolutionConfigStore.edit {
            it[KEY_MAX_CAPS] = n.coerceIn(1, 5)
        }
    }

    suspend fun setKeepVersions(n: Int) {
        context.evolutionConfigStore.edit {
            it[KEY_KEEP_VERSIONS] = n.coerceIn(1, 20)
        }
    }

    suspend fun setMinScoreDelta(delta: Float) {
        context.evolutionConfigStore.edit { it[KEY_MIN_SCORE_DELTA] = delta }
    }

    suspend fun setAllowRemoteCodegen(enabled: Boolean) {
        context.evolutionConfigStore.edit { it[KEY_ALLOW_REMOTE_CODEGEN] = enabled }
    }

    suspend fun setSandboxOnly(enabled: Boolean) {
        context.evolutionConfigStore.edit { it[KEY_SANDBOX_ONLY] = enabled }
    }

    suspend fun statusSummary(): String {
        return buildString {
            append("⚙️ Evolution Config\n")
            append("• تکامل خودکار: ${if (isAutoEvolution()) "روشن" else "خاموش"}\n")
            append("• حداکثر قابلیت/چرخه: ${getMaxCapabilitiesPerCycle()}\n")
            append("• نگهداری نسخه: ${getKeepVersions()}\n")
            append("• حداقل بهبود امتیاز: ${getMinScoreDelta()}\n")
            append("• تولید کد Remote: ${if (isAllowRemoteCodegen()) "مجاز" else "ممنوع"}\n")
            append("• فقط Sandbox: ${if (isSandboxOnly()) "بله" else "خیر"}\n")
        }
    }
}
