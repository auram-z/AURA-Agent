package com.aura.agent.ai

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aura.agent.ai.tools.AuraTools
import com.aura.agent.data.AuraUiState
import com.aura.agent.data.ChatMessage
import com.aura.agent.data.MemoryStore
import com.aura.agent.data.MessageRole
import com.aura.agent.learning.ConfirmationGate
import com.aura.agent.learning.FeedbackStore
import com.aura.agent.learning.KnowledgeStore
import com.aura.agent.learning.LearningManager
import com.aura.agent.learning.OfflineLearningQueue
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

    // ── Learning system ──────────────────────────────────────────────
    private val memoryStore = MemoryStore(application)
    private val feedbackStore = FeedbackStore(application)
    private val knowledgeStore = KnowledgeStore(application)
    private val offlineQueue = OfflineLearningQueue(application)
    private val confirmationGate = ConfirmationGate()

    private val learningManager = LearningManager(
        context = application,
        memoryStore = memoryStore,
        feedbackStore = feedbackStore,
        knowledgeStore = knowledgeStore,
        offlineQueue = offlineQueue,
        confirmationGate = confirmationGate,
        scope = viewModelScope
    )

    private val _uiState = MutableStateFlow(AuraUiState())
    val uiState: StateFlow<AuraUiState> = _uiState.asStateFlow()

    init {
        voiceService.onResult = { text -> sendMessage(text) }
        voiceService.onError = { error ->
            _uiState.update { it.copy(isListening = false, error = error) }
        }
        voiceService.onListeningStateChanged = { listening ->
            _uiState.update { it.copy(isListening = listening) }
        }
        voiceService.onSpeakingStateChanged = { speaking ->
            _uiState.update { it.copy(isSpeaking = speaking) }
        }

        // همگام‌سازی پیشنهاد تأیید با UI
        viewModelScope.launch {
            confirmationGate.pendingConfirmation.collect { pending ->
                _uiState.update { it.copy(pendingConfirmation = pending) }
            }
        }

        refreshNetworkStatus()
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        val toolResult = detectAndRunTool(text)
        val userMessage = ChatMessage(role = MessageRole.USER, content = text)

        _uiState.update { state ->
            state.copy(messages = state.messages + userMessage, isLoading = true, error = null)
        }

        viewModelScope.launch {
            refreshNetworkStatus()

            val extraContext = buildString {
                append(tools.getAvailableToolsDescription())
                append("\n\nوضعیت شبکه: ${if (_uiState.value.networkOnline) "متصل به اینترنت جهانی" else "آفلاین"}")
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
                    voiceService.speak(finalReply)

                    // ── یادگیری: تحلیل و پیشنهاد ذخیره ──
                    learningManager.analyzeAndPropose(
                        userMessage = userMessage,
                        assistantMessage = assistantMessage,
                        isOnline = _uiState.value.networkOnline
                    )
                },
                onFailure = { error ->
                    val msg = toolResult ?: "متأسفانه خطایی رخ داد:\n${error.message}"
                    val errorMessage = ChatMessage(role = MessageRole.ASSISTANT, content = msg)
                    _uiState.update { state ->
                        state.copy(messages = state.messages + errorMessage, isLoading = false, error = error.message)
                    }
                    voiceService.speak(msg.take(500))
                }
            )
        }
    }

    /** تصمیم کاربر روی پیشنهاد ذخیره حافظه/بازخورد */
    fun resolveConfirmation(decision: ConfirmationGate.Decision, editedContent: String? = null) {
        confirmationGate.resolve(decision, editedContent)
    }

    fun cancelConfirmation() {
        confirmationGate.cancel()
    }

    private fun detectAndRunTool(text: String): String? {
        val lower = text.lowercase().trim()
        return when {
            lower.contains("زمان") || lower.contains("ساعت") -> tools.executeTool("get_time")
            lower.contains("باتری") -> tools.executeTool("get_battery")
            lower.contains("دستگاه") || lower.contains("مدل گوشی") -> tools.executeTool("get_device_info")

            lower.contains("وضعیت شبکه") || lower.contains("اینترنت") || lower.contains("اتصال شبکه") ->
                tools.networkStatus()

            lower.contains("وضعیت معاملات") || lower.contains("پورتفو") ||
            lower.contains("وضعیت پورتفو") -> tools.executeTool("trading_status")

            lower.contains("اسکن بورس") || lower.contains("بورس ایران") ->
                tools.executeTool("scan_iran")

            lower.contains("اسکن کریپتو") -> tools.executeTool("scan_crypto")

            lower.contains("معامله خودکار") && (lower.contains("روشن") || lower.contains("فعال")) ->
                tools.executeTool("auto_on")

            lower.contains("معامله خودکار") && (lower.contains("خاموش") || lower.contains("وقف")) ->
                tools.executeTool("auto_off")

            lower.contains("کیل سوییچ") || lower.contains("kill switch") || lower.contains("توقف اورژانسی") ->
                tools.executeTool("kill_switch")

            lower.contains("شنیدن مداوم") || lower.contains("گفتار مداوم") ||
            lower.contains("صدا بدون محدودیت") || lower.contains("continuous") -> {
                enableContinuousListening()
                "✅ شنیدن مداوم فعال شد. بدون محدودیت زمانی صحبت کنید. برای توقف بگویید «توقف شنیدن»."
            }
            lower.contains("توقف شنیدن") || lower.contains("خاموش کردن صدا") -> {
                disableContinuousListening()
                "🛑 شنیدن مداوم خاموش شد."
            }

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

    fun startVoiceInput() {
        voiceService.setContinuousMode(false)
        voiceService.startListening()
        _uiState.update { it.copy(continuousListening = false) }
    }

    fun stopVoiceInput() {
        voiceService.stopListening()
        _uiState.update { it.copy(continuousListening = false, isListening = false) }
    }

    fun enableContinuousListening() {
        voiceService.setContinuousMode(true)
        voiceService.startListening()
        _uiState.update { it.copy(continuousListening = true) }
    }

    fun disableContinuousListening() {
        voiceService.setContinuousMode(false)
        voiceService.stopListening()
        _uiState.update { it.copy(continuousListening = false, isListening = false) }
    }

    fun toggleContinuousListening() {
        if (_uiState.value.continuousListening) {
            disableContinuousListening()
        } else {
            enableContinuousListening()
        }
    }

    fun clearConversation() = _uiState.update {
        AuraUiState(
            continuousListening = it.continuousListening,
            networkOnline = it.networkOnline
            // حافظه بلندمدت پاک نمی‌شود
        )
    }

    fun refreshNetworkStatus() {
        val online = isNetworkAvailable()
        _uiState.update { it.copy(networkOnline = online) }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getApplication<Application>()
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override fun onCleared() {
        super.onCleared()
        voiceService.release()
    }
}
