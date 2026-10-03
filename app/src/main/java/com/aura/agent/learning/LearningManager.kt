package com.aura.agent.learning

import android.content.Context
import android.util.Log
import com.aura.agent.data.ChatMessage
import com.aura.agent.data.MemoryStore
import com.aura.agent.data.MessageFeedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private val knowledgeExtractor: KnowledgeExtractor = KnowledgeExtractor()
) {

    companion object {
        private const val TAG = "AuraLearning"
    }

    fun analyzeAndPropose(
        userMessage: ChatMessage,
        assistantMessage: ChatMessage,
        isOnline: Boolean
    ) {
        scope.launch(Dispatchers.Default) {
            val lower = userMessage.content.lowercase()

            when {
                lower.contains("یادت باشه") || lower.contains("به خاطر بسپار") ||
                lower.contains("یاد بگیر") || lower.contains("remember") -> {
                    proposeMemory(
                        summary = "درخواست ذخیره از کاربر: ${userMessage.content.take(120)}",
                        content = userMessage.content,
                        isOnline = isOnline
                    )
                }
                lower.contains("اسم من") || lower.contains("من هستم") || lower.startsWith("من ") -> {
                    proposeMemory(
                        summary = "احتمالاً نام یا معرفی کاربر",
                        content = userMessage.content,
                        suggestedKey = "user_name",
                        isOnline = isOnline
                    )
                }
                lower.contains("اشتباهه") || lower.contains("درست نیست") ||
                lower.contains("غلط") || lower.contains("نه،") -> {
                    proposeFeedback(
                        summary = "بازخورد اصلاحی کاربر",
                        correction = userMessage.content,
                        messageId = assistantMessage.id,
                        isOnline = isOnline
                    )
                }
                else -> {
                    // فاز ۵: استخراج دانش با LLM (فقط آنلاین و مکالمات نسبتاً بلند)
                    if (isOnline && userMessage.content.length > 40) {
                        tryExtractKnowledge(userMessage, assistantMessage, isOnline)
                    }
                }
            }
        }
    }

    /** فاز ۴: ثبت thumbs بدون دیالوگ (رأی صریح و کم‌اصطکاک) */
    fun submitThumbs(
        messageId: String,
        thumbsUp: Boolean,
        messageContent: String
    ) {
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
            Log.i(TAG, "Thumbs ${if (thumbsUp) "up" else "down"} for $messageId")
        }
    }

    private suspend fun tryExtractKnowledge(
        userMessage: ChatMessage,
        assistantMessage: ChatMessage,
        isOnline: Boolean
    ) {
        val items = knowledgeExtractor.extractFromConversation(userMessage, assistantMessage)
        items.forEach { item ->
            proposeKnowledge(item.title, item.content, isOnline)
        }
    }

    private fun proposeMemory(
        summary: String,
        content: String,
        suggestedKey: String? = null,
        isOnline: Boolean
    ) {
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
            when (decision) {
                ConfirmationGate.Decision.APPROVE,
                ConfirmationGate.Decision.EDIT_AND_APPROVE -> {
                    val finalContent = edited?.takeIf { it.isNotBlank() } ?: pending.rawContent
                    scope.launch {
                        if (pending.suggestedKey == "user_name") {
                            memoryStore.saveUserName(finalContent)
                        } else {
                            memoryStore.saveNotes(finalContent)
                        }
                    }
                }
                ConfirmationGate.Decision.REJECT -> { }
            }
        }
    }

    private fun proposeFeedback(
        summary: String,
        correction: String,
        messageId: String? = null,
        isOnline: Boolean
    ) {
        val item = ConfirmationGate.PendingItem(
            id = UUID.randomUUID().toString(),
            category = ConfirmationGate.Category.FEEDBACK,
            summary = summary,
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
            if (decision == ConfirmationGate.Decision.APPROVE ||
                decision == ConfirmationGate.Decision.EDIT_AND_APPROVE) {
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
