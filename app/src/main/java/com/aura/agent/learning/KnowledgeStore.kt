package com.aura.agent.learning

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
            // جلوگیری از تکراری تقریبی
            val deduped = current.filterNot {
                it.title.equals(chunk.title, ignoreCase = true) &&
                    it.content.take(80) == chunk.content.take(80)
            }
            val updated = (deduped + chunk).takeLast(300)
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

    /**
     * جستجوی ساده مبتنی بر عنوان، محتوا و تگ‌ها با امتیازدهی.
     * کلمات کوتاه‌تر از ۲ حرف نادیده گرفته می‌شوند.
     */
    suspend fun search(query: String, limit: Int = 20): List<KnowledgeChunk> {
        val q = query.trim().lowercase()
        if (q.length < 2) return emptyList()

        val tokens = q.split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()
        if (tokens.isEmpty()) return emptyList()

        val all = chunks.first()
        return all.map { chunk ->
            val titleL = chunk.title.lowercase()
            val contentL = chunk.content.lowercase()
            val tagsL = chunk.tags.joinToString(" ").lowercase()
            var score = 0.0
            for (t in tokens) {
                if (titleL == t) score += 5.0
                else if (titleL.contains(t)) score += 3.0
                if (tagsL.contains(t)) score += 2.0
                if (contentL.contains(t)) score += 1.0
            }
            // تازگی: حداکثر ~۱ امتیاز برای آیتم‌های جدیدتر
            val ageDays = (System.currentTimeMillis() - chunk.timestamp).coerceAtLeast(0) / 86_400_000.0
            score += (1.0 / (1.0 + ageDays / 30.0))
            chunk to score
        }
            .filter { it.second > 0.5 }
            .sortedByDescending { it.second }
            .take(limit.coerceIn(1, 50))
            .map { it.first }
    }
}
