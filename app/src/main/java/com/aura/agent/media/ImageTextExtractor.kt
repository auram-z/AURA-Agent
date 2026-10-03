package com.aura.agent.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.aura.agent.BuildConfig
import com.aura.agent.security.SecurityGuards
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * استخراج متن از عکس (OCR / Vision) سپس آماده‌سازی برای ترجمه به فارسی.
 * از مدل vision در صورت پشتیبانی API استفاده می‌کند؛ در غیر این صورت راهنما برمی‌گرداند.
 */
class ImageTextExtractor(private val context: Context) {

    companion object {
        private const val TAG = "AuraImageOCR"
        private const val MAX_DIM = 1280
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun extractTextFromUri(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            val bitmap = loadBitmap(uri) ?: return@withContext Result.failure(
                Exception("نتوانستم تصویر را بخوانم")
            )
            extractTextFromBitmap(bitmap)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun extractTextFromBitmap(bitmap: Bitmap): Result<String> = withContext(Dispatchers.IO) {
        try {
            val scaled = scaleDown(bitmap)
            val b64 = bitmapToBase64Jpeg(scaled)
            visionExtract(b64)
        } catch (e: Exception) {
            Log.e(TAG, "OCR failed", e)
            Result.failure(e)
        }
    }

    private fun loadBitmap(uri: Uri): Bitmap? {
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream)
        }
    }

    private fun scaleDown(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val max = maxOf(w, h)
        if (max <= MAX_DIM) return src
        val scale = MAX_DIM.toFloat() / max
        return Bitmap.createScaledBitmap(src, (w * scale).toInt(), (h * scale).toInt(), true)
    }

    private fun bitmapToBase64Jpeg(bitmap: Bitmap): String {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    private fun visionExtract(base64Jpeg: String): Result<String> {
        val apiKey = BuildConfig.AURA_API_KEY
        if (apiKey.isBlank()) return Result.failure(Exception("AURA_API_KEY تنظیم نشده"))
        val base = SecurityGuards.requireHttps(BuildConfig.AURA_BASE_URL).getOrElse {
            return Result.failure(it)
        }

        // محتوای چندرسانه‌ای به سبک OpenAI-compatible
        val userContent = buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put(
                    "text",
                    "تمام متن قابل‌خواندن داخل این تصویر را استخراج کن. " +
                        "اگر متنی نیست، صحنه را کوتاه به فارسی توصیف کن. فقط خروجی متن."
                )
            })
            add(buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject {
                    put("url", "data:image/jpeg;base64,$base64Jpeg")
                })
            })
        }

        val bodyObj = buildJsonObject {
            put("model", BuildConfig.AURA_MODEL)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", userContent)
                })
            })
            put("max_tokens", 2048)
            put("temperature", 0.1)
        }

        val body = bodyObj.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$base/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    // fallback: مدل vision ندارد
                    if (response.code == 400 || response.code == 422) {
                        return Result.success(
                            "[مدل فعلی vision را پشتیبانی نمی‌کند. متن را کپی کنید یا مدل بینایی تنظیم کنید.]\n$raw".take(300)
                        )
                    }
                    return Result.failure(Exception("خطای Vision API (${response.code})")
                }
                val content = Regex("\"content\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
                    .find(raw)?.groupValues?.getOrNull(1)
                    ?.replace("\\n", "\n")
                    ?.replace("\\\"", "\"")
                    ?: json.decodeFromString<SimpleChatResponse>(raw)
                        .choices.firstOrNull()?.message?.content
                if (content.isNullOrBlank()) Result.failure(Exception("متن استخراج نشد"))
                else Result.success(content.trim())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    @Serializable
    private data class SimpleChatResponse(val choices: List<SimpleChoice> = emptyList())

    @Serializable
    private data class SimpleChoice(val message: SimpleMsg)

    @Serializable
    private data class SimpleMsg(val content: String? = null)
}
