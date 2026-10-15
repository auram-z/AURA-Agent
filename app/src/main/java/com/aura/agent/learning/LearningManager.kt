package com.aura.agent.learning

import android.content.Context
import android.util.Log
import com.aura.agent.data.ChatMessage
import com.aura.agent.data.MemoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

class LearningManager(
    private val context: Context,
    private val memoryStore: MemoryStore,
    private val feedbackStore: FeedbackStore,
    private val knowledgeStore: KnowledgeStore,
    private val offlineQueue: OfflineLearningQueue,
    private val confirmationGate: ConfirmationGate,
    private val scope: CoroutineScope,
    private val autoLearnSettings: AutoLearnSettings = AutoLearnSettings(context),
    private val knowledgeExtractor: KnowledgeExtractor = KnowledgeExtractor()
) {

    companion object {
        private const val TAG = "AuraLearning"
        private val NOISE_PREF_PHRASES = setOf(
            "دوست دارم بدونم", "دوست دارم بدونم", "دوست دارم ببینم"
        )
    }

    val autoLearnEnabled = autoLearnSettings.enabled

    fun setAutoLearn(enabled: Boolean) {
        scope.launch { autoLearnSettings.setEnabled(enabled) }
    }

    fun analyzeAndPropose(
        userMessage: ChatMessage,
        assistantMessage: ChatMessage,
        isOnline: Boolean
    ) {
        scope.launch(Dispatchers.Default) {
            val lower = userMessage.content.lowercase()
            val auto = autoLearnSettings.enabled.first()

            when {
                lower.contains("یادت باشه") || lower.contains("به خاطر بسپار") ||
                lower.contains("یاد بگیر") || lower.contains("remember") -> {
                    saveMemory(
                        summary = "درخواست ذخیره: ${userMessage.content.take(120)}",
                        content = userMessage.content,
                        suggestedKey = null,
                        isOnline = isOnline,
                        forceConfirm = !auto
                    )
                }
                lower.contains("اسم من") || lower.contains("من هستم") -> {
                    saveMemory(
                        summary = "نام / معرفی کاربر",
                        content = userMessage.content,
                        suggestedKey = "user_name",
                        isOnline = isOnline,
                        forceConfirm = true
                    )
                }
                lower.contains("اشتباهه") || lower.contains("درست نیست") ||
                lower.contains("غلط") || lower.contains("نه،") -> {
                    saveFeedback(
                        correction = userMessage.content,
                        messageId = assistantMessage.id,
                        isOnline = isOnline,
                        auto = auto
                    )
                }
                else -> {
                    if (isOnline && (auto || userMessage.content.length > 30)) {
                        tryExtractKnowledge(userMessage, assistantMessage, isOnline, auto)
                    }
                    if (auto) {
                        detectImplicitPreference(lower, isOnline)
                    }
                }
            }
        }
    }

    /**
     * ساخت context از حافظه/دانش برای تزریق به مدل.
     * اگر [query] داده شود، دانش مرتبط با جستجو اولویت دارد.
     */
    suspend fun buildLearnedContext(query: String? = null): String {
        if (!autoLearnSettings.injectContext.first()) return ""
        return buildString {
            memoryStore.userName.first()?.takeIf { it.isNotBlank() }?.let {
                append("نام کاربر: $it\n")
            }
            memoryStore.notes.first()?.takeIf { it.isNotBlank() }?.let {
                append("یادداشت‌ها: ${it.take(400)}\n")
            }
            memoryStore.goals.first()?.takeIf { it.isNotBlank() }?.let {
                append("اهداف: ${it.take(200)}\n")
            }
            memoryStore.preferences.first()?.takeIf { it.isNotBlank() }?.let {
                append("ترجیحات: ${it.take(200)}\n")
            }

            val selected = if (!query.isNullOrBlank() && query.length >= 2) {
                val hits = knowledgeStore.search(query, limit = 8)
                if (hits.isNotEmpty()) hits
                else knowledgeStore.chunks.first().sortedByDescending { it.timestamp }.take(6)
            } else {
                knowledgeStore.chunks.first().sortedByDescending { it.timestamp }.take(8)
            }

            if (selected.isNotEmpty()) {
                append("دانش ذخیره‌شده:\n")
                selected.forEach { append("- ${it.title}: ${it.content.take(140)}\n") }
            }
        }.trim()
    }

    fun submitThumbs(messageId: String, thumbsUp: Boolean, messageContent: String) {
        scope.launch {
            feedbackStore.add(
                FeedbackStore.FeedbackEntry(
                    id = UUID.randomUUID().toString(),
                    messageId = messageId,
                    thumbsUp = thumbsUp,
                    rating = if (thumbsUp) 5 else 1,
                    note = messageContent.take(200)
                )
            )
            Log.i(TAG, "Thumbs ${if (thumbsUp) "up" else "down"}")
        }
    }

    private suspend fun tryExtractKnowledge(
        userMessage: ChatMessage,
        assistantMessage: ChatMessage,
        isOnline: Boolean,
        auto: Boolean
    ) {
        val items = knowledgeExtractor.extractFromConversation(userMessage, assistantMessage)
        items.forEach { item ->
            if (auto && isOnline) {
                knowledgeStore.add(
                    KnowledgeStore.KnowledgeChunk(
                        id = UUID.randomUUID().toString(),
                        title = item.title,
                        content = item.content,
                        source = "auto_llm",
                        tags = listOf("auto")
                    )
                )
                Log.i(TAG, "Auto-saved knowledge: ${item.title}")
            } else {
                proposeKnowledge(item.title, item.content, isOnline)
            }
        }
    }

    /**
     * ترجیح ضمنی فقط با فیلتر نویز و حداقل طول؛ در حالت auto بدون دیالوگ ذخیره می‌شود
     * ولی از عبارات مبهم جلوگیری می‌شود.
     */
    private fun detectImplicitPreference(lower: String, isOnline: Boolean) {
        if (lower.length !in 20..180) return
        if (NOISE_PREF_PHRASES.any { lower.contains(it) }) return

        val prefs = listOf(
            "دوست دارم" to "like",
            "ترجیح میدم" to "prefer",
            "ترجیح می‌دم" to "prefer",
            "همیشه" to "always",
            "هرگز" to "never",
            "نمی‌خوام" to "dislike",
            "نمیخوام" to "dislike"
        )
        for ((phrase, _) in prefs) {
            if (!lower.contains(phrase)) continue
            // باید بعد از عبارت محتوای معنادار باشد
            val idx = lower.indexOf(phrase)
            val after = lower.drop(idx + phrase.length).trim()
            if (after.length < 4) continue

            scope.launch {
                val existing = memoryStore.preferences.first().orEmpty()
                val line = lower.take(150)
                if (existing.contains(line.take(40))) return@launch

                // حتی در auto، ترجیح ضمنی را با تأیید سبک پیشنهاد می‌کنیم اگر آنلاین نیست
                if (!isOnline) {
                    offlineQueue.enqueue(
                        OfflineLearningQueue.LearningJob(
                            type = OfflineLearningQueue.JobType.MEMORY_SAVE,
                            payload = "ترجیح: $line"
                        )
                    )
                    return@launch
                }

                memoryStore.savePreference(
                    "auto_${System.currentTimeMillis()}",
                    line
                )
                val notes = memoryStore.notes.first().orEmpty()
                if (!notes.contains(line.take(30))) {
                    memoryStore.saveNotes(
                        (notes + "\n• $line").trim().take(2000)
                    )
                }
                Log.i(TAG, "Auto preference: $line")
            }
            break
        }
    }

    private fun saveMemory(
        summary: String,
        content: String,
        suggestedKey: String?,
        isOnline: Boolean,
        forceConfirm: Boolean
    ) {
        if (!forceConfirm) {
            scope.launch {
                if (suggestedKey == "user_name") memoryStore.saveUserName(content)
                else memoryStore.saveNotes(content)
                Log.i(TAG, "Auto memory saved")
            }
            return
        }

        val item = ConfirmationGate.PendingItem(
            id = UUID.randomUUID().toString(),
            category = ConfirmationGate.Category.MEMORY_FACT,
            summary = summary,
            rawContent = content,
            suggestedKey = suggestedKey
        )

        if (!isOnline) {
            scope.launch {
                offlineQueue.enqueue(
                    OfflineLearningQueue.LearningJob(
                        type = OfflineLearningQueue.JobType.MEMORY_SAVE,
                        payload = content
                    )
                )
            }
            return
        }

        confirmationGate.propose(item) { pending, decision, edited ->
            if (decision == ConfirmationGate.Decision.APPROVE ||
                decision == ConfirmationGate.Decision.EDIT_AND_APPROVE
            ) {
                val finalContent = edited?.takeIf { it.isNotBlank() } ?: pending.rawContent
                scope.launch {
                    if (pending.suggestedKey == "user_name") memoryStore.saveUserName(finalContent)
                    else memoryStore.saveNotes(finalContent)
                }
            }
        }
    }

    private fun saveFeedback(
        correction: String,
        messageId: String?,
        isOnline: Boolean,
        auto: Boolean
    ) {
        if (auto) {
            scope.launch {
                feedbackStore.add(
                    FeedbackStore.FeedbackEntry(
                        id = UUID.randomUUID().toString(),
                        messageId = messageId,
                        correction = correction
                    )
                )
                Log.i(TAG, "Auto feedback saved")
            }
            return
        }

        val item = ConfirmationGate.PendingItem(
            id = UUID.randomUUID().toString(),
            category = ConfirmationGate.Category.FEEDBACK,
            summary = "بازخورد اصلاحی",
            rawContent = correction
        )

        if (!isOnline) {
            scope.launch {
                offlineQueue.enqueue(
                    OfflineLearningQueue.LearningJob(
                        type = OfflineLearningQueue.JobType.FEEDBACK_SAVE,
                        payload = correction
                    )
                )
            }
            return
        }

        confirmationGate.propose(item) { pending, decision, edited ->
            if (decision != ConfirmationGate.Decision.REJECT) {
                val final = edited?.takeIf { it.isNotBlank() } ?: pending.rawContent
                scope.launch {
                    feedbackStore.add(
                        FeedbackStore.FeedbackEntry(
                            id = UUID.randomUUID().toString(),
                            messageId = messageId,
                            correction = final
                        )
                    )
                }
            }
        }
    }

    fun proposeKnowledge(title: String, content: String, isOnline: Boolean) {
        val item = ConfirmationGate.PendingItem(
            id = UUID.randomUUID().toString(),
            category = ConfirmationGate.Category.KNOWLEDGE,
            summary = "دانش جدید: $title",
            rawContent = content
        )
        if (!isOnline) {
            scope.launch {
                offlineQueue.enqueue(
                    OfflineLearningQueue.LearningJob(
                        type = OfflineLearningQueue.JobType.KNOWLEDGE_EXTRACT,
                        payload = "$title\n$content"
                    )
                )
            }
            return
        }
        confirmationGate.propose(item) { pending, decision, edited ->
            if (decision != ConfirmationGate.Decision.REJECT) {
                val final = edited?.takeIf { it.isNotBlank() } ?: pending.rawContent
                scope.launch {
                    knowledgeStore.add(
                        KnowledgeStore.KnowledgeChunk(
                            id = UUID.randomUUID().toString(),
                            title = title,
                            content = final,
                            source = "llm_extract"
                        )
                    )
                }
            }
        }
    }
}
