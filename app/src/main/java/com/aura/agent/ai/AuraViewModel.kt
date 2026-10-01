package com.aura.agent.ai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aura.agent.ai.tools.AuraTools
import com.aura.agent.data.AuraUiState
import com.aura.agent.data.ChatMessage
import com.aura.agent.data.MessageRole
import com.aura.agent.voice.VoiceService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AuraViewModel(application: Application) : AndroidViewModel(application) {

    private val aiService = AuraAiService()
    private val tools = AuraTools(application)
    private val voiceService = VoiceService(application)

    private val _uiState = MutableStateFlow(AuraUiState())
    val uiState: StateFlow<AuraUiState> = _uiState.asStateFlow()

    init {
        voiceService.onResult = { text ->
            sendMessage(text)
        }
        voiceService.onError = { error ->
            _uiState.update { it.copy(isListening = false, error = error) }
        }
        voiceService.onListeningStateChanged = { listening ->
            _uiState.update { it.copy(isListening = listening) }
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        // بررسی ابزارهای ساده
        val toolResult = detectAndRunTool(text)

        val userMessage = ChatMessage(role = MessageRole.USER, content = text)

        _uiState.update { state ->
            state.copy(
                messages = state.messages + userMessage,
                isLoading = true,
                error = null
            )
        }

        viewModelScope.launch {
            val extraContext = buildString {
                append(tools.getAvailableToolsDescription())
                if (toolResult != null) {
                    append("\n\nنتیجه ابزار: $toolResult")
                }
            }

            val history = _uiState.value.messages
            val result = aiService.chat(history, extraContext)

            result.fold(
                onSuccess = { reply ->
                    val finalReply = if (toolResult != null && !reply.contains(toolResult.take(20))) {
                        "$toolResult\n\n$reply"
                    } else reply

                    val assistantMessage = ChatMessage(role = MessageRole.ASSISTANT, content = finalReply)
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages + assistantMessage,
                            isLoading = false
                        )
                    }
                    // پاسخ صوتی
                    voiceService.speak(finalReply)
                },
                onFailure = { error ->
                    val errorMessage = ChatMessage(
                        role = MessageRole.ASSISTANT,
                        content = "متأسفانه خطایی رخ داد:\n${error.message ?: "خطای ناشناخته"}"
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

    private fun detectAndRunTool(text: String): String? {
        val lower = text.lowercase()
        return when {
            lower.contains("زمان") || lower.contains("ساعت") || lower.contains("time") ->
                tools.executeTool("get_time")
            lower.contains("باتری") || lower.contains("battery") ->
                tools.executeTool("get_battery")
            lower.contains("دستگاه") || lower.contains("مدل گوشی") || lower.contains("device") ->
                tools.executeTool("get_device_info")
            else -> null
        }
    }

    fun startVoiceInput() {
        voiceService.startListening()
    }

    fun stopVoiceInput() {
        voiceService.stopListening()
    }

    fun clearConversation() {
        _uiState.update { AuraUiState() }
    }

    override fun onCleared() {
        super.onCleared()
        voiceService.release()
    }
}
