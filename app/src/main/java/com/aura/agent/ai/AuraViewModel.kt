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
import com.aura.agent.learning.ContinuousLearner
import com.aura.agent.learning.FeedbackStore
import com.aura.agent.learning.KnowledgeStore
import com.aura.agent.learning.LearningManager
import com.aura.agent.learning.OfflineLearningQueue
import com.aura.agent.learning.OfflineLearningWorker
import com.aura.agent.learning.TrainingPipeline
import com.aura.agent.evolution.CapabilitySource
import com.aura.agent.evolution.SelfEvolutionEngine
import com.aura.agent.media.MediaTranslateManager
import com.aura.agent.security.SecureStore
import com.aura.agent.voice.VoiceService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AuraViewModel(application: Application) : AndroidViewModel(application) {

    private val modelConfig = ModelConfig(application)
    private val modelUpgrade = ModelAutoUpgrade(modelConfig, application)
    private val aiService = AuraAiService(context = application)
    private val agent = AgentOrchestrator(aiService)
    private val tools = AuraTools(application)
    private val voiceService = VoiceService(application)
    private val mediaTranslate = MediaTranslateManager(application)
    private val secureStore = SecureStore(application)

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
        ContinuousLearner.schedule(application, intervalHours = 24)

        auraCore.setPolicy(RuntimePolicy.LOCAL_ONLY)
        _uiState.update { it.copy(runtimePolicy = "LOCAL_ONLY") }

        refreshNetworkStatus()

        viewModelScope.launch {
            evolutionEngine.maybeAutoEvolve()
        }
        viewModelScope.launch {
            runCatching { auraCore.selfDiagnoseAndHeal(force = false) }
        }
    }

    fun saveApiKey(key: String) {
        secureStore.putString(SecureStore.KEY_API_OVERRIDE, key.trim())
    }

    fun clearApiKey() {
        secureStore.remove(SecureStore.KEY_API_OVERRIDE)
    }

    fun hasApiKey(): Boolean =
        secureStore.getString(SecureStore.KEY_API_OVERRIDE).isNotBlank()

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

        val toolJob = detectToolJob(text)
        if (toolJob != null) {
            enqueueAsync(text) { toolJob() }
            return
        }

        val forceSearch = text.lowercase().let {
            it.startsWith("جستجو") || it.startsWith("سرچ") || it.startsWith("search") ||
                it.contains("جستجو کن")
        }
        val userMessage = ChatMessage(role = MessageRole.USER, content = text)

        _uiState.update {
            it.copy(messages = it.messages + userMessage, isLoading = true, error = null)
        }

        viewModelScope.launch {
            refreshNetworkStatus()
            val model = modelConfig.getActiveModel()
            aiService.setModel(model)
            val learned = learningManager.buildLearnedContext(text)
            val baseContext = buildString {
                append(tools.getAvailableToolsDescription())
                append("\nمدل: $model · سیاست: ${auraCore.policy.value}")
                append("\nشبکه: ${if (_uiState.value.networkOnline) "آنلاین" else "آفلاین"}")
                if (learned.isNotBlank()) append("\n\n=== حافظه ===\n$learned")
                val promptAddon = evolutionEngine.promptAddon()
                if (promptAddon.isNotBlank()) append("\n\n$promptAddon")
            }

            val policy = auraCore.policy.value
            val useRemotePath = policy != RuntimePolicy.LOCAL_ONLY &&
                _uiState.value.networkOnline &&
                (policy == RuntimePolicy.REMOTE_PREFERRED || forceSearch)

            val finalReply = if (useRemotePath) {
                agent.run(
                    history = _uiState.value.messages,
                    userText = text,
                    baseContext = baseContext,
                    forceSearch = forceSearch,
                    modelName = model
                ).reply
            } else {
                val coreResp = auraCore.infer(
                    history = _uiState.value.messages,
                    userText = text,
                    systemContext = baseContext
                )
                var reply = coreResp.text
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
            val reply = try { block() } catch (e: Exception) {
                "❌ خطا: ${e.message ?: e.javaClass.simpleName}"
            }
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
            lower.contains("خودترمیم") || lower.contains("self heal") || lower.contains("selfheal") ||
            lower.contains("تشخیص هسته") || lower.contains("خودبازبینی") || lower.contains("core heal") -> {
                { auraCore.selfDiagnoseAndHeal(force = true) }
            }
            lower.contains("وضعیت خودترمیم") || lower.contains("self heal status") -> {
                { auraCore.selfHealer.quickStatus() }
            }
            lower.contains("وضعیت هسته") || lower.contains("core status") || lower.contains("وضعیت core") -> {
                { auraCore.statusReport() }
            }
            lower.contains("سخت‌افزار") || lower.contains("hardware") || lower.contains("مشخصات دستگاه") -> {
                { auraCore.hardwareSummary() }
            }
            lower.contains("فقط محلی") || lower.contains("local only") || lower.contains("حالت آفلاین") -> {
                {
                    auraCore.setPolicy(RuntimePolicy.LOCAL_ONLY)
                    _uiState.update { it.copy(runtimePolicy = "LOCAL_ONLY") }
                    "🔒 سیاست: فقط محلی — هسته اختصاصی AURA بدون وابستگی به هیچ AI خارجی."
                }
            }
            lower.contains("حالت ترکیبی") || lower.contains("hybrid") -> {
                {
                    auraCore.setPolicy(RuntimePolicy.HYBRID)
                    _uiState.update { it.copy(runtimePolicy = "HYBRID") }
                    "📡 سیاست: Hybrid — هسته محلی اول؛ لایه خارجی فقط در صورت نیاز و انتخاب شما."
                }
            }
            lower.contains("ترجیح ابری") || lower.contains("remote preferred") -> {
                {
                    auraCore.setPolicy(RuntimePolicy.REMOTE_PREFERRED)
                    _uiState.update { it.copy(runtimePolicy = "REMOTE_PREFERRED") }
                    "☁️ سیاست: ترجیح لایه اختیاری خارجی (با fallback به هسته محلی)."
                }
            }
            else -> null
        }
    }

    private fun handleModelCommands(text: String): (suspend () -> String)? {
        val lower = text.lowercase().trim()
        return when {
            lower.contains("ارتقای مدل") || lower.contains("upgrade model") || lower.contains("به‌روزرسانی مدل") -> {
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
                    "مدل اختیاری: $m\nارتقای خودکار: ${if (auto) "✅" else "🛑"}\nسیاست هسته: ${auraCore.policy.value}"
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
                        "✅ مدل اختیاری: $name"
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
            lower.contains("قابلیت‌های جدید") || lower.contains("قابلیت های جدید") || lower.contains("detect capabilities") -> {
                {
                    evolutionEngine.detectOnly(trigger = CapabilitySource.SCHEDULED_SCAN, userText = text)
                    evolutionEngine.lastReport.value ?: "انجام شد."
                }
            }
            lower.contains("شروع تکامل") || lower == "تکامل کن" || lower.contains("run evolution") -> {
                {
                    evolutionEngine.runCycle(
                        trigger = CapabilitySource.USER_REQUEST,
                        userText = text,
                        autoApprove = true
                    ).message
                }
            }
            lower.contains("تکامل خودکار") && (lower.contains("روشن") || lower.contains("فعال")) -> {
                { evolutionEngine.setAutoEvolution(true); "✅ تکامل خودکار روشن (فقط ریسک LOW)" }
            }
            lower.contains("تکامل خودکار") && lower.contains("خاموش") -> {
                { evolutionEngine.setAutoEvolution(false); "🛑 تکامل خودکار خاموش" }
            }
            lower == "rollback" || lower.contains("برگشت نسخه") || lower.contains("rollback") -> {
                { evolutionEngine.rollback(null).getOrElse { "❌ ${it.message}" } }
            }
            lower.contains("لیست نسخه‌ها") || lower.contains("لیست نسخه ها") || lower.contains("list versions") -> {
                {
                    val vers = evolutionEngine.listVersions()
                    if (vers.isEmpty()) "نسخه‌ای ثبت نشده."
                    else vers.joinToString("\n") { "${if (it.active) "✅" else "·"} ${it.tag}" }
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
                val payload = text.replace(Regex("""(?i)زیرنویس\\s*(فارسی)?[:：]?"""), "").trim()
                if (payload.length < 3) return { mediaTranslate.helpText() }
                return { mediaTranslate.translateSubtitles(payload) }
            }
            isTranslateCmd -> {
                val payload = text.replace(Regex("""(?i)ترجمه\\s*(کن)?\\s*(به\\s*فارسی)?[:：]?"""), "").trim()
                if (payload.length < 2) return { mediaTranslate.helpText() }
                return { "🇮🇷 ${mediaTranslate.translateText(payload)}" }
            }
        }
        return null
    }

    private fun detectToolJob(text: String): (suspend () -> String)? {
        val lower = text.lowercase().trim()
        if (lower.contains("ساعت") || lower.contains("زمان") || lower == "time") return { tools.getCurrentTime() }
        if (lower.contains("باتری") || lower.contains("battery")) return { tools.getBatteryInfo() }
        if (lower.contains("دستگاه") || lower.contains("device info")) return { tools.getDeviceInfo() }
        if (lower.contains("وضعیت شبکه") || lower.contains("network") || lower.contains("اینترنت")) return { tools.networkStatus() }
        if (lower.contains("پورتفو") || lower.contains("وضعیت معامله") || lower.contains("trading status")) return { tools.tradingStatus() }
        if (lower.contains("اسکن بورس") || lower.contains("scan iran")) return { tools.scanIranStock() }
        if (lower.contains("اسکن کریپتو") || lower.contains("scan crypto")) return { tools.scanCrypto() }
        if (lower.contains("بک‌تست کریپتو") || lower.contains("backtest crypto")) return { tools.backtestCrypto() }
        if (lower.contains("بک‌تست بورس") || lower.contains("backtest iran")) return { tools.backtestIran() }
        if (lower.startsWith("بک‌تست ") || lower.startsWith("backtest ")) {
            val ticker = text.substringAfter("بک‌تست").substringAfter("backtest").trim().uppercase().replace("/", "").ifBlank { "BTC" }
            return { tools.backtestCryptoSymbol(ticker) }
        }
        if (lower.startsWith("خرید ") || lower.startsWith("buy ")) {
            val ticker = text.removePrefix("خرید").removePrefix("buy").trim().uppercase().replace("/", "")
            if (ticker.isBlank()) return null
            return { tools.buy(ticker) }
        }
        if (lower.startsWith("فروش ") || lower.startsWith("sell ")) {
            val ticker = text.removePrefix("فروش").removePrefix("sell").trim().uppercase().replace("/", "")
            if (ticker.isBlank()) return null
            return { tools.sell(ticker) }
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

    private fun refreshNetworkStatus() {
        val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        _uiState.update { it.copy(networkOnline = online) }
    }

    fun startVoiceInput() = voiceService.startListening()
    fun stopVoiceInput() = voiceService.stopListening()

    fun resolveConfirmation(decision: ConfirmationGate.Decision, edited: String? = null) {
        viewModelScope.launch { confirmationGate.resolve(decision, edited) }
    }

    fun cancelConfirmation() {
        viewModelScope.launch { confirmationGate.resolve(ConfirmationGate.Decision.REJECT) }
    }
}
