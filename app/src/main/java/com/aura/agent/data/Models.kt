package com.aura.agent.data

import com.aura.agent.learning.ConfirmationGate
import java.util.UUID

enum class MessageRole {
    USER, ASSISTANT, SYSTEM
}

/** بازخورد روی یک پیام: null = هنوز رأی نداده */
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
    val lastTrainingReport: String? = null
)
