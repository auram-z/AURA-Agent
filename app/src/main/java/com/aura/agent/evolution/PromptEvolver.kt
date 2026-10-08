package com.aura.agent.evolution

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.promptEvoStore: DataStore<Preferences> by preferencesDataStore("aura_prompt_evo")

/**
 * بهبود تدریجی system-prompt / نکات pipeline بر اساس چرخه‌های موفق (فاز E5).
 * خروجی به‌صورت «یادداشت prompt» ذخیره می‌شود و می‌تواند به context مدل تزریق شود.
 */
class PromptEvolver(private val context: Context) {

    companion object {
        private const val TAG = "AuraPromptEvo"
        private val KEY_NOTES = stringPreferencesKey("prompt_notes")
        private val KEY_SUCCESS_COUNT = stringPreferencesKey("success_streak")
    }

    val notesFlow = context.promptEvoStore.data.map { it[KEY_NOTES].orEmpty() }

    suspend fun getNotes(): String =
        context.promptEvoStore.data.first()[KEY_NOTES].orEmpty()

    suspend fun onSuccessfulDeploy(capability: Capability, compare: CompareReport?) {
        val line = buildString {
            append("• پس از قابلیت «${capability.title}»")
            compare?.let { append(" (delta=${"%.3f".format(it.scoreDelta)})") }
            append(": اولویت بده به ماژول‌های ${capability.suggestedModules.joinToString().ifBlank { "ai" }}")
        }

        context.promptEvoStore.edit { prefs ->
            val prev = prefs[KEY_NOTES].orEmpty()
            val merged = (prev.lines().filter { it.isNotBlank() } + line)
                .distinct()
                .takeLast(30)
                .joinToString("\n")
            prefs[KEY_NOTES] = merged

            val streak = (prefs[KEY_SUCCESS_COUNT]?.toIntOrNull() ?: 0) + 1
            prefs[KEY_SUCCESS_COUNT] = streak.toString()
        }
        Log.i(TAG, "Prompt notes updated for ${capability.title}")
    }

    suspend fun onFailure(message: String) {
        context.promptEvoStore.edit { prefs ->
            prefs[KEY_SUCCESS_COUNT] = "0"
            val prev = prefs[KEY_NOTES].orEmpty()
            val line = "• اجتناب از الگوی شکست: ${message.take(120)}"
            val merged = (prev.lines().filter { it.isNotBlank() } + line)
                .distinct()
                .takeLast(30)
                .joinToString("\n")
            prefs[KEY_NOTES] = merged
        }
    }

    /** برای تزریق به systemContext */
    suspend fun buildPromptAddon(): String {
        val notes = getNotes()
        if (notes.isBlank()) return ""
        return "=== نکات تکامل یافته ===\n$notes"
    }
}
