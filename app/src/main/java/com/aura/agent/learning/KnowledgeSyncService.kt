package com.aura.agent.learning

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * همگام‌سازی دانش بین نسخه آنلاین (وب) و نسخه اندروید.
 *
 * وقتی گوشی آنلاین است:
 * - می‌تواند بسته دانش را از URL امن (HTTPS) بگیرد
 * - یا از متن JSON وارد کند
 * - و بسته دانش فعلی را برای ارسال به وب صادر کند
 */
class KnowledgeSyncService(
    private val context: Context,
    private val knowledgeStore: KnowledgeStore = KnowledgeStore(context)
) {

    companion object {
        private const val TAG = "AuraKnowledgeSync"
        private const val FORMAT = "aura-knowledge-pack"
        private const val VERSION = 2
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @Serializable
    data class KnowledgePack(
        val format: String = FORMAT,
        val version: Int = VERSION,
        val exportedAt: Long = System.currentTimeMillis(),
        val device: String = "android",
        val chunks: List<KnowledgeStore.KnowledgeChunk> = emptyList()
    )

    data class SyncResult(
        val imported: Int,
        val skipped: Int,
        val updated: Int,
        val message: String
    )

    /** صدور بسته دانش فعلی برای ارسال به نسخه آنلاین */
    suspend fun exportPack(): String = withContext(Dispatchers.IO) {
        val chunks = knowledgeStore.chunks.first()
        val pack = KnowledgePack(
            exportedAt = System.currentTimeMillis(),
            device = "android",
            chunks = chunks
        )
        json.encodeToString(pack)
    }

    /** وارد کردن بسته دانش از متن JSON */
    suspend fun importFromJson(raw: String): SyncResult = withContext(Dispatchers.IO) {
        val pack = runCatching {
            json.decodeFromString<KnowledgePack>(raw)
        }.getOrElse {
            return@withContext SyncResult(0, 0, 0, "فرمت بسته نامعتبر: ${it.message}")
        }

        if (pack.format != FORMAT) {
            return@withContext SyncResult(0, 0, 0, "فرمت ناشناخته: ${pack.format}")
        }

        mergeChunks(pack.chunks, sourceLabel = "online_sync")
    }

    /**
     * گرفتن بسته دانش از یک URL امن (فقط HTTPS).
     * مناسب برای وقتی کاربر بسته را روی فضای تحت کنترل خودش گذاشته.
     */
    suspend fun importFromUrl(url: String): SyncResult = withContext(Dispatchers.IO) {
        val cleaned = url.trim()
        if (!cleaned.startsWith("https://")) {
            return@withContext SyncResult(0, 0, 0, "فقط آدرس HTTPS مجاز است.")
        }

        try {
            val req = Request.Builder()
                .url(cleaned)
                .header("User-Agent", "AURA-Agent/3.1-Sync")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext SyncResult(0, 0, 0, "خطای شبکه: ${resp.code}")
                }
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) {
                    return@withContext SyncResult(0, 0, 0, "پاسخ خالی از سرور")
                }
                importFromJson(body)
            }
        } catch (e: Exception) {
            Log.e(TAG, "importFromUrl failed: ${e.message}")
            SyncResult(0, 0, 0, "خطا: ${e.message}")
        }
    }

    /**
     * ادغام chunkهای دریافتی با دانش محلی.
     * - تکراری تقریبی رد می‌شود
     * - اگر عنوان یکسان و confidence بالاتر بود، به‌روز می‌شود
     */
    private suspend fun mergeChunks(
        incoming: List<KnowledgeStore.KnowledgeChunk>,
        sourceLabel: String
    ): SyncResult {
        val existing = knowledgeStore.chunks.first()
        var imported = 0
        var skipped = 0
        var updated = 0

        for (raw in incoming) {
            val chunk = raw.copy(
                id = raw.id.ifBlank { UUID.randomUUID().toString() },
                source = raw.source.ifBlank { sourceLabel },
                tags = (raw.tags + "synced").distinct()
            )

            val sameTitle = existing.find {
                it.title.equals(chunk.title, ignoreCase = true)
            }

            when {
                sameTitle == null -> {
                    // جدید
                    val nearDup = existing.any {
                        it.content.take(80) == chunk.content.take(80)
                    }
                    if (nearDup) {
                        skipped++
                    } else {
                        knowledgeStore.add(chunk)
                        imported++
                    }
                }
                chunk.confidence > sameTitle.confidence + 0.05f -> {
                    // نسخه بهتر از آنلاین
                    knowledgeStore.update(
                        sameTitle.copy(
                            content = chunk.content,
                            source = chunk.source,
                            sourceUrl = chunk.sourceUrl.ifBlank { sameTitle.sourceUrl },
                            language = chunk.language.ifBlank { sameTitle.language },
                            confidence = chunk.confidence,
                            claims = if (chunk.claims.isNotEmpty()) chunk.claims else sameTitle.claims,
                            lastVerifiedAt = System.currentTimeMillis(),
                            tags = (sameTitle.tags + chunk.tags + "updated_from_online").distinct()
                        )
                    )
                    updated++
                }
                else -> skipped++
            }
        }

        val msg = buildString {
            append("همگام‌سازی انجام شد: ")
            append("$imported جدید، ")
            append("$updated به‌روز، ")
            append("$skipped رد شده")
        }
        Log.i(TAG, msg)
        return SyncResult(imported, skipped, updated, msg)
    }
}
