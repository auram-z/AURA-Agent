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
import com.aura.agent.data.MessageFeedback
import com.aura.agent.data.MessageRole
import com.aura.agent.learning.ConfirmationGate
import com.aura.agent.learning.FeedbackStore
import com.aura.agent.learning.KnowledgeStore
import com.aura.agent.learning.LearningManager
import com.aura.agent.learning.OfflineLearningQueue
import com.aura.agent.learning.OfflineLearningWorker
import com.aura.agent.learning.TrainingPipeline
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

    private val trainingPipeline = TrainingPipeline(
        application, memoryStore, feedbackStore, knowledgeStore
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

        viewModelScope.launch {
            confirmationGate.pendingConfirmation.collect { pending ->
                _uiState.update { it.copy(pendingConfirmation = pending) }
            }
        }

        viewModelScope.launch {
            learningManager.autoLearnEnabled.collect { enabled ->
                _uiState.update { it.copy(autoLearnEnabled = enabled) }
            }
        }

        OfflineLearningWorker.schedule(application)
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

            val learned = learningManager.buildLearnedContext()
            val extraContext = buildString {
                append(tools.getAvailableToolsDescription())
                append("\n\nوضعیت شبکه: ${if (_uiState.value.networkOnline) "متصل" else "آفلاین"}")
                append("\nیادگیری خودکار: ${if (_uiState.value.autoLearnEnabled) "فعال" else "خاموش"}")
                if (learned.isNotBlank()) {
                    append("\n\n=== حافظه و دانش یادگرفته‌شده ===\n")
                    append(learned)
                }
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

    fun rateMessage(messageId: String, thumbsUp: Boolean) {
        val msg = _uiState.value.messages.find { it.id == messageId } ?: return
        if (msg.role != MessageRole.ASSISTANT) return
        val fb = if (thumbsUp) MessageFeedback.THUMBS_UP else MessageFeedback.THUMBS_DOWN
        _uiState.update { state ->
            state.copy(messages = state.messages.map {
                if (it.id == messageId) it.copy(feedback = fb) else it
            })
        }
        learningManager.submitThumbs(messageId, thumbsUp, msg.content)
    }

    fun setAutoLearn(enabled: Boolean) {
        learningManager.setAutoLearn(enabled)
        _uiState.update { it.copy(autoLearnEnabled = enabled) }
    }

    fun prepareTrainingData() {
        viewModelScope.launch {
            val report = trainingPipeline.prepareDataset()
            _uiState.update { it.copy(lastTrainingReport = report.message) }
        }
    }

    fun processOfflineQueueNow() {
        OfflineLearningWorker.runOnce(getApplication())
    }

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
            lower.contains("وضعیت شبکه") || lower.contains("اینترنت") -> tools.networkStatus()
            lower.contains("وضعیت معاملات") || lower.contains("پورتفو") -> tools.executeTool("trading_status")
            lower.contains("اسکن بورس") || lower.contains("بورس ایران") -> tools.executeTool("scan_iran")
            lower.contains("اسکن کریپتو") -> tools.executeTool("scan_crypto")
            lower.contains("معامله خودکار") && (lower.contains("روشن") || lower.contains("فعال")) ->
                tools.executeTool("auto_on")
            lower.contains("معامله خودکار") && (lower.contains("خاموش") || lower.contains("وقف")) ->
                tools.executeTool("auto_off")
            lower.contains("کیل سوییچ") || lower.contains("kill switch") -> tools.executeTool("kill_switch")
            lower.contains("شنیدن مداوم") || lower.contains("گفتار مداوم") || lower.contains("صدا بدون محدودیت") -> {
                enableContinuousListening()
                "✅ شنیدن مداوم فعال شد."
            }
            lower.contains("توقف شنیدن") -> {
                disableContinuousListening()
                "🛑 شنیدن مداوم خاموش شد."
            }
            lower.contains("بک‌تست") && lower.contains("بورس") -> tools.backtestIran()
            lower.contains("بک‌تست") && (lower.contains("btc") || lower.contains("بیتکوین")) ->
                tools.backtestCryptoSymbol("BTCUSDT")
            lower.contains("بک‌تست") || lower.contains("backtest") -> tools.backtestCrypto()

            // یادگیری خودکار
            (lower.contains("یادگیری خودکار") || lower.contains("auto learn") || lower.contains("یادگیری اتوماتیک")) &&
            (lower.contains("روشن") || lower.contains("فعال") || lower.contains("on")) -> {
                setAutoLearn(true)
                "✅ یادگیری خودکار فعال شد.\nدانش، بازخورد و «یادت باشه» بدون دیالوگ ذخیره می‌شوند.\nنام کاربر همچنان نیاز به تأیید دارد."
            }
            (lower.contains("یادگیری خودکار") || lower.contains("auto learn") || lower.contains("یادگیری اتوماتیک")) &&
            (lower.contains("خاموش") || lower.contains("غیرفعال") || lower.contains("off")) -> {
                setAutoLearn(false)
                "🛑 یادگیری خودکار خاموش شد. همه ذخیره‌ها نیاز به تأیید شما دارند."
            }
            lower.contains("وضعیت یادگیری") || lower.contains("یادگیری خودکار؟") -> {
                if (_uiState.value.autoLearnEnabled)
                    "یادگیری خودکار: ✅ فعال"
                else
                    "یادگیری خودکار: 🛑 خاموش"
            }

            lower.contains("آماده‌سازی آموزش") || lower.contains("export training") -> {
                prepareTrainingData()
                "در حال آماده‌سازی داده آموزش مدل..."
            }
            lower.contains("پردازش صف") || lower.contains("صف آفلاین") -> {
                processOfflineQueueNow()
                "پردازش صف یادگیری آفلاین شروع شد."
            }
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
        if (_uiState.value.continuousListening) disableContinuousListening() else enableContinuousListening()
    }

    fun clearConversation() = _uiState.update {
        AuraUiState(
            continuousListening = it.continuousListening,
            networkOnline = it.networkOnline,
            autoLearnEnabled = it.autoLearnEnabled
        )
    }

    fun refreshNetworkStatus() {
        _uiState.update { it.copy(networkOnline = isNetworkAvailable()) }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
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
