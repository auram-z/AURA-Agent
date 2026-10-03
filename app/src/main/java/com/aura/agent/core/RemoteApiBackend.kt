package com.aura.agent.core

import com.aura.agent.BuildConfig
import com.aura.agent.ai.AuraAiService

/**
 * بک‌اند ابری اختیاری — فقط وقتی کلید و شبکه موجود باشد.
 * وابستگی اجباری نیست.
 */
class RemoteApiBackend(
    private val aiService: AuraAiService = AuraAiService(),
    private val modelProvider: suspend () -> String? = { null },
    private val onlineProvider: () -> Boolean = { true }
) : InferenceBackend {

    override val kind = BackendKind.REMOTE
    override val displayName = "Remote API (optional)"

    override fun isAvailable(): Boolean {
        return BuildConfig.AURA_API_KEY.isNotBlank() && onlineProvider()
    }

    override suspend fun generate(request: InferenceRequest): Result<InferenceResponse> {
        if (!isAvailable()) {
            return Result.failure(
                IllegalStateException("Remote در دسترس نیست (کلید یا شبکه).")
            )
        }
        val model = modelProvider()
        val result = aiService.chat(
            history = request.history,
            extraContext = request.systemContext,
            modelName = model
        )
        return result.map { text ->
            InferenceResponse(
                text = text,
                backend = BackendKind.REMOTE,
                offline = false,
                confidence = 0.95f
            )
        }
    }
}
