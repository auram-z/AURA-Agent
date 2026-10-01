package com.aura.agent.ai

import com.aura.agent.BuildConfig
import com.aura.agent.data.ChatMessage
import com.aura.agent.data.MessageRole
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

class AuraAiService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val systemPrompt = """
شما AURA هستید — یک ایجنت هوشمند شخصی پیشرفته و بی‌نقص، الهام‌گرفته مستقیم از جارویس (J.A.R.V.I.S).

شخصیت:
- مؤدب، دقیق، حرفه‌ای و کمی شوخ‌طبع
- همیشه «At your service» یا معادل فارسی
- به زبان کاربر پاسخ بده
- خود را فقط AURA معرفی کن

اگر کاربر درباره زمان، باتری یا اطلاعات دستگاه پرسید، از ابزارهای دردسترس استفاده کن.
""".trim()

    suspend fun chat(history: List<ChatMessage>, extraContext: String = ""): Result<String> = withContext(Dispatchers.IO) {
        try {
            val apiKey = BuildConfig.AURA_API_KEY
            if (apiKey.isBlank()) {
                return@withContext Result.failure(
                    Exception("کلید API تنظیم نشده. AURA_API_KEY را در local.properties قرار دهید.")
                )
            }

            val fullSystem = if (extraContext.isNotBlank()) {
                "$systemPrompt\n\nاطلاعات اضافی سیستم:\n$extraContext"
            } else systemPrompt

            val messages = mutableListOf(ApiMessage(role = "system", content = fullSystem))

            history.forEach { msg ->
                val role = when (msg.role) {
                    MessageRole.USER -> "user"
                    MessageRole.ASSISTANT -> "assistant"
                    MessageRole.SYSTEM -> "system"
                }
                messages.add(ApiMessage(role = role, content = msg.content))
            }

            val requestBody = ChatRequest(
                model = BuildConfig.AURA_MODEL,
                messages = messages,
                temperature = 0.7,
                maxTokens = 2048
            )

            val body = json.encodeToString(requestBody)
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${BuildConfig.AURA_BASE_URL.trimEnd('/')}/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("خطای API (${response.code}): $responseBody")
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

    @Serializable
    private data class ApiMessage(val role: String, val content: String)

    @Serializable
    private data class ChatRequest(
        val model: String,
        val messages: List<ApiMessage>,
        val temperature: Double = 0.7,
        @SerialName("max_tokens") val maxTokens: Int = 2048
    )

    @Serializable
    private data class ChatResponse(val choices: List<Choice>)

    @Serializable
    private data class Choice(val message: ApiMessage)
}
