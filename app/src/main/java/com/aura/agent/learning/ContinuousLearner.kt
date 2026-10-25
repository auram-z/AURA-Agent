package com.aura.agent.learning

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
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
 *
 * پیش‌فرض: هر ۲۴ ساعت یک‌بار (با نیاز به شبکه).
 */
class ContinuousLearner(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "AuraContinuous"
        private const val UNIQUE_NAME = "aura_continuous_learner"

        /**
         * زمان‌بندی پیش‌فرض: هر ۲۴ ساعت.
         * فقط وقتی شبکه در دسترس باشد اجرا می‌شود.
         */
        fun schedule(context: Context, intervalHours: Long = 24) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<ContinuousLearner>(
                intervalHours.coerceAtLeast(6), // حداقل ۶ ساعت برای جلوگیری از فشار
                TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            Log.i(TAG, "Continuous learner scheduled every ${intervalHours}h (network required)")
        }

        fun runOnce(context: Context) {
            val request = androidx.work.OneTimeWorkRequestBuilder<ContinuousLearner>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueue(request)
            Log.i(TAG, "Continuous learner one-shot enqueued")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
            Log.i(TAG, "Continuous learner cancelled")
        }
    }

    override suspend fun doWork(): Result {
        return try {
            val knowledgeStore = KnowledgeStore(applicationContext)
            val scorer = CredibilityScorer()
            val searchService = WebSearchService()
            val detector = ContradictionDetector()

            val existing = knowledgeStore.chunks.first()
            Log.i(TAG, "Starting 24h continuous cycle. Current knowledge: ${existing.size}")

            // ۱. موضوعات اولویت‌دار از دانش موجود (کم‌اطمینان یا قدیمی)
            val topics = selectRefreshTopics(existing)
            if (topics.isEmpty()) {
                Log.i(TAG, "No topics to refresh")
                return Result.success()
            }

            var added = 0
            for (topic in topics.take(4)) { // محدودیت برای باتری و ترافیک
                try {
                    val report = searchService.search(
                        query = topic,
                        maxResults = 5,
                        preferredLangs = listOf("fa", "en")
                    )
                    for (hit in report.hits) {
                        if (hit.snippet.length < 40) continue

                        val score = scorer.score(
                            sourceUrl = hit.url,
                            sourceLabel = hit.sourceLabel,
                            receivedAt = System.currentTimeMillis()
                        )

                        // فقط منابع با حداقل اعتبار ذخیره شوند
                        if (score.confidence < 0.45f) continue

                        val now = System.currentTimeMillis()
                        knowledgeStore.add(
                            KnowledgeStore.KnowledgeChunk(
                                id = UUID.randomUUID().toString(),
                                title = hit.title.take(100),
                                content = hit.snippet.take(600),
                                source = hit.sourceLabel,
                                sourceUrl = hit.url,
                                language = hit.language,
                                tags = listOf("continuous", "auto_refresh", "24h"),
                                confidence = score.confidence,
                                claims = listOf(
                                    KnowledgeStore.Claim(hit.snippet.take(200))
                                ),
                                timestamp = now,
                                lastVerifiedAt = now,
                                receivedAt = now
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
            Log.i(TAG, "24h cycle done. Added=$added | ${analysis.summary}")

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
     * - chunkهای با اطمینان پایین (< 0.6)
     * - chunkهای قدیمی‌تر از ۳۰ روز
     * - در صورت خالی بودن: موضوعات پیش‌فرض
     */
    private fun selectRefreshTopics(chunks: List<KnowledgeStore.KnowledgeChunk>): List<String> {
        if (chunks.isEmpty()) {
            return listOf("هوش مصنوعی", "Android development", "یادگیری ماشین")
        }

        val now = System.currentTimeMillis()
        val thirtyDaysMs = 30L * 86_400_000

        val candidates = chunks
            .filter { chunk ->
                chunk.confidence < 0.6f ||
                    (now - chunk.lastVerifiedAt) > thirtyDaysMs
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
