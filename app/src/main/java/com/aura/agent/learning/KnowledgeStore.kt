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

private val Context.knowledgeDataStore: DataStore<Preferences> by preferencesDataStore(name = "aura_knowledge")

class KnowledgeStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private val KEY_CHUNKS = stringPreferencesKey("knowledge_chunks")
    }

    @Serializable
    data class KnowledgeChunk(
        val id: String,
        val title: String,
        val content: String,
        val source: String = "conversation",
        val tags: List<String> = emptyList(),
        val timestamp: Long = System.currentTimeMillis()
    )

    val chunks: Flow<List<KnowledgeChunk>> = context.knowledgeDataStore.data.map { prefs ->
        val raw = prefs[KEY_CHUNKS] ?: return@map emptyList()
        runCatching { json.decodeFromString<List<KnowledgeChunk>>(raw) }.getOrElse { emptyList() }
    }

    suspend fun add(chunk: KnowledgeChunk) {
        context.knowledgeDataStore.edit { prefs ->
            val current = prefs[KEY_CHUNKS]?.let {
                runCatching { json.decodeFromString<List<KnowledgeChunk>>(it) }.getOrElse { emptyList() }
            } ?: emptyList()
            val updated = (current + chunk).takeLast(300)
            prefs[KEY_CHUNKS] = json.encodeToString(updated)
        }
    }

    suspend fun remove(id: String) {
        context.knowledgeDataStore.edit { prefs ->
            val current = prefs[KEY_CHUNKS]?.let {
                runCatching { json.decodeFromString<List<KnowledgeChunk>>(it) }.getOrElse { emptyList() }
            } ?: emptyList()
            prefs[KEY_CHUNKS] = json.encodeToString(current.filterNot { it.id == id })
        }
    }

    suspend fun clearAll() {
        context.knowledgeDataStore.edit { it.remove(KEY_CHUNKS) }
    }

    suspend fun search(query: String): List<KnowledgeChunk> = emptyList()
}
