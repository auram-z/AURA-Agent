package com.aura.agent.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.agent.data.AuraUiState
import com.aura.agent.data.ChatMessage
import com.aura.agent.data.MessageRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AuraViewModel : ViewModel() {

    private val aiService = AuraAiService()

    private val _uiState = MutableStateFlow(AuraUiState())
    val uiState: StateFlow<AuraUiState> = _uiState.asStateFlow()

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        val userMessage = ChatMessage(role = MessageRole.USER, content = text)

        _uiState.update { state ->
            state.copy(
                messages = state.messages + userMessage,
                isLoading = true,
                error = null
            )
        }

        viewModelScope.launch {
            val history = _uiState.value.messages
            val result = aiService.chat(history)

            result.fold(
                onSuccess = { reply ->
                    val assistantMessage = ChatMessage(role = MessageRole.ASSISTANT, content = reply)
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages + assistantMessage,
                            isLoading = false
                        )
                    }
                },
                onFailure = { error ->
                    val errorMessage = ChatMessage(
                        role = MessageRole.ASSISTANT,
                        content = "متأسفانه خطایی رخ داد:\n${error.message ?: "خطای ناشناخته"}\n\nلطفاً اتصال اینترنت و تنظیمات API را بررسی کنید."
                    )
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages + errorMessage,
                            isLoading = false,
                            error = error.message
                        )
                    }
                }
            )
        }
    }

    fun clearConversation() {
        _uiState.update { AuraUiState() }
    }
}
