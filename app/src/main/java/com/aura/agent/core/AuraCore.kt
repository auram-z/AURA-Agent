package com.aura.agent.core

import android.content.Context
import android.util.Log
import com.aura.agent.ai.AuraAiService
import com.aura.agent.ai.ModelConfig
import com.aura.agent.data.ChatMessage
import com.aura.agent.data.MemoryStore
import com.aura.agent.learning.KnowledgeStore
import com.aura.agent.security.SecureStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * هسته مرکزی AURA — دستیار فوق‌هوشمند چندگانه با هوش اختصاصی خود.
 *
 * اصل بنیادین: هیچ وابستگی اجباری به هیچ هوش مصنوعی خارجی وجود ندارد.
 * پیش‌فرض: LOCAL_ONLY. لایه Remote فقط وقتی کاربر صراحتاً انتخاب کند.
 */
class AuraCore(
    context: Context,
    private val memoryStore: MemoryStore,
    private val knowledgeStore: KnowledgeStore,
    private val aiService: AuraAiService = AuraAiService(context = context),
    private val modelConfig: ModelConfig = ModelConfig(context)
) {

    companion object {
        private const val TAG = "AuraCore"
        const val CORE_VERSION = "3.2.0-super-independent"
    }

    private val hardware = HardwareProfiler(context)
    private val secureStore = SecureStore(context)

    private val local = LocalKnowledgeBackend(memoryStore, knowledgeStore)
    private val onDevice = OnDeviceBackend()
    private val remote = RemoteApiBackend(
        aiService = aiService,
        modelProvider = { modelConfig.getActiveModel() },
        onlineProvider = { hardware.profile().online },
        keyAvailable = { secureStore.getString(SecureStore.KEY_API_OVERRIDE).isNotBlank() }
    )

    // استقلال اول: پیش‌فرض فقط هسته اختصاصی محلی
    private val _policy = MutableStateFlow(RuntimePolicy.LOCAL_ONLY)
    val policy: StateFlow<RuntimePolicy> = _policy.asStateFlow()

    private val _lastBackend = MutableStateFlow(BackendKind.LOCAL)
    val lastBackend: StateFlow<BackendKind> = _lastBackend.asStateFlow()

    fun setPolicy(p: RuntimePolicy) {
        _policy.value = p
        Log.i(TAG, "Policy → $p")
    }

    fun hardwareSummary(): String = hardware.summary()

    suspend fun statusReport(): String {
        val p = hardware.profile()
        val kbCount = runCatching { knowledgeStore.chunks.first().size }.getOrDefault(0)
        return buildString {
            append("🌌 AURA Super Independent Core\n")
            append("• نسخه هسته: $CORE_VERSION\n")
            append("• هویت: هوش اختصاصی AURA — بدون وابستگی اجباری به AI خارجی\n")
            append("• سیاست: ${_policy.value}\n")
            append("• آخرین backend: ${_lastBackend.value}\n")
            append("• Local (اختصاصی): ✅ همیشه فعال\n")
            append("• OnDevice: ${if (onDevice.isAvailable()) "✅" else "⏳ مدل محلی نصب نشده"}\n")
            append("• Remote: ${if (remote.isAvailable()) "⚪ اختیاری (کلید موجود)" else "⚪ غیرفعال"}\n")
            append("• پایگاه دانش: $kbCount تکه\n")
            append("\n")
            append(hardware.summary())
        }
    }

    suspend fun infer(
        history: List<ChatMessage>,
        userText: String,
        systemContext: String = ""
    ): InferenceResponse {
        val profile = hardware.profile()
        val policy = _policy.value
        val request = InferenceRequest(
            history = history,
            userText = userText,
            systemContext = systemContext,
            preferOffline = !profile.online || policy == RuntimePolicy.LOCAL_ONLY
        )

        val order = resolveOrder(policy, profile, userText)
        Log.i(TAG, "Route order: $order for policy=$policy online=${profile.online}")

        var lastError: Throwable? = null
        var bestLocal: InferenceResponse? = null

        for (kind in order) {
            val backend = backendFor(kind) ?: continue
            if (!backend.isAvailable()) continue
            val result = backend.generate(request)
            result.onSuccess { resp ->
                if (kind == BackendKind.LOCAL) {
                    bestLocal = resp
                    if (resp.confidence >= 0.7f || policy == RuntimePolicy.LOCAL_ONLY) {
                        _lastBackend.value = resp.backend
                        return resp
                    }
                    return@onSuccess
                }
                _lastBackend.value = resp.backend
                return resp
            }.onFailure { lastError = it }
        }

        bestLocal?.let {
            _lastBackend.value = BackendKind.LOCAL
            return it
        }

        val fallback = local.generate(request).getOrElse {
            InferenceResponse(
                text = "هسته مستقل موقتاً پاسخ نداد: ${lastError?.message ?: it.message}",
                backend = BackendKind.LOCAL,
                offline = true,
                confidence = 0.1f
            )
        }
        _lastBackend.value = BackendKind.LOCAL
        return fallback
    }

    private fun resolveOrder(
        policy: RuntimePolicy,
        profile: HardwareProfiler.Profile,
        userText: String
    ): List<BackendKind> {
        return when (policy) {
            RuntimePolicy.LOCAL_ONLY -> listOf(
                BackendKind.ON_DEVICE,
                BackendKind.LOCAL
            )
            RuntimePolicy.HYBRID -> buildList {
                add(BackendKind.LOCAL)
                if (profile.canRunHeavyOnDevice && onDevice.isAvailable()) add(BackendKind.ON_DEVICE)
                if (profile.online) add(BackendKind.REMOTE)
            }
            RuntimePolicy.REMOTE_PREFERRED -> buildList {
                if (profile.online) add(BackendKind.REMOTE)
                if (onDevice.isAvailable()) add(BackendKind.ON_DEVICE)
                add(BackendKind.LOCAL)
            }
        }.distinct()
    }

    private fun backendFor(kind: BackendKind): InferenceBackend? = when (kind) {
        BackendKind.LOCAL -> local
        BackendKind.ON_DEVICE -> onDevice
        BackendKind.REMOTE -> remote
    }
}
