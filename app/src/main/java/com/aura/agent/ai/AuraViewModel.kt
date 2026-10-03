package com.aura.agent.ai

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
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
import com.aura.agent.media.MediaTranslateManager
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
    private val mediaTranslate = MediaTranslateManager(application)

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

        // مسیر ترجمه/زیرنویس (async)
        val mediaJob = detectMediaTranslate(text)
        if (mediaJob != null) {
            val userMessage = ChatMessage(role = MessageRole.USER, content = text)
            _uiState.update {
                it.copy(messages = it.messages + userMessage, isLoading = true, error = null)
            }
            viewModelScope.launch {
                val result = mediaJob()
                val assistantMessage = ChatMessage(role = MessageRole.ASSISTANT, content = result)
                _uiState.update {
                    it.copy(messages = it.messages + assistantMessage, isLoading = false)
                }
                voiceService.speak(result.take(800))
            }
            return
        }

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
                append("\n\n")
                append(mediaTranslate.helpText())
                append("\n\nوضعیت شبکه: ${if (_uiState.value.networkOnline) "متصل" else "آفلاین"}")
                if (learned.isNotBlank()) {
                    append("\n\n=== حافظه ===\n$learned")
                }
                if (toolResult != null) append("\n\nنتیجه ابزار:\n$toolResult")
            }

            val result = aiService.chat(_uiState.value.messages, extraContext)
            result.fold(
                onSuccess = { reply ->
                    val finalReply = when {
                        toolResult != null && reply.length < 40 -> toolResult
                        toolResult != null -> "$toolResult\n\n$reply"
                        else -> reply
                    }
                    val assistantMessage = ChatMessage(role = MessageRole.ASSISTANT, content = finalReply)
                    _uiState.update {
                        it.copy(messages = it.messages + assistantMessage, isLoading = false)
                    }
                    voiceService.speak(finalReply)
                    learningManager.analyzeAndPropose(userMessage, assistantMessage, _uiState.value.networkOnline)
                },
                onFailure = { error ->
                    val msg = toolResult ?: "خطا: ${error.message}"
                    _uiState.update {
                        it.copy(
                            messages = it.messages + ChatMessage(role = MessageRole.ASSISTANT, content = msg),
                            isLoading = false,
                            error = error.message
                        )
                    }
                    voiceService.speak(msg.take(500))
                }
            )
        }
    }

    /** ترجمه عکس از گالری */
    fun translateImageUri(uri: Uri) {
        val userMessage = ChatMessage(role = MessageRole.USER, content = "📷 ترجمه تصویر به فارسی")
        _uiState.update {
            it.copy(messages = it.messages + userMessage, isLoading = true)
        }
        viewModelScope.launch {
            val result = mediaTranslate.translateImage(uri)
            _uiState.update {
                it.copy(
                    messages = it.messages + ChatMessage(role = MessageRole.ASSISTANT, content = result),
                    isLoading = false
                )
            }
            voiceService.speak(result.take(600))
        }
    }

    fun translateBitmap(bitmap: Bitmap) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = mediaTranslate.translateImageBitmap(bitmap)
            _uiState.update {
                it.copy(
                    messages = it.messages + ChatMessage(role = MessageRole.ASSISTANT, content = result),
                    isLoading = false
                )
            }
        }
    }

    private fun detectMediaTranslate(text: String): (suspend () -> String)? {
        val lower = text.lowercase().trim()
        val isTranslateCmd = lower.startsWith("ترجمه") || lower.startsWith("translate") ||
            lower.contains("ترجمه کن") || lower.contains("به فارسی")
        val isSubCmd = lower.contains("زیرنویس") || lower.contains("subtitle") || lower.contains("srt")

        when {
            isSubCmd && (lower.contains("کمک") || lower.contains("راهنما") || text.length < 15) ->
                return { mediaTranslate.helpText() }

            isSubCmd -> {
                val payload = text
                    .replace(Regex("(?i)زیرنویس\\s*(فارسی)?[:：]?"), "")
                    .replace(Regex("(?i)subtitle[:：]?"), "")
                    .trim()
                if (payload.length < 3) return { mediaTranslate.helpText() }
                return { mediaTranslate.translateSubtitles(payload) }
            }

            isTranslateCmd -> {
                val payload = text
                    .replace(Regex("(?i)ترجمه\\s*(کن)?\\s*(به\\s*فارسی)?[:：]?"), "")
                    .replace(Regex("(?i)translate\\s*(to\\s*persian|to\\s*farsi)?[:：]?"), "")
                    .trim()
                if (payload.length < 2) return { mediaTranslate.helpText() }
                if (payload.contains("-->") || payload.contains("WEBVTT")) {
                    return { mediaTranslate.translateSubtitles(payload) }
                }
                return { "🇮🇷 ${mediaTranslate.translateText(payload)}" }
            }

            lower == "ترجمه عکس" || lower == "عکس ترجمه" || lower.contains("ترجمه تصویر") ->
                return {
                    "برای ترجمه عکس، از دکمه 📷 استفاده کنید یا تصویر را از گالری انتخاب کنید."
                }
        }
        return null
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

    fun cancelConfirmation() = confirmationGate.cancel()

    private fun detectAndRunTool(text: String): String? {
        val lower = text.lowercase().trim()
        return when {
            lower.contains("زمان") || lower.contains("ساعت") -> tools.executeTool("get_time")
            lower.contains("باتری") -> tools.executeTool("get_battery")
            lower.contains("دستگاه") || lower.contains("مدل گوشی") -> tools.executeTool("get_device_info")
            lower.contains("وضعیت شبکه") || lower.contains("اینترنت") -> tools.networkStatus()
            lower.contains("وضعیت معاملات") || lower.contains("پورتفو") -> tools.executeTool("trading_status")
            lower.contains("اسکن بورس") -> tools.executeTool("scan_iran")
            lower.contains("اسکن کریپتو") -> tools.executeTool("scan_crypto")
            lower.contains("معامله خودکار") && (lower.contains("روشن") || lower.contains("فعال")) ->
                tools.executeTool("auto_on")
            lower.contains("معامله خودکار") && (lower.contains("خاموش") || lower.contains("وقف")) ->
                tools.executeTool("auto_off")
            lower.contains("کیل سوییچ") -> tools.executeTool("kill_switch")
            lower.contains("شنیدن مداوم") || lower.contains("گفتار مداوم") -> {
                enableContinuousListening(); "✅ شنیدن مداوم فعال شد."
            }
            lower.contains("توقف شنیدن") -> {
                disableContinuousListening(); "🛑 شنیدن مداوم خاموش شد."
            }
            lower.contains("بک‌تست") && lower.contains("بورس") -> tools.backtestIran()
            lower.contains("بک‌تست") || lower.contains("backtest") -> tools.backtestCrypto()
            (lower.contains("یادگیری خودکار")) && (lower.contains("روشن") || lower.contains("فعال")) -> {
                setAutoLearn(true); "✅ یادگیری خودکار فعال شد."
            }
            (lower.contains("یادگیری خودکار")) && (lower.contains("خاموش")) -> {
                setAutoLearn(false); "🛑 یادگیری خودکار خاموش شد."
            }
            lower.contains("وضعیت یادگیری") ->
                if (_uiState.value.autoLearnEnabled) "یادگیری خودکار: ✅ فعال" else "یادگیری خودکار: 🛑 خاموش"
            lower.contains("آماده‌سازی آموزش") -> {
                prepareTrainingData(); "در حال آماده‌سازی داده آموزش..."
            }
            lower.contains("پردازش صف") -> {
                processOfflineQueueNow(); "پردازش صف آفلاین شروع شد."
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
