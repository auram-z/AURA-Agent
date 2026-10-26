package com.aura.agent.ai

import android.content.Context
import android.util.Log
import com.aura.agent.BuildConfig
import com.aura.agent.security.SecureStore
import com.aura.agent.security.SecurityGuards
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * کشف مدل‌های موجود از API xAI و ارتقای خودکار به بهترین/جدیدترین.
 */
class ModelAutoUpgrade(
    private val modelConfig: ModelConfig,
    private val context: Context? = null
) {

    companion object {
        private const val TAG = "AuraModelUpgrade"
        const val CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L

        private val PREFERRED_ORDER = listOf(
            "grok-4.7",
            "grok-4-latest",
            "grok-4.6",
            "grok-4.5",
            "grok-4",
            "grok-3",
            "grok-2",
            "grok-beta"
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    data class UpgradeResult(
        val changed: Boolean,
        val previousModel: String,
        val activeModel: String,
        val available: List<String>,
        val message: String
    )

    private fun resolveApiKey(): String {
        context?.let { ctx ->
            val k = SecureStore(ctx).getString(SecureStore.KEY_API_OVERRIDE)
            if (k.isNotBlank()) return k
        }
        return ""
    }

    suspend fun listRemoteModels(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val apiKey = resolveApiKey()
            if (apiKey.isBlank()) {
                return@withContext Result.failure(Exception("کلید API تنظیم نشده (SecureStore)"))
            }
            val base = SecurityGuards.requireHttps(BuildConfig.AURA_BASE_URL).getOrElse {
                return@withContext Result.failure(it)
            }
            val req = Request.Builder()
                .url("$base/models")
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(Exception("models API: ${resp.code}"))
                }
                val parsed = json.decodeFromString<ModelsResponse>(body)
                val ids = parsed.data.map { it.id }.sorted()
                Result.success(ids)
            }
        } catch (e: Exception) {
            Log.w(TAG, "list models failed: ${e.message}")
            Result.failure(e)
        }
    }

    fun pickBest(available: List<String>): String? {
        if (available.isEmpty()) return null
        for (pref in PREFERRED_ORDER) {
            if (available.any { it.equals(pref, ignoreCase = true) }) {
                return available.first { it.equals(pref, ignoreCase = true) }
            }
        }
        available.filter { it.startsWith("grok-4", ignoreCase = true) }
            .maxWithOrNull(compareBy({ versionKey(it) }, { it }))
            ?.let { return it }
        return available.filter { it.startsWith("grok", ignoreCase = true) }
            .maxWithOrNull(compareBy({ versionKey(it) }, { it }))
            ?: available.firstOrNull()
    }

    private fun versionKey(id: String): Double {
        val m = Regex("""grok-?(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(id)
        return m?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 0.0
    }

    suspend fun checkAndUpgrade(force: Boolean = false): UpgradeResult {
        val previous = modelConfig.getActiveModel()
        val auto = modelConfig.isAutoUpgradeEnabled()

        if (!force && !auto) {
            return UpgradeResult(
                false, previous, previous, emptyList(),
                "ارتقای خودکار خاموش است. مدل فعلی: $previous"
            )
        }

        if (!force) {
            val last = modelConfig.getLastCheckMs()
            if (System.currentTimeMillis() - last < CHECK_INTERVAL_MS) {
                return UpgradeResult(
                    false, previous, previous, modelConfig.getCachedAvailable(),
                    "به‌تازگی بررسی شده. مدل: $previous"
                )
            }
        }

        val remote = listRemoteModels().getOrElse { err ->
            if (previous == "grok-beta" || previous.isBlank()) {
                modelConfig.setActiveModel(ModelConfig.FLAGSHIP_DEFAULT)
                return UpgradeResult(
                    true, previous, ModelConfig.FLAGSHIP_DEFAULT, emptyList(),
                    "API در دسترس نبود؛ مدل پیش‌فرض به ${ModelConfig.FLAGSHIP_DEFAULT} تنظیم شد.\n(${err.message})"
                )
            }
            return UpgradeResult(
                false, previous, previous, emptyList(),
                "نتوانستم لیست مدل‌ها را بگیرم: ${err.message}"
            )
        }

        modelConfig.markChecked(remote.joinToString(","))
        val best = pickBest(remote) ?: previous

        return if (best != previous && (auto || force)) {
            modelConfig.setActiveModel(best)
            Log.i(TAG, "Upgraded $previous → $best")
            UpgradeResult(
                true, previous, best, remote,
                "✅ مدل ارتقا یافت:\n$previous → **$best**\n\nمدل‌های موجود: ${remote.take(12).joinToString(", ")}"
            )
        } else {
            UpgradeResult(
                false, previous, previous, remote,
                "مدل فعلی به‌روز است: $previous\n\nدر دسترس: ${remote.take(12).joinToString(", ")}"
            )
        }
    }

    @Serializable
    private data class ModelsResponse(val data: List<ModelItem> = emptyList())

    @Serializable
    private data class ModelItem(val id: String)
}
