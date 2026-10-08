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
import com.aura.agent.core.AuraCore
import com.aura.agent.core.BackendKind
import com.aura.agent.core.RuntimePolicy
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
import com.aura.agent.evolution.CapabilitySource
import com.aura.agent.evolution.SelfEvolutionEngine
import com.aura.agent.media.MediaTranslateManager
import com.aura.agent.voice.VoiceService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AuraViewModel(application: Application) : AndroidViewModel(application) {

    private val modelConfig = ModelConfig(application)
    private val modelUpgrade = ModelAutoUpgrade(modelConfig)
    private val aiService = AuraAiService()
    private val agent = AgentOrchestrator(aiService)
    private val tools = AuraTools(application)
    private val voiceService = VoiceService(application)
    private val mediaTranslate = MediaTranslateManager(application)

    private val memoryStore = MemoryStore(application)
    private val feedbackStore = FeedbackStore(application)
    private val knowledgeStore = KnowledgeStore(application)
    private val offlineQueue = OfflineLearningQueue(application)
    private val confirmationGate = ConfirmationGate()

    private val auraCore = AuraCore(
        context = application,
        memoryStore = memoryStore,
        knowledgeStore = knowledgeStore,
        aiService = aiService,
        modelConfig = modelConfig
    )

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

    private val evolutionEngine = SelfEvolutionEngine(
        context = application,
        feedbackStore = feedbackStore,
        knowledgeStore = knowledgeStore,
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
        viewModelScope.launch {
            modelConfig.activeModel.collect { model ->
                aiService.setModel(model)
                _uiState.update { it.copy(activeModel = model) }
            }
        }
        viewModelScope.launch {
            auraCore.lastBackend.collect { backend ->
                _uiState.update { it.copy(activeBackend = backend.name) }
            }
        }

        viewModelScope.launch {
            val result = modelUpgrade.checkAndUpgrade(force = false)
            if (result.changed) {
                _uiState.update {
                    it.copy(activeModel = result.activeModel, lastTrainingReport = result.message)
                }
            }
        }

        OfflineLearningWorker.schedule(application)
        refreshNetworkStatus()

        // E6: یک تلاش تکامل خودکار برای قابلیت‌های LOW (اگر روشن باشد)
        viewModelScope.launch {
            evolutionEngine.maybeAutoEvolve()
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        val mediaJob = detectMediaTranslate(text)
        if (mediaJob != null) {
            enqueueAsync(text) { mediaJob() }
            return
        }

        val coreCmd = handleCoreCommands(text)
        if (coreCmd != null) {
            enqueueAsync(text) { coreCmd() }
            return
        }

        val modelCmd = handleModelCommands(text)
        if (modelCmd != null) {
            enqueueAsync(text) { modelCmd() }
            return
        }

        val evoCmd = handleEvolutionCommands(text)
        if (evoCmd != null) {
            enqueueAsync(text) { evoCmd() }
            return
        }

        val forceSearch = text.lowercase().let {
            it.startsWith("جستجو") || it.startsWith("سرچ") || it.startsWith("search") ||
                it.contains("جستجو کن")
        }
        val toolResult = detectAndRunTool(text)
        val userMessage = ChatMessage(role = MessageRole.USER, content = text)

        _uiState.update {
            it.copy(messages = it.messages + userMessage, isLoading = true, error = null)
        }

        viewModelScope.launch {
            refreshNetworkStatus()
            val model = modelConfig.getActiveModel()
            aiService.setModel(model)
            val learned = learningManager.buildLearnedContext()
            val baseContext = buildString {
                append(tools.getAvailableToolsDescription())
                append("\nمدل: $model · سیاست: ${auraCore.policy.value}")
                append("\nشبکه: ${if (_uiState.value.networkOnline) "آنلاین" else "آفلاین"}")
                if (learned.isNotBlank()) append("\n\n=== حافظه ===\n$learned")
                val promptAddon = evolutionEngine.promptAddon()
                if (promptAddon.isNotBlank()) append("\n\n$promptAddon")
                if (toolResult != null) append("\n\nنتیجه ابزار:\n$toolResult")
            }

            // هسته اختصاصی: مسیریابی Local / OnDevice / Remote
            val policy = auraCore.policy.value
            val useRemotePath = policy != RuntimePolicy.LOCAL_ONLY &&
                _uiState.value.networkOnline &&
                (policy == RuntimePolicy.REMOTE_PREFERRED || forceSearch)

            val finalReply = if (useRemotePath || (policy == RuntimePolicy.HYBRID && _uiState.value.networkOnline && forceSearch)) {
                val agentResult = agent.run(
                    history = _uiState.value.messages,
                    userText = text,
                    baseContext = baseContext,
                    forceSearch = forceSearch,
                    modelName = model
                )
                var reply = agentResult.reply
                if (toolResult != null && reply.length < 40) reply = toolResult
                else if (toolResult != null) reply = "$toolResult\n\n$reply"
                reply
            } else {
                val coreResp = auraCore.infer(
                    history = _uiState.value.messages,
                    userText = text,
                    systemContext = baseContext
                )
                var reply = coreResp.text
                if (toolResult != null && reply.length < 40) reply = toolResult
                else if (toolResult != null) reply = "$toolResult\n\n$reply"
                if (coreResp.backend != BackendKind.REMOTE) {
                    reply += "\n\n— ${coreResp.backend.name}" +
                        if (coreResp.offline) " · آفلاین" else ""
                }
                reply
            }

            val assistantMessage = ChatMessage(role = MessageRole.ASSISTANT, content = finalReply)
            _uiState.update {
                it.copy(messages = it.messages + assistantMessage, isLoading = false)
            }
            voiceService.speak(finalReply)
            learningManager.analyzeAndPropose(userMessage, assistantMessage, _uiState.value.networkOnline)
        }
    }

    private fun enqueueAsync(userText: String, block: suspend () -> String) {
        val userMessage = ChatMessage(role = MessageRole.USER, content = userText)
        _uiState.update {
            it.copy(messages = it.messages + userMessage, isLoading = true, error = null)
        }
        viewModelScope.launch {
            val reply = block()
            _uiState.update {
                it.copy(
                    messages = it.messages + ChatMessage(role = MessageRole.ASSISTANT, content = reply),
                    isLoading = false
                )
            }
            voiceService.speak(reply.take(600))
        }
    }

    private fun handleCoreCommands(text: String): (suspend () -> String)? {
        val lower = text.lowercase().trim()
        return when {
            lower.contains("وضعیت هسته") || lower.contains("core status") ||
            lower.contains("وضعیت core") -> {
                { auraCore.statusReport() }
            }
            lower.contains("سخت‌افزار") || lower.contains("hardware") || lower.contains("مشخصات دستگاه") -> {
                { auraCore.hardwareSummary() }
            }
            lower.contains("فقط محلی") || lower.contains("local only") || lower.contains("حالت آفلاین") -> {
                {
                    auraCore.setPolicy(RuntimePolicy.LOCAL_ONLY)
                    _uiState.update { it.copy(runtimePolicy = "LOCAL_ONLY") }
                    "🔒 سیاست: فقط محلی — بدون وابستگی به API خارجی."
                }
            }
            lower.contains("حالت ترکیبی") || lower.contains("hybrid") -> {
                {
                    auraCore.setPolicy(RuntimePolicy.HYBRID)
                    _uiState.update { it.copy(runtimePolicy = "HYBRID") }
                    "📡 سیاست: Hybrid — محلی اول، ابری در صورت نیاز."
                }
            }
            lower.contains("ترجیح ابری") || lower.contains("remote preferred") -> {
                {
                    auraCore.setPolicy(RuntimePolicy.REMOTE_PREFERRED)
                    _uiState.update { it.copy(runtimePolicy = "REMOTE_PREFERRED") }
                    "☁️ سیاست: ترجیح ابری (با fallback محلی)."
                }
            }
            else -> null
        }
    }

    private fun handleModelCommands(text: String): (suspend () -> String)? {
        val lower = text.lowercase().trim()
        return when {
            lower.contains("ارتقای مدل") || lower.contains("upgrade model") ||
            lower.contains("به‌روزرسانی مدل") -> {
                {
                    val r = modelUpgrade.checkAndUpgrade(force = true)
                    _uiState.update { it.copy(activeModel = r.activeModel) }
                    r.message
                }
            }
            lower.contains("مدل فعلی") || lower.contains("وضعیت مدل") -> {
                {
                    val m = modelConfig.getActiveModel()
                    val auto = modelConfig.isAutoUpgradeEnabled()
                    "مدل: $m\nارتقای خودکار: ${if (auto) "✅" else "🛑"}\nسیاست هسته: ${auraCore.policy.value}"
                }
            }
            lower.contains("ارتقای خودکار مدل") && (lower.contains("روشن") || lower.contains("فعال")) -> {
                { modelConfig.setAutoUpgrade(true); "✅ ارتقای خودکار مدل روشن" }
            }
            lower.contains("ارتقای خودکار مدل") && lower.contains("خاموش") -> {
                { modelConfig.setAutoUpgrade(false); "🛑 ارتقای خودکار خاموش" }
            }
            lower.startsWith("مدل ") && lower.length < 40 -> {
                {
                    val name = text.removePrefix("مدل ").removePrefix("مدل:").trim()
                    if (name.isBlank()) "مثال: مدل grok-4.7"
                    else {
                        modelConfig.setActiveModel(name)
                        aiService.setModel(name)
                        _uiState.update { it.copy(activeModel = name) }
                        "✅ مدل: $name"
                    }
                }
            }
            else -> null
        }
    }

    fun translateImageUri(uri: Uri) {
        enqueueAsync("📷 ترجمه تصویر") { mediaTranslate.translateImage(uri) }
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

    private fun handleEvolutionCommands(text: String): (suspend () -> String)? {
        val lower = text.lowercase().trim()
        return when {
            lower.contains("وضعیت تکامل") || lower.contains("evolution status") -> {
                { evolutionEngine.statusSummary() + "\n\n" + evolutionEngine.configStatus() }
            }
            lower.contains("قابلیت‌های جدید") || lower.contains("قابلیت های جدید") ||
            lower.contains("detect capabilities") -> {
                {
                    evolutionEngine.detectOnly(
                        trigger = CapabilitySource.SCHEDULED_SCAN,
                        userText = text
                    )
                    evolutionEngine.lastReport.value ?: "انجام شد."
                }
            }
            lower.contains("شروع تکامل") || lower == "تکامل کن" || lower.contains("run evolution") -> {
                {
                    val run = evolutionEngine.runCycle(
                        trigger = CapabilitySource.USER_REQUEST,
                        userText = text,
                        autoApprove = true
                    )
                    run.message
                }
            }
            lower.contains("تکامل خودکار") && (lower.contains("روشن") || lower.contains("فعال")) -> {
                { evolutionEngine.setAutoEvolution(true); "✅ تکامل خودکار روشن (فقط ریسک LOW)" }
            }
            lower.contains("تکامل خودکار") && lower.contains("خاموش") -> {
                { evolutionEngine.setAutoEvolution(false); "🛑 تکامل خودکار خاموش" }
            }
            lower.startsWith("rollback به") || lower.startsWith("rollback to") -> {
                {
                    val tag = text.substringAfter("به").substringAfter("to").trim()
                    evolutionEngine.rollback(tag.ifBlank { null }).getOrElse { "❌ ${it.message}" }
                }
            }
            lower == "rollback" || lower.contains("برگشت نسخه") || lower.contains("rollback") -> {
                {
                    evolutionEngine.rollback(null).getOrElse { "❌ ${it.message}" }
                }
            }
            lower.contains("لیست نسخه‌ها") || lower.contains("لیست نسخه ها") ||
            lower.contains("list versions") -> {
                {
                    val vers = evolutionEngine.listVersions()
                    if (vers.isEmpty()) "نسخه‌ای ثبت نشده."
                    else vers.joinToString("\n") {
                        val mark = if (it.active) "✅" else "·"
                        "$mark ${it.tag} (snap=${it.snapshotId.take(12)}…)"
                    }
                }
            }
            else -> null
        }
    }

    private fun detectMediaTranslate(text: String): (suspend () -> String)? {
        val lower = text.lowercase().trim()
        val isTranslateCmd = lower.startsWith("ترجمه") || lower.contains("ترجمه کن") || lower.contains("به فارسی")
        val isSubCmd = lower.contains("زیرنویس") || lower.contains("subtitle")
        when {
            isSubCmd && text.length < 15 -> return { mediaTranslate.helpText() }
            isSubCmd -> {
                val payload = text.replace(Regex("(?i)زیرنویس\\s*(فارسی)?[:：]?"), "").trim()
                if (payload.length < 3) return { mediaTranslate.helpText() }
                return { mediaTranslate.translateSubtitles(payload) }
            }
            isTranslateCmd -> {
                val payload = text.replace(Regex("(?i)ترجمه\\s*(کن)?\\s*(به\\s*فارسی)?[:：]?"), "").trim()
                if (payload.length < 2) return { mediaTranslate.helpText() }
                return { "🇮🇷 ${mediaTranslate.translateText(payload)}" }
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

    fun processOfflineQueueNow() = OfflineLearningWorker.runOnce(getApplication())

    fun resolveConfirmation(decision: ConfirmationGate.Decision, editedContent: String? = null) {
        confirmationGate.resolve(decision, editedContent)
    }

    fun cancelConfirmation() = confirmationGate.cancel()

    private fun detectAndRunTool(text: String): String? {
        val lower = text.lowercase().trim()
        return when {
            lower.contains("زمان") || lower.contains("ساعت") -> tools.executeTool("get_time")
            lower.contains("باتری") -> tools.executeTool("get_battery")
            lower.contains("دستگاه") && !lower.contains("سخت") -> tools.executeTool("get_device_info")
            lower.contains("وضعیت شبکه") || lower.contains("اینترنت") -> tools.networkStatus()
            lower.contains("وضعیت معاملات") || lower.contains("پورتفو") -> tools.executeTool("trading_status")
            lower.contains("اسکن بورس") -> tools.executeTool("scan_iran")
            lower.contains("اسکن کریپتو") -> tools.executeTool("scan_crypto")
            lower.contains("معامله خودکار") && (lower.contains("روشن") || lower.contains("فعال")) ->
                tools.executeTool("auto_on")
            lower.contains("معامله خودکار") && lower.contains("خاموش") -> tools.executeTool("auto_off")
            lower.contains("کیل سوییچ") -> tools.executeTool("kill_switch")
            lower.contains("شنیدن مداوم") -> { enableContinuousListening(); "✅ شنیدن مداوم" }
            lower.contains("توقف شنیدن") -> { disableContinuousListening(); "🛑 توقف شنیدن" }
            lower.contains("بک‌تست") || lower.contains("backtest") -> tools.backtestCrypto()
            lower.contains("یادگیری خودکار") && lower.contains("روشن") -> {
                setAutoLearn(true); "✅ یادگیری خودکار"
            }
            lower.contains("یادگیری خودکار") && lower.contains("خاموش") -> {
                setAutoLearn(false); "🛑 یادگیری خاموش"
            }
            lower.contains("آماده‌سازی آموزش") -> {
                prepareTrainingData(); "آماده‌سازی آموزش..."
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
            autoLearnEnabled = it.autoLearnEnabled,
            activeModel = it.activeModel,
            runtimePolicy = it.runtimePolicy,
            activeBackend = it.activeBackend
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
        evolutionEngine.cancel()
        voiceService.release()
    }
}
