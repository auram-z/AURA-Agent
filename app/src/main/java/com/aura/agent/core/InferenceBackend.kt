package com.aura.agent.core

import com.aura.agent.data.ChatMessage

enum class BackendKind {
    LOCAL,      // قوانین + دانش روی دستگاه — همیشه در دسترس
    ON_DEVICE,  // مدل محلی (آینده: GGUF / MediaPipe)
    REMOTE      // API ابری اختیاری
}

data class InferenceRequest(
    val history: List<ChatMessage>,
    val userText: String,
    val systemContext: String = "",
    val preferOffline: Boolean = false
)

data class InferenceResponse(
    val text: String,
    val backend: BackendKind,
    val offline: Boolean,
    val confidence: Float = 1f
)

interface InferenceBackend {
    val kind: BackendKind
    val displayName: String
    fun isAvailable(): Boolean
    suspend fun generate(request: InferenceRequest): Result<InferenceResponse>
}

enum class RuntimePolicy {
    /** فقط محلی — بدون هیچ API خارجی */
    LOCAL_ONLY,
    /** محلی اول؛ ابری فقط وقتی محلی کافی نیست و آنلاین هستیم */
    HYBRID,
    /** ترجیح ابری در صورت وجود کلید و شبکه */
    REMOTE_PREFERRED
}
