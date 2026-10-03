package com.aura.agent.data

import com.aura.agent.learning.ConfirmationGate

enum class MessageRole {
    USER, ASSISTANT, SYSTEM
}

data class ChatMessage(
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class AuraUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isLoading: Boolean = false,
    val isListening: Boolean = false,
    val isSpeaking: Boolean = false,
    val continuousListening: Boolean = false,
    val networkOnline: Boolean = true,
    val error: String? = null,
    /** پیشنهاد ذخیره حافظه/بازخورد که منتظر تأیید کاربر است */
    val pendingConfirmation: ConfirmationGate.PendingItem? = null
)
