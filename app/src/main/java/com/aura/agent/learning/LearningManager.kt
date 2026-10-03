package com.aura.agent.learning

import android.content.Context
import android.util.Log
import com.aura.agent.data.ChatMessage
import com.aura.agent.data.MemoryStore
import com.aura.agent.data.MessageRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * مدیر مرکزی یادگیری AURA.
 *
 * مسئول:
 * - تشخیص اینکه آیا پیام/پاسخ ارزش یادگیری دارد
 * - پیشنهاد ذخیره از طریق ConfirmationGate
 * - توزیع به Memory / Feedback / Knowledge
 * - صف‌بندی آفلاین
 */
class LearningManager(
    private val context: Context,
    private val memoryStore: MemoryStore,
    private val feedbackStore: FeedbackStore,
    private val knowledgeStore: KnowledgeStore,
    private val offlineQueue: OfflineLearningQueue,
    private val confirmationGate: ConfirmationGate,
    private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "AuraLearning"
    }

    /**
     * بعد از هر پاسخ مدل فراخوانی شود.
     * تصمیم می‌گیرد آیا چیزی برای ذخیره پیشنهاد شود یا نه.
     */
    fun analyzeAndPropose(
        userMessage: ChatMessage,
        assistantMessage: ChatMessage,
        isOnline: Boolean
    ) {
        scope.launch(Dispatchers.Default) {
            // قوانین ساده تشخیص (قابل ارتقا با مدل):
            val lower = userMessage.content.lowercase()

            when {
                // کاربر صریحاً گفته «یادت باشه» یا مشابه
                lower.contains("یادت باشه") || lower.contains("به خاطر بسپار") ||
                lower.contains("یاد بگیر") || lower.contains("remember") -> {
                    proposeMemory(
                        summary = "درخواست ذخیره از کاربر: ${userMessage.content.take(120)}",
                        content = userMessage.content,
                        isOnline = isOnline
                    )
                }

                // نام کاربر
                lower.contains("اسم من") || lower.contains("من هستم") || lower.startsWith("من ") -> {
                    proposeMemory(
                        summary = "احتمالاً نام یا معرفی کاربر",
                        content = userMessage.content,
                        suggestedKey = "user_name",
                        isOnline = isOnline
                    )
                }

                // بازخورد منفی/مثبت ساده
                lower.contains("اشتباهه") || lower.contains("درست نیست") ||
                lower.contains("غلط") || lower.contains("نه،") -> {
                    proposeFeedback(
                        summary = "بازخورد اصلاحی کاربر",
                        correction = userMessage.content,
                        isOnline = isOnline
                    )
                }

                else -> {
                    // در نسخه فعلی فقط موارد صریح را پیشنهاد می‌دهیم
                    // بعداً می‌توان با LLM تصمیم‌گیری کرد
                }
            }
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
            // آفلاین → صف
            scope.launch {
                offlineQueue.enqueue(
                    OfflineLearningQueue.LearningJob(
                        type = OfflineLearningQueue.JobType.MEMORY_SAVE,
                        payload = content
                    )
                )
                Log.i(TAG, "Memory job queued (offline)")
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
                        Log.i(TAG, "Memory saved after confirmation")
                    }
                }
                ConfirmationGate.Decision.REJECT -> {
                    Log.i(TAG, "Memory save rejected by user")
                }
            }
        }
    }

    private fun proposeFeedback(
        summary: String,
        correction: String,
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
                            correction = final
                        )
                    )
                    Log.i(TAG, "Feedback saved")
                }
            }
        }
    }

    /** فراخوانی دستی از UI برای ذخیره دانش */
    fun proposeKnowledge(title: String, content: String, isOnline: Boolean) {
        val item = ConfirmationGate.PendingItem(
            id = UUID.randomUUID().toString(),
            category = ConfirmationGate.Category.KNOWLEDGE,
            summary = "دانش جدید: $title",
            rawContent = content
        )

        confirmationGate.propose(item) { pending, decision, edited ->
            if (decision != ConfirmationGate.Decision.REJECT) {
                val final = edited?.takeIf { it.isNotBlank() } ?: pending.rawContent
                scope.launch {
                    knowledgeStore.add(
                        KnowledgeStore.KnowledgeChunk(
                            id = UUID.randomUUID().toString(),
                            title = title,
                            content = final
                        )
                    )
                }
            }
        }
    }
}
