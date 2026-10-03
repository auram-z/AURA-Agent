package com.aura.agent.core

import android.content.Context
import android.util.Log
import com.aura.agent.ai.AuraAiService
import com.aura.agent.ai.ModelConfig
import com.aura.agent.data.ChatMessage
import com.aura.agent.data.MemoryStore
import com.aura.agent.learning.KnowledgeStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * هسته کنترل‌پذیر و اختصاصی AURA.
 * وابستگی اجباری به API خارجی ندارد.
 */
class AuraCore(
    context: Context,
    private val memoryStore: MemoryStore,
    private val knowledgeStore: KnowledgeStore,
    private val aiService: AuraAiService = AuraAiService(),
    private val modelConfig: ModelConfig = ModelConfig(context)
) {

    companion object {
        private const val TAG = "AuraCore"
    }

    private val hardware = HardwareProfiler(context)

    private val local = LocalKnowledgeBackend(memoryStore, knowledgeStore)
    private val onDevice = OnDeviceBackend()
    private val remote = RemoteApiBackend(
        aiService = aiService,
        modelProvider = { modelConfig.getActiveModel() },
        onlineProvider = { hardware.profile().online }
    )

    private val _policy = MutableStateFlow(RuntimePolicy.HYBRID)
    val policy: StateFlow<RuntimePolicy> = _policy.asStateFlow()

    private val _lastBackend = MutableStateFlow(BackendKind.LOCAL)
    val lastBackend: StateFlow<BackendKind> = _lastBackend.asStateFlow()

    fun setPolicy(p: RuntimePolicy) {
        _policy.value = p
        Log.i(TAG, "Policy → $p")
    }

    fun hardwareSummary(): String = hardware.summary()

    fun statusReport(): String {
        val p = hardware.profile()
        return buildString {
            append("🌌 AURA Core Status\n")
            append("• سیاست: ${_policy.value}\n")
            append("• آخرین backend: ${_lastBackend.value}\n")
            append("• Local: ✅ همیشه\n")
            append("• OnDevice: ${if (onDevice.isAvailable()) "✅" else "⏳ مدل نصب نشده"}\n")
            append("• Remote: ${if (remote.isAvailable()) "✅ آماده" else "⚪ اختیاری / غیرفعال"}\n")
            append("\n")
            append(hardware.summary())
        }
    }

    /**
     * مسیریابی هوشمند بر اساس سیاست + سخت‌افزار + پیچیدگی سؤال.
     */
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
        for (kind in order) {
            val backend = backendFor(kind) ?: continue
            if (!backend.isAvailable()) continue
            val result = backend.generate(request)
            result.onSuccess { resp ->
                // اگر Local با confidence پایین بود و Remote در صف بعدی است، ادامه بده
                if (kind == BackendKind.LOCAL && resp.confidence < 0.7f && order.contains(BackendKind.REMOTE)) {
                    lastError = null
                    // keep as candidate but try remote
                    val remoteTry = remote.takeIf { it.isAvailable() }?.generate(request)
                    if (remoteTry?.isSuccess == true) {
                        val r = remoteTry.getOrThrow()
                        _lastBackend.value = r.backend
                        return r
                    }
                }
                _lastBackend.value = resp.backend
                return resp
            }.onFailure { lastError = it }
        }

        // آخرین پناه: Local حتی اگر قبلاً fail شده باشد
        val fallback = local.generate(request).getOrElse {
            InferenceResponse(
                text = "هسته محلی موقتاً پاسخ نداد: ${lastError?.message ?: it.message}",
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
        val complex = userText.length > 80 ||
            userText.contains("؟") || userText.contains("?") ||
            listOf("چرا", "چگونه", "توضیح", "کد", "تحلیل", "why", "how", "explain", "code")
                .any { userText.lowercase().contains(it) }

        return when (policy) {
            RuntimePolicy.LOCAL_ONLY -> listOf(
                BackendKind.ON_DEVICE,
                BackendKind.LOCAL
            )
            RuntimePolicy.HYBRID -> buildList {
                if (profile.canRunHeavyOnDevice && onDevice.isAvailable()) add(BackendKind.ON_DEVICE)
                add(BackendKind.LOCAL)
                if (profile.online && complex) add(BackendKind.REMOTE)
                else if (profile.online) add(BackendKind.REMOTE)
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
