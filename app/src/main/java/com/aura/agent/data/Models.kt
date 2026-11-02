package com.aura.agent.data

import com.aura.agent.learning.ConfirmationGate
import java.util.UUID

enum class MessageRole {
    USER, ASSISTANT, SYSTEM
}

enum class MessageFeedback {
    NONE, THUMBS_UP, THUMBS_DOWN
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val feedback: MessageFeedback = MessageFeedback.NONE
)

data class AuraUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isLoading: Boolean = false,
    val isListening: Boolean = false,
    val isSpeaking: Boolean = false,
    val continuousListening: Boolean = false,
    val networkOnline: Boolean = true,
    val error: String? = null,
    val pendingConfirmation: ConfirmationGate.PendingItem? = null,
    val lastTrainingReport: String? = null,
    val autoLearnEnabled: Boolean = true,
    val activeModel: String = "grok-4.7",
    /** LOCAL_ONLY | HYBRID | REMOTE_PREFERRED — پیش‌فرض استقلال کامل */
    val runtimePolicy: String = "LOCAL_ONLY",
    val activeBackend: String = "LOCAL"
)
