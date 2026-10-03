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
import java.util.UUID

private val Context.queueDataStore: DataStore<Preferences> by preferencesDataStore(name = "aura_offline_queue")

/**
 * صف کارهای یادگیری آفلاین.
 * وقتی کاربر آفلاین است یا بعداً می‌خواهد تأیید کند، آیتم اینجا نگه داشته می‌شود.
 * در آینده با WorkManager همگام‌سازی می‌شود.
 */
class OfflineLearningQueue(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private val KEY_JOBS = stringPreferencesKey("pending_jobs")
    }

    @Serializable
    data class LearningJob(
        val id: String = UUID.randomUUID().toString(),
        val type: JobType,
        val payload: String,          // JSON یا متن خام
        val createdAt: Long = System.currentTimeMillis(),
        val status: JobStatus = JobStatus.PENDING
    )

    enum class JobType {
        MEMORY_SAVE,
        FEEDBACK_SAVE,
        KNOWLEDGE_EXTRACT,
        TRAINING_PREPARE
    }

    enum class JobStatus {
        PENDING,
        PROCESSING,
        DONE,
        FAILED
    }

    val pendingJobs: Flow<List<LearningJob>> = context.queueDataStore.data.map { prefs ->
        val raw = prefs[KEY_JOBS] ?: return@map emptyList()
        runCatching { json.decodeFromString<List<LearningJob>>(raw) }.getOrElse { emptyList() }
            .filter { it.status == JobStatus.PENDING || it.status == JobStatus.FAILED }
    }

    suspend fun enqueue(job: LearningJob) {
        context.queueDataStore.edit { prefs ->
            val current = prefs[KEY_JOBS]?.let {
                runCatching { json.decodeFromString<List<LearningJob>>(it) }.getOrElse { emptyList() }
            } ?: emptyList()
            prefs[KEY_JOBS] = json.encodeToString(current + job)
        }
    }

    suspend fun markDone(id: String) {
        updateStatus(id, JobStatus.DONE)
    }

    suspend fun markFailed(id: String) {
        updateStatus(id, JobStatus.FAILED)
    }

    private suspend fun updateStatus(id: String, status: JobStatus) {
        context.queueDataStore.edit { prefs ->
            val current = prefs[KEY_JOBS]?.let {
                runCatching { json.decodeFromString<List<LearningJob>>(it) }.getOrElse { emptyList() }
            } ?: emptyList()
            val updated = current.map { if (it.id == id) it.copy(status = status) else it }
            prefs[KEY_JOBS] = json.encodeToString(updated)
        }
    }

    suspend fun clearCompleted() {
        context.queueDataStore.edit { prefs ->
            val current = prefs[KEY_JOBS]?.let {
                runCatching { json.decodeFromString<List<LearningJob>>(it) }.getOrElse { emptyList() }
            } ?: emptyList()
            prefs[KEY_JOBS] = json.encodeToString(current.filter { it.status == JobStatus.PENDING })
        }
    }
}
