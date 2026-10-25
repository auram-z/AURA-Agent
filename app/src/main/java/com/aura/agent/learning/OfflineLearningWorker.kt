package com.aura.agent.learning

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aura.agent.data.MemoryStore
import kotlinx.coroutines.flow.first
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * پردازش دوره‌ای صف یادگیری آفلاین با WorkManager.
 */
class OfflineLearningWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "AuraOfflineWorker"
        private const val UNIQUE_NAME = "aura_offline_learning"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<OfflineLearningWorker>(6, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.i(TAG, "Periodic offline learning scheduled")
        }

        fun runOnce(context: Context) {
            val request = androidx.work.OneTimeWorkRequestBuilder<OfflineLearningWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }

    override suspend fun doWork(): Result {
        return try {
            val queue = OfflineLearningQueue(applicationContext)
            val memoryStore = MemoryStore(applicationContext)
            val feedbackStore = FeedbackStore(applicationContext)
            val knowledgeStore = KnowledgeStore(applicationContext)

            val jobs = queue.pendingJobs.first()
            Log.i(TAG, "Processing ${jobs.size} pending jobs")

            var failures = 0
            for (job in jobs) {
                try {
                    when (job.type) {
                        OfflineLearningQueue.JobType.MEMORY_SAVE -> {
                            memoryStore.saveNotes(job.payload)
                            queue.markDone(job.id)
                        }
                        OfflineLearningQueue.JobType.FEEDBACK_SAVE -> {
                            feedbackStore.add(
                                FeedbackStore.FeedbackEntry(
                                    id = UUID.randomUUID().toString(),
                                    correction = job.payload
                                )
                            )
                            queue.markDone(job.id)
                        }
                        OfflineLearningQueue.JobType.KNOWLEDGE_EXTRACT -> {
                            val lines = job.payload.lines()
                            val title = lines.firstOrNull()?.take(80)?.ifBlank { "آفلاین" } ?: "آفلاین"
                            val content = lines.drop(1).joinToString("\n").ifBlank { job.payload }
                            val now = System.currentTimeMillis()
                            val lang = if (content.any { it in '\u0600'..'\u06FF' }) "fa" else "en"
                            knowledgeStore.add(
                                KnowledgeStore.KnowledgeChunk(
                                    id = UUID.randomUUID().toString(),
                                    title = title,
                                    content = content.take(500),
                                    source = "offline_queue",
                                    language = lang,
                                    tags = listOf("offline"),
                                    confidence = 0.45f,
                                    claims = listOf(KnowledgeStore.Claim(content.take(200))),
                                    timestamp = now,
                                    lastVerifiedAt = now,
                                    receivedAt = now
                                )
                            )
                            queue.markDone(job.id)
                        }
                        OfflineLearningQueue.JobType.TRAINING_PREPARE -> {
                            val pipeline = TrainingPipeline(
                                applicationContext, memoryStore, feedbackStore, knowledgeStore
                            )
                            pipeline.prepareDataset()
                            queue.markDone(job.id)
                        }
                    }
                } catch (e: Exception) {
                    failures++
                    Log.e(TAG, "Job ${job.id} failed: ${e.message}")
                    queue.markFailed(job.id)
                }
            }
            queue.clearCompleted()
            if (failures > 0 && failures == jobs.size) Result.retry()
            else Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Worker failed: ${e.message}")
            Result.retry()
        }
    }
}
