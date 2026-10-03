package com.aura.agent.ai

import com.aura.agent.BuildConfig
import com.aura.agent.data.ChatMessage
import com.aura.agent.data.MessageRole
import com.aura.agent.security.SecurityGuards
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class AuraAiService(
    private val apiKeyOverride: String? = null,
    private val baseUrlOverride: String? = null,
    /** اگر null باشد از ModelConfig / BuildConfig خوانده می‌شود */
    private var modelOverride: String? = null
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .callTimeout(200, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun setModel(model: String) {
        modelOverride = model
    }

    private val systemPrompt = """
شما AURA هستید — هوش مصنوعی همه‌کاره در سطح دستیارهای پیشرفته (Grok / ChatGPT / Gemini).

هویت: AURA | «At your service.»
به زبان کاربر پاسخ بده.

قابلیت‌ها: استدلال چندمرحله‌ای، استفاده از نتایج جستجوی وب، کدنویسی، تحلیل، ترجمه، صداقت دربارهٔ ندانستن.
وقتی نتایج جستجو در context است حتماً استفاده کن و منبع را اشاره کن.
پاسخ می‌تواند کامل و ساخت‌یافته باشد.
معاملات فقط Paper. محتوای مضر نساز.
""".trim()

    suspend fun chat(
        history: List<ChatMessage>,
        extraContext: String = "",
        temperature: Double = 0.7,
        maxTokens: Int = 8192,
        modelName: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val apiKey = (apiKeyOverride?.takeIf { it.isNotBlank() } ?: BuildConfig.AURA_API_KEY)
            if (apiKey.isBlank()) {
                return@withContext Result.failure(
                    Exception("کلید API تنظیم نشده. AURA_API_KEY را در local.properties قرار دهید.")
                )
            }

            val rawBase = baseUrlOverride?.takeIf { it.isNotBlank() } ?: BuildConfig.AURA_BASE_URL
            val base = SecurityGuards.requireHttps(rawBase).getOrElse {
                return@withContext Result.failure(it)
            }

            val model = modelName?.takeIf { it.isNotBlank() }
                ?: modelOverride?.takeIf { it.isNotBlank() }
                ?: BuildConfig.AURA_MODEL.takeIf { it.isNotBlank() && it != "grok-beta" }
                ?: ModelConfig.FLAGSHIP_DEFAULT

            val fullSystem = buildString {
                append(systemPrompt)
                append("\nمدل فعال: $model")
                if (extraContext.isNotBlank()) {
                    append("\n\n=== context ===\n")
                    append(extraContext)
                }
            }

            val trimmedHistory = if (history.size > 80) history.takeLast(80) else history
            val messages = mutableListOf(ApiMessage(role = "system", content = fullSystem))
            trimmedHistory.forEach { msg ->
                val role = when (msg.role) {
                    MessageRole.USER -> "user"
                    MessageRole.ASSISTANT -> "assistant"
                    MessageRole.SYSTEM -> "system"
                }
                messages.add(ApiMessage(role = role, content = msg.content))
            }

            val requestBody = ChatRequest(
                model = model,
                messages = messages,
                temperature = temperature,
                maxTokens = maxTokens
            )

            val body = json.encodeToString(requestBody)
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$base/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("خطای API (${response.code}) مدل=$model")
                    )
                }
                val chatResponse = json.decodeFromString<ChatResponse>(responseBody)
                val content = chatResponse.choices.firstOrNull()?.message?.content
                    ?: return@withContext Result.failure(Exception("پاسخ خالی"))
                Result.success(content.trim())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun quickDecide(prompt: String, modelName: String? = null): Result<String> =
        chat(
            history = listOf(ChatMessage(role = MessageRole.USER, content = prompt)),
            extraContext = "فقط خروجی کوتاه.",
            temperature = 0.2,
            maxTokens = 256,
            modelName = modelName
        )

    @Serializable
    private data class ApiMessage(val role: String, val content: String)

    @Serializable
    private data class ChatRequest(
        val model: String,
        val messages: List<ApiMessage>,
        val temperature: Double = 0.7,
        @SerialName("max_tokens") val maxTokens: Int = 8192
    )

    @Serializable
    private data class ChatResponse(val choices: List<Choice>)

    @Serializable
    private data class Choice(val message: ApiMessage)
}
