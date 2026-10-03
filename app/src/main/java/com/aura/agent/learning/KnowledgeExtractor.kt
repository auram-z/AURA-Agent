package com.aura.agent.learning

import android.util.Log
import com.aura.agent.ai.AuraAiService
import com.aura.agent.data.ChatMessage
import com.aura.agent.data.MessageRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * فاز ۵: استخراج دانش از مکالمه با کمک LLM.
 * نتیجه فقط پس از ConfirmationGate ذخیره می‌شود.
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
    }

    data class ExtractedItem(val title: String, val content: String)

    suspend fun extractFromConversation(
        userMessage: ChatMessage,
        assistantMessage: ChatMessage
    ): List<ExtractedItem> = withContext(Dispatchers.IO) {
        try {
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
            Log.w(TAG, "Knowledge extract failed: ${e.message}")
            emptyList()
        }
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
                items.add(ExtractedItem(title.take(80), content.take(500)))
            }
        }
        // fallback: اگر فرمت رعایت نشد ولی متن مفید است
        if (items.isEmpty() && raw.length in 20..400 && !raw.contains("NONE")) {
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
