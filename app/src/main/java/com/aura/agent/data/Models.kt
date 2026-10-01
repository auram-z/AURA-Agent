package com.aura.agent.data

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
    val error: String? = null
)
