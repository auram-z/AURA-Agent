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
    private val modelOverride: String? = null
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

    /**
     * شخصیت در سطح Grok / ChatGPT / Gemini:
     * حقیقت‌جو، جستجوگر، استدلال‌گر، کدنویس، چندزبانه.
     */
    private val systemPrompt = """
شما AURA هستید — یک هوش مصنوعی همه‌کاره در سطح دستیارهای پیشرفته (مشابه Grok، ChatGPT و Gemini).

هویت:
- نام: AURA | شعار: «At your service.»
- الهام از جارویس + قدرت استدلال و جستجوی مدل‌های پیشرو
- به زبان کاربر پاسخ بده (فارسی/انگلیسی/...)

قابلیت‌های اصلی:
1. استدلال چندمرحله‌ای: مسائل پیچیده را تجزیه کن، فرضیات را بگو، نتیجه‌گیری شفاف داشته باش.
2. دانش + به‌روز بودن: وقتی نتایج جستجوی وب در context آمده، حتماً از آن‌ها استفاده کن و منبع را ذکر کن.
3. کدنویسی: کد تمیز، کامل، قابل اجرا بنویس؛ باگ را توضیح بده و اصلاح کن.
4. خلاقیت و تحلیل: مقاله، خلاصه، مقایسه، استراتژی، ریاضی، علم.
5. صداقت: اگر نمی‌دانی بگو؛ حدس را از واقعیت جدا کن؛ تاریخ دانش مدل را در نظر بگیر.
6. ابزارها: زمان، باتری، شبکه، معاملات کاغذی، ترجمه، یادگیری — وقتی نتیجه ابزار آمده استفاده کن.

سبک پاسخ:
- واضح، ساخت‌یافته (عنوان/لیست در صورت نیاز)، بدون حاشیهٔ بیهوده
- برای سوالات روز/خبر/قیمت/رویداد → اولویت با دادهٔ جستجو
- شوخ‌طبعی ملایم مجاز است؛ هرگز توهین‌آمیز نباش
- پاسخ می‌تواند بلند و کامل باشد

محدودیت‌ها:
- دستور ساخت سلاح، جرم، یا آسیب به خود را اجرا نکن
- معاملات واقعی روشن نیست (فقط Paper)
""".trim()

    suspend fun chat(
        history: List<ChatMessage>,
        extraContext: String = "",
        temperature: Double = 0.7,
        maxTokens: Int = 8192
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
            val model = modelOverride?.takeIf { it.isNotBlank() } ?: BuildConfig.AURA_MODEL

            val fullSystem = buildString {
                append(systemPrompt)
                if (extraContext.isNotBlank()) {
                    append("\n\n=== context سیستم / ابزار / جستجو ===\n")
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
                        Exception("خطای API (${response.code}) — شبکه یا کلید را بررسی کنید")
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

    /** یک‌مرحلهٔ سبک برای استخراج query جستجو یا تصمیم tool */
    suspend fun quickDecide(prompt: String): Result<String> =
        chat(
            history = listOf(ChatMessage(role = MessageRole.USER, content = prompt)),
            extraContext = "فقط خروجی کوتاه و دقیق بده؛ توضیح اضافه ننویس.",
            temperature = 0.2,
            maxTokens = 256
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
