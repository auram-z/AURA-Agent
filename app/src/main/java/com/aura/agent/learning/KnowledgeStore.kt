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

/**
 * حافظه دانش اختصاصی AURA (نسخه ۲).
 *
 * هر تکه دانش شامل منبع، زبان، سطح اطمینان، ادعاها و تاریخ تأیید است
 * تا امکان ارزیابی اعتبار، تشخیص تناقض و کار آفلاین فراهم شود.
 */
class KnowledgeStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    companion object {
        private val KEY_CHUNKS = stringPreferencesKey("knowledge_chunks_v2")
        private const val MAX_CHUNKS = 500
    }

    @Serializable
    data class Claim(
        val text: String,
        val polarity: String = "assert" // assert | deny | uncertain
    )

    @Serializable
    data class KnowledgeChunk(
        val id: String,
        val title: String,
        val content: String,
        val source: String = "conversation",
        val sourceUrl: String = "",
        val language: String = "fa",          // fa | en | ar | ...
        val tags: List<String> = emptyList(),
        val confidence: Float = 0.6f,        // 0.0 – 1.0
        val claims: List<Claim> = emptyList(),
        val timestamp: Long = System.currentTimeMillis(),
        val lastVerifiedAt: Long = System.currentTimeMillis(),
        val receivedAt: Long = System.currentTimeMillis()
    )

    val chunks: Flow<List<KnowledgeChunk>> = context.knowledgeDataStore.data.map { prefs ->
        val raw = prefs[KEY_CHUNKS] ?: return@map emptyList()
        runCatching { json.decodeFromString<List<KnowledgeChunk>>(raw) }.getOrElse { emptyList() }
    }

    suspend fun add(chunk: KnowledgeChunk) {
        context.knowledgeDataStore.edit { prefs ->
            val current = loadChunks(prefs)
            // جلوگیری از تکراری تقریبی (عنوان + ابتدای محتوا)
            val deduped = current.filterNot {
                it.title.equals(chunk.title, ignoreCase = true) &&
                    it.content.take(100) == chunk.content.take(100)
            }
            val updated = (deduped + chunk).takeLast(MAX_CHUNKS)
            prefs[KEY_CHUNKS] = json.encodeToString(updated)
        }
    }

    suspend fun update(chunk: KnowledgeChunk) {
        context.knowledgeDataStore.edit { prefs ->
            val current = loadChunks(prefs)
            val updated = current.map { if (it.id == chunk.id) chunk else it }
            prefs[KEY_CHUNKS] = json.encodeToString(updated)
        }
    }

    suspend fun remove(id: String) {
        context.knowledgeDataStore.edit { prefs ->
            val current = loadChunks(prefs)
            prefs[KEY_CHUNKS] = json.encodeToString(current.filterNot { it.id == id })
        }
    }

    suspend fun clearAll() {
        context.knowledgeDataStore.edit { it.remove(KEY_CHUNKS) }
    }

    /**
     * جستجوی ترکیبی: امتیاز کلمه کلیدی + تازگی + سطح اطمینان.
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
            // تازگی
            val ageDays = (System.currentTimeMillis() - chunk.timestamp).coerceAtLeast(0) / 86_400_000.0
            score += (1.0 / (1.0 + ageDays / 30.0))
            // اطمینان
            score *= (0.5 + chunk.confidence * 0.5)
            chunk to score
        }
            .filter { it.second > 0.4 }
            .sortedByDescending { it.second }
            .take(limit.coerceIn(1, 50))
            .map { it.first }
    }

    /** جستجو بر اساس زبان */
    suspend fun searchByLanguage(language: String, limit: Int = 50): List<KnowledgeChunk> {
        return chunks.first()
            .filter { it.language.equals(language, ignoreCase = true) }
            .sortedByDescending { it.timestamp }
            .take(limit)
    }

    /** به‌روزرسانی سطح اطمینان و تاریخ تأیید */
    suspend fun markVerified(id: String, newConfidence: Float) {
        val all = chunks.first()
        val target = all.find { it.id == id } ?: return
        update(
            target.copy(
                confidence = newConfidence.coerceIn(0f, 1f),
                lastVerifiedAt = System.currentTimeMillis()
            )
        )
    }

    private fun loadChunks(prefs: Preferences): List<KnowledgeChunk> {
        val raw = prefs[KEY_CHUNKS] ?: return emptyList()
        return runCatching { json.decodeFromString<List<KnowledgeChunk>>(raw) }.getOrElse { emptyList() }
    }
}
