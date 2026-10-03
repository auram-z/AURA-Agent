package com.aura.agent.core

/**
 * جای‌نگهدار مدل روی دستگاه (GGUF / MediaPipe / MLCLLM).
 * تا وقتی مدل محلی نصب نشده، unavailable است.
 */
class OnDeviceBackend(
    private val modelPathProvider: () -> String? = { null }
) : InferenceBackend {

    override val kind = BackendKind.ON_DEVICE
    override val displayName = "AURA On-Device LLM"

    override fun isAvailable(): Boolean {
        val path = modelPathProvider()
        return !path.isNullOrBlank()
    }

    override suspend fun generate(request: InferenceRequest): Result<InferenceResponse> {
        if (!isAvailable()) {
            return Result.failure(
                IllegalStateException(
                    "مدل روی‌دستگاه نصب نشده. فایل GGUF را در مسیر مدل قرار دهید یا از Local/Remote استفاده کنید."
                )
            )
        }
        // فاز بعدی: بارگذاری llama.cpp / MediaPipe و inference
        return Result.success(
            InferenceResponse(
                text = "[OnDevice stub] مدل محلی شناسایی شد اما موتور inference هنوز وصل نشده است.",
                backend = BackendKind.ON_DEVICE,
                offline = true,
                confidence = 0.3f
            )
        )
    }
}
