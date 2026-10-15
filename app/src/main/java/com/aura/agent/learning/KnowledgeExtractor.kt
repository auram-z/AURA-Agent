package com.aura.agent.learning

import android.util.Log
import com.aura.agent.ai.AuraAiService
import com.aura.agent.data.ChatMessage
import com.aura.agent.data.MessageRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * استخراج دانش از مکالمه با کمک LLM.
 * در صورت شکست LLM، استخراج heuristic محلی انجام می‌شود.
 */
class KnowledgeExtractor(
    private val aiService: AuraAiService = AuraAiService()
) {

    companion object {
        private const val TAG = "AuraKnowledge"

        private val EXTRACT_PROMPT = """
از مکالمه زیر حداکثر ۳ نکته دانشی پایدار و مفید استخراج کن.
فقط حقایق یا ترجیحات کاربر که ارزش ذخیره بلندمدت دارند.
اگر چیزی ارزش ذخیره ندارد فقط بنویس: NONE

فرمت هر خط (اگر وجود دارد):
TITLE: <عنوان کوتاه>
CONTENT: <متن دانش>
---
""".trim()

        private val FACT_HINTS = listOf(
            "من ", "اسمم", "کارم", "زندگی", "علاقه", "ترجیح",
            "همیشه", "هرگز", "مهمه", "یادم باشه", "my name", "i am", "i prefer"
        )
    }

    data class ExtractedItem(val title: String, val content: String)

    suspend fun extractFromConversation(
        userMessage: ChatMessage,
        assistantMessage: ChatMessage
    ): List<ExtractedItem> = withContext(Dispatchers.IO) {
        // ابتدا LLM
        val llmItems = try {
            val history = listOf(
                ChatMessage(role = MessageRole.SYSTEM, content = EXTRACT_PROMPT),
                userMessage,
                assistantMessage,
                ChatMessage(
                    role = MessageRole.USER,
                    content = "لطفاً دانش پایدار را طبق فرمت استخراج کن."
                )
            )
            val result = aiService.chat(history, "")
            result.getOrNull()?.let { parse(it) } ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Knowledge extract LLM failed: ${e.message}")
            emptyList()
        }

        if (llmItems.isNotEmpty()) return@withContext llmItems

        // Fallback محلی بدون وابستگی به شبکه/مدل
        heuristicExtract(userMessage.content)
    }

    private fun heuristicExtract(userText: String): List<ExtractedItem> {
        val text = userText.trim()
        if (text.length < 15 || text.length > 400) return emptyList()
        val lower = text.lowercase()

        val looksFactual = FACT_HINTS.any { lower.contains(it) } ||
            lower.contains("یادت باشه") || lower.contains("به خاطر بسپار")
        if (!looksFactual) return emptyList()

        // از دستورات ذخیره جدا کن
        val cleaned = text
            .replace(Regex("(?i)(یادت باشه|به خاطر بسپار|یاد بگیر|remember)[:：]?\\s*"), "")
            .trim()
        if (cleaned.length < 10) return emptyList()

        val title = when {
            lower.contains("اسم") || lower.contains("من هستم") -> "هویت کاربر"
            lower.contains("ترجیح") || lower.contains("دوست دارم") -> "ترجیح کاربر"
            lower.contains("هدف") -> "هدف کاربر"
            else -> "نکته مکالمه"
        }
        return listOf(ExtractedItem(title, cleaned.take(400)))
    }

    private fun parse(raw: String): List<ExtractedItem> {
        if (raw.trim().equals("NONE", ignoreCase = true)) return emptyList()
        val items = mutableListOf<ExtractedItem>()
        val blocks = raw.split("---").map { it.trim() }.filter { it.isNotBlank() }
        for (block in blocks) {
            val title = Regex("TITLE:\\s*(.+)", RegexOption.IGNORE_CASE)
                .find(block)?.groupValues?.getOrNull(1)?.trim()
            val content = Regex("CONTENT:\\s*(.+)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(block)?.groupValues?.getOrNull(1)?.trim()
            if (!title.isNullOrBlank() && !content.isNullOrBlank()) {
                items.add(ExtractedItem(title.take(80), content.take(500))
                )
            }
        }
        if (items.isEmpty() && raw.length in 20..400 && !raw.contains("NONE", ignoreCase = true)) {
            items.add(ExtractedItem("دانش استخراج‌شده", raw.take(400)))
        }
        return items.take(3)
    }

    fun toChunk(item: ExtractedItem) = KnowledgeStore.KnowledgeChunk(
        id = UUID.randomUUID().toString(),
        title = item.title,
        content = item.content,
        source = "llm_extract"
    )
}
