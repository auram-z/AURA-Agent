package com.aura.agent.learning

import android.content.Context
import android.util.Log
import com.aura.agent.data.MemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * فاز ۷: آماده‌سازی و export داده برای fine-tune (JSONL).
 * اجرای واقعی fine-tune روی دستگاه/سرور در نسخه‌های بعدی.
 */
class TrainingPipeline(
    private val context: Context,
    private val memoryStore: MemoryStore,
    private val feedbackStore: FeedbackStore,
    private val knowledgeStore: KnowledgeStore
) {

    companion object {
        private const val TAG = "AuraTraining"
    }

    @Serializable
    data class TrainingExample(
        val messages: List<Msg>,
        val source: String
    )

    @Serializable
    data class Msg(val role: String, val content: String)

    data class TrainingReport(
        val memoryItems: Int,
        val feedbackItems: Int,
        val knowledgeChunks: Int,
        val exportedPath: String?,
        val exampleCount: Int,
        val message: String
    )

    suspend fun prepareDataset(): TrainingReport = withContext(Dispatchers.IO) {
        Log.i(TAG, "Preparing training dataset...")
        val json = Json { prettyPrint = false }

        val notes = memoryStore.notes.first()
        val name = memoryStore.userName.first()
        val goals = memoryStore.goals.first()
        val feedbacks = feedbackStore.entries.first()
        val chunks = knowledgeStore.chunks.first()

        val examples = mutableListOf<TrainingExample>()

        name?.takeIf { it.isNotBlank() }?.let {
            examples.add(
                TrainingExample(
                    listOf(
                        Msg("user", "اسم من چیست؟"),
                        Msg("assistant", it)
                    ),
                    "memory_name"
                )
            )
        }
        notes?.takeIf { it.isNotBlank() }?.let {
            examples.add(
                TrainingExample(
                    listOf(
                        Msg("user", "یادداشت‌های من را بگو"),
                        Msg("assistant", it)
                    ),
                    "memory_notes"
                )
            )
        }
        goals?.takeIf { it.isNotBlank() }?.let {
            examples.add(
                TrainingExample(
                    listOf(
                        Msg("user", "اهداف من چیست؟"),
                        Msg("assistant", it)
                    ),
                    "memory_goals"
                )
            )
        }

        feedbacks.filter { !it.correction.isNullOrBlank() }.forEach { fb ->
            examples.add(
                TrainingExample(
                    listOf(
                        Msg("user", "اصلاح کن: ${fb.correction}"),
                        Msg("assistant", "متوجه شدم. ${fb.correction}")
                    ),
                    "feedback"
                )
            )
        }

        chunks.forEach { chunk ->
            examples.add(
                TrainingExample(
                    listOf(
                        Msg("user", "درباره «${chunk.title}» چه می‌دانی؟"),
                        Msg("assistant", chunk.content)
                    ),
                    "knowledge"
                )
            )
        }

        var path: String? = null
        if (examples.isNotEmpty()) {
            val dir = File(context.filesDir, "training")
            if (!dir.exists()) dir.mkdirs()
            val out = File(dir, "aura_finetune_${System.currentTimeMillis()}.jsonl")
            out.bufferedWriter().use { w ->
                examples.forEach { ex ->
                    w.appendLine(json.encodeToString(ex))
                }
            }
            path = out.absolutePath
            Log.i(TAG, "Exported ${examples.size} examples to $path")
        }

        TrainingReport(
            memoryItems = listOfNotNull(name, notes, goals).size,
            feedbackItems = feedbacks.size,
            knowledgeChunks = chunks.size,
            exportedPath = path,
            exampleCount = examples.size,
            message = if (path != null)
                "✅ ${examples.size} نمونه برای fine-tune در فایل ذخیره شد."
            else
                "هنوز داده تأییدشده‌ای برای export وجود ندارد."
        )
    }

    suspend fun runFineTune(): Result<String> = withContext(Dispatchers.IO) {
        val report = prepareDataset()
        if (report.exportedPath == null) {
            return@withContext Result.failure(
                IllegalStateException("داده کافی برای fine-tune نیست.")
            )
        }
        // placeholder: اتصال به API fine-tune یا on-device LoRA در آینده
        Result.success(
            "داده آماده است: ${report.exportedPath}\n" +
                "برای fine-tune واقعی از این JSONL با API ارائه‌دهنده مدل استفاده کنید."
        )
    }
}
