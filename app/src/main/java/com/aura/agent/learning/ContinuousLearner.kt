package com.aura.agent.learning

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aura.agent.ai.WebSearchService
import kotlinx.coroutines.flow.first
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * یادگیری مستمر و هوشمند ۲۴ ساعته.
 *
 * به جای کراول بی‌هدف وب، فقط موضوعات مرتبط با دانش موجود
 * و پرسش‌های اخیر کاربر را دوره‌ای به‌روز می‌کند.
 */
class ContinuousLearner(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "AuraContinuous"
        private const val UNIQUE_NAME = "aura_continuous_learner"

        /** زمان‌بندی پیش‌فرض: هر ۱۲ ساعت */
        fun schedule(context: Context, intervalHours: Long = 12) {
            val request = PeriodicWorkRequestBuilder<ContinuousLearner>(
                intervalHours, TimeUnit.HOURS
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            Log.i(TAG, "Continuous learner scheduled every ${intervalHours}h")
        }

        fun runOnce(context: Context) {
            val request = androidx.work.OneTimeWorkRequestBuilder<ContinuousLearner>().build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }

    override suspend fun doWork(): Result {
        return try {
            val knowledgeStore = KnowledgeStore(applicationContext)
            val scorer = CredibilityScorer()
            val searchService = WebSearchService()
            val detector = ContradictionDetector()

            val existing = knowledgeStore.chunks.first()
            Log.i(TAG, "Starting continuous cycle. Current knowledge: ${existing.size}")

            // ۱. موضوعات اولویت‌دار از دانش موجود (عنوان‌های پرتکرار / کم‌اطمینان)
            val topics = selectRefreshTopics(existing)
            if (topics.isEmpty()) {
                Log.i(TAG, "No topics to refresh")
                return Result.success()
            }

            var added = 0
            for (topic in topics.take(4)) { // محدودیت برای باتری و ترافیک
                try {
                    val report = searchService.search(topic, maxResults = 5, preferredLangs = listOf("fa", "en"))
                    for (hit in report.hits) {
                        if (hit.snippet.length < 40) continue

                        val score = scorer.score(
                            sourceUrl = hit.url,
                            sourceLabel = hit.sourceLabel,
                            receivedAt = System.currentTimeMillis()
                        )

                        // فقط منابع با حداقل اعتبار ذخیره شوند
                        if (score.confidence < 0.45f) continue

                        knowledgeStore.add(
                            KnowledgeStore.KnowledgeChunk(
                                id = UUID.randomUUID().toString(),
                                title = hit.title.take(100),
                                content = hit.snippet.take(600),
                                source = hit.sourceLabel,
                                sourceUrl = hit.url,
                                language = hit.language,
                                tags = listOf("continuous", "auto_refresh"),
                                confidence = score.confidence,
                                claims = listOf(
                                    KnowledgeStore.Claim(hit.snippet.take(200))
                                ),
                                timestamp = System.currentTimeMillis(),
                                lastVerifiedAt = System.currentTimeMillis(),
                                receivedAt = System.currentTimeMillis()
                            )
                        )
                        added++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Topic '$topic' failed: ${e.message}")
                }
            }

            // ۲. تحلیل تناقض بعد از به‌روزرسانی
            val updated = knowledgeStore.chunks.first()
            val analysis = detector.analyze(updated)
            Log.i(TAG, "Cycle done. Added=$added | ${analysis.summary}")

            // کاهش اطمینان chunkهایی که در تناقض هستند
            analysis.contradictions
                .filter { it.severity >= 0.6f }
                .forEach { c ->
                    knowledgeStore.markVerified(c.chunkIdA, 0.35f)
                    knowledgeStore.markVerified(c.chunkIdB, 0.35f)
                }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Continuous learner failed: ${e.message}")
            Result.retry()
        }
    }

    /**
     * انتخاب موضوعات برای تازه‌سازی:
     * - chunkهای با اطمینان پایین
     * - chunkهای قدیمی‌تر از ۳۰ روز
     * - عناوین پرتکرار
     */
    private fun selectRefreshTopics(chunks: List<KnowledgeStore.KnowledgeChunk>): List<String> {
        if (chunks.isEmpty()) return listOf("هوش مصنوعی", "Android development")

        val now = System.currentTimeMillis()
        val candidates = chunks
            .filter { chunk ->
                chunk.confidence < 0.6f ||
                    (now - chunk.lastVerifiedAt) > 30L * 86_400_000
            }
            .map { it.title.trim() }
            .filter { it.length in 4..60 }
            .distinct()
            .shuffled()
            .take(6)

        return candidates.ifEmpty {
            chunks.sortedByDescending { it.timestamp }
                .take(3)
                .map { it.title }
        }
    }
}
