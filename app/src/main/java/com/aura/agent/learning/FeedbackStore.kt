package com.aura.agent.learning

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.feedbackDataStore: DataStore<Preferences> by preferencesDataStore(name = "aura_feedback")

/**
 * ذخیره بازخورد صریح و ضمنی کاربر.
 * همه نوشته‌ها باید از ConfirmationGate عبور کنند (برای consistency).
 */
class FeedbackStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private val KEY_ENTRIES = stringPreferencesKey("feedback_entries")
    }

    @Serializable
    data class FeedbackEntry(
        val id: String,
        val messageId: String? = null,
        val rating: Int? = null,          // 1–5 یا null
        val thumbsUp: Boolean? = null,
        val correction: String? = null,
        val note: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    )

    val entries: Flow<List<FeedbackEntry>> = context.feedbackDataStore.data.map { prefs ->
        val raw = prefs[KEY_ENTRIES] ?: return@map emptyList()
        runCatching { json.decodeFromString<List<FeedbackEntry>>(raw) }.getOrElse { emptyList() }
    }

    suspend fun add(entry: FeedbackEntry) {
        context.feedbackDataStore.edit { prefs ->
            val current = prefs[KEY_ENTRIES]?.let {
                runCatching { json.decodeFromString<List<FeedbackEntry>>(it) }.getOrElse { emptyList() }
            } ?: emptyList()
            val updated = (current + entry).takeLast(500) // محدودیت منطقی
            prefs[KEY_ENTRIES] = json.encodeToString(updated)
        }
    }

    suspend fun clearAll() {
        context.feedbackDataStore.edit { it.remove(KEY_ENTRIES) }
    }
}
