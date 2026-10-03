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
 * فاز ۶: پردازش دوره‌ای صف یادگیری آفلاین با WorkManager.
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
                            knowledgeStore.add(
                                KnowledgeStore.KnowledgeChunk(
                                    id = UUID.randomUUID().toString(),
                                    title = "آفلاین",
                                    content = job.payload,
                                    source = "offline_queue"
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
                    Log.e(TAG, "Job ${job.id} failed: ${e.message}")
                    queue.markFailed(job.id)
                }
            }
            queue.clearCompleted()
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Worker failed: ${e.message}")
            Result.retry()
        }
    }
}
