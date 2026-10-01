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
        voiceService.onResult = { text -> sendMessage(text) }
        voiceService.onError = { error -> _uiState.update { it.copy(isListening = false, error = error) } }
        voiceService.onListeningStateChanged = { listening -> _uiState.update { it.copy(isListening = listening) } }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        val toolResult = detectAndRunTool(text)
        val userMessage = ChatMessage(role = MessageRole.USER, content = text)

        _uiState.update { state ->
            state.copy(messages = state.messages + userMessage, isLoading = true, error = null)
        }

        viewModelScope.launch {
            val extraContext = buildString {
                append(tools.getAvailableToolsDescription())
                if (toolResult != null) append("\n\nنتیجه ابزار:\n$toolResult")
            }

            val history = _uiState.value.messages
            val result = aiService.chat(history, extraContext)

            result.fold(
                onSuccess = { reply ->
                    val finalReply = when {
                        toolResult != null && reply.length < 40 -> toolResult
                        toolResult != null -> "$toolResult\n\n$reply"
                        else -> reply
                    }
                    val assistantMessage = ChatMessage(role = MessageRole.ASSISTANT, content = finalReply)
                    _uiState.update { state ->
                        state.copy(messages = state.messages + assistantMessage, isLoading = false)
                    }
                    voiceService.speak(finalReply.take(300))
                },
                onFailure = { error ->
                    val msg = toolResult ?: "متأسفانه خطایی رخ داد:\n${error.message}"
                    val errorMessage = ChatMessage(role = MessageRole.ASSISTANT, content = msg)
                    _uiState.update { state ->
                        state.copy(messages = state.messages + errorMessage, isLoading = false, error = error.message)
                    }
                }
            )
        }
    }

    private fun detectAndRunTool(text: String): String? {
        val lower = text.lowercase().trim()
        return when {
            lower.contains("زمان") || lower.contains("ساعت") -> tools.executeTool("get_time")
            lower.contains("باتری") -> tools.executeTool("get_battery")
            lower.contains("دستگاه") || lower.contains("مدل گوشی") -> tools.executeTool("get_device_info")

            lower.contains("وضعیت معاملات") || lower.contains("پورتفو") ||
            lower.contains("وضعیت پورتفو") -> tools.executeTool("trading_status")

            lower.contains("اسکن بورس") || lower.contains("بورس ایران") ->
                tools.executeTool("scan_iran")

            lower.contains("اسکن کریپتو") -> tools.executeTool("scan_crypto")

            lower.contains("معامله خودکار") && (lower.contains("روش
") || lower.contains("فعال")) ->
                tools.executeTool("auto_on")

            lower.contains("معامله خودکار") && (lower.contains("خاموش") || lower.contains("وقف")) ->
                tools.executeTool("auto_off")

            lower.contains("کیل سوییچ") || lower.contains("kill switch") || lower.contains("توقف اورژانسی") ->
                tools.executeTool("kill_switch")

            // Backtest
            lower.contains("بک‌تست") && lower.contains("بورس") -> tools.backtestIran()
            lower.contains("بک تست") && lower.contains("بورس") -> tools.backtestIran()
            lower.contains("backtest") && lower.contains("iran") -> tools.backtestIran()

            lower.contains("بک‌تست") && (lower.contains("btc") || lower.contains("بیتکوین")) ->
                tools.backtestCryptoSymbol("BTCUSDT")
            lower.contains("بک‌تست") && (lower.contains("eth") || lower.contains("اتر")) ->
                tools.backtestCryptoSymbol("ETHUSDT")

            lower.contains("بک‌تست") && lower.contains("کریپتو") -> tools.backtestCrypto()
            lower.contains("بک تست") && lower.contains("کریپتو") -> tools.backtestCrypto()
            lower.contains("backtest") && lower.contains("crypto") -> tools.backtestCrypto()
            lower.contains("بک‌تست") || lower.contains("بک تست") || lower.contains("backtest") ->
                tools.backtestCrypto()

            else -> null
        }
    }

    fun startVoiceInput() = voiceService.startListening()
    fun stopVoiceInput() = voiceService.stopListening()
    fun clearConversation() = _uiState.update { AuraUiState() }

    override fun onCleared() {
        super.onCleared()
        voiceService.release()
    }
}
