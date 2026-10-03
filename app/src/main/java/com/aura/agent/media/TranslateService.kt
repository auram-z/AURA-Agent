package com.aura.agent.media

import com.aura.agent.BuildConfig
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

/**
 * ترجمه خودکار هر زبانی → فارسی با مدل AURA (xAI).
 */
class TranslateService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun toPersian(
        text: String,
        sourceHint: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext Result.failure(Exception("متن خالی است"))
        try {
            val apiKey = BuildConfig.AURA_API_KEY
            if (apiKey.isBlank()) {
                return@withContext Result.failure(Exception("AURA_API_KEY تنظیم نشده"))
            }
            val base = SecurityGuards.requireHttps(BuildConfig.AURA_BASE_URL).getOrElse {
                return@withContext Result.failure(it)
            }
            val model = BuildConfig.AURA_MODEL

            val src = sourceHint?.takeIf { it.isNotBlank() } ?: "هر زبان مبدأ"
            val system = """
شما مترجم حرفه‌ای هستید. فقط ترجمه روان و طبیعی به فارسی بنویسید.
زبان مبدأ: $src
قوانین:
- فقط متن ترجمه‌شده را برگردان (بدون توضیح اضافه)
- اصطلاحات را طبیعی ترجمه کن
- اگر متن از قبل فارسی است، همان را با اصلاح جزئی برگردان
- برای زیرنویس: جملات کوتاه و خوانا نگه دار
            """.trim()

            val body = json.encodeToString(
                ChatRequest(
                    model = model,
                    messages = listOf(
                        ApiMessage("system", system),
                        ApiMessage("user", text.take(12000))
                    ),
                    temperature = 0.2,
                    maxTokens = 4096
                )
            ).toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$base/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("خطای ترجمه (${response.code})"))
                }
                val parsed = json.decodeFromString<ChatResponse>(raw)
                val content = parsed.choices.firstOrNull()?.message?.content?.trim()
                    ?: return@withContext Result.failure(Exception("پاسخ خالی"))
                Result.success(content)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun translateSubtitleBlocks(blocks: List<SubtitleBlock>): Result<List<SubtitleBlock>> =
        withContext(Dispatchers.IO) {
            if (blocks.isEmpty()) return@withContext Result.success(emptyList())
            val joined = blocks.joinToString("\n") { b -> "[${b.index}] ${b.text}" }
            val translated = toPersian(
                "این خطوط زیرنویس را به فارسی ترجمه کن. شماره [n] را حفظ کن:\n$joined",
                sourceHint = "زیرنویس فیلم"
            ).getOrElse { return@withContext Result.failure(it) }

            val map = mutableMapOf<Int, String>()
            Regex("\\[(\\d+)\\]\\s*(.+)").findAll(translated).forEach { m ->
                val idx = m.groupValues[1].toIntOrNull() ?: return@forEach
                map[idx] = m.groupValues[2].trim()
            }
            if (map.isEmpty()) {
                return@withContext Result.success(
                    listOf(blocks.first().copy(text = translated, index = 1))
                )
            }
            Result.success(blocks.map { b -> b.copy(text = map[b.index] ?: b.text) })
        }

    @Serializable
    private data class ApiMessage(val role: String, val content: String)

    @Serializable
    private data class ChatRequest(
        val model: String,
        val messages: List<ApiMessage>,
        val temperature: Double = 0.2,
        @SerialName("max_tokens") val maxTokens: Int = 4096
    )

    @Serializable
    private data class ChatResponse(val choices: List<Choice>)

    @Serializable
    private data class Choice(val message: ApiMessage)
}

data class SubtitleBlock(
    val index: Int,
    val startMs: Long = 0,
    val endMs: Long = 0,
    val text: String
)
