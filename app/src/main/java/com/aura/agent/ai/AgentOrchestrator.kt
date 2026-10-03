package com.aura.agent.ai

import android.util.Log
import com.aura.agent.data.ChatMessage
import com.aura.agent.data.MessageRole

/**
 * حلقهٔ ایجنت: تشخیص نیاز به جستجو → جمع‌آوری داده → پاسخ نهایی.
 * مشابه tool-use در ChatGPT / Gemini / Grok.
 */
class AgentOrchestrator(
    private val ai: AuraAiService = AuraAiService(),
    private val search: WebSearchService = WebSearchService()
) {

    companion object {
        private const val TAG = "AuraAgent"

        private val SEARCH_HINTS = listOf(
            "جستجو", "سرچ", "search", "گوگل", "خبر", "امروز", "الان",
            "قیمت", "نرخ", "آخرین", "جدیدترین", "چه خبر", "آب و هوا",
            "هواشناسی", "نتیجه بازی", "انتخابات", "بورس امروز",
            "who is", "what is", "when did", "latest", "news", "price of",
            "کیست", "چیست", "کی بود", "تعریف"
        )
    }

    data class AgentResult(
        val reply: String,
        val searched: Boolean,
        val searchSummary: String?
    )

    fun needsSearch(userText: String): Boolean {
        val lower = userText.lowercase()
        if (SEARCH_HINTS.any { lower.contains(it) }) return true
        // سوالات با «؟» و کلمات زمانی
        if (lower.contains("؟") || lower.contains("?")) {
            if (listOf("چند", "کجا", "کی ", "آیا", "how", "where", "when", "why").any {
                    lower.contains(it)
                }
            ) return true
        }
        return false
    }

    suspend fun run(
        history: List<ChatMessage>,
        userText: String,
        baseContext: String,
        forceSearch: Boolean = false
    ): AgentResult {
        val doSearch = forceSearch || needsSearch(userText)
        var searchBlock: String? = null

        if (doSearch) {
            val query = extractQuery(userText)
            Log.i(TAG, "Web search: $query")
            val report = search.search(query)
            if (report.hits.isNotEmpty()) {
                searchBlock = report.summaryText
            }
        }

        val extra = buildString {
            append(baseContext)
            if (!searchBlock.isNullOrBlank()) {
                append("\n\n=== نتایج جستجوی وب (استفاده الزامی در پاسخ) ===\n")
                append(searchBlock)
                append("\nاز این نتایج برای پاسخ دقیق و به‌روز استفاده کن و منبع را اشاره کن.")
            }
        }

        val result = ai.chat(history, extra)
        val reply = result.getOrElse {
            return AgentResult("متأسفانه خطا رخ داد: ${it.message}", doSearch, searchBlock)
        }
        return AgentResult(reply, doSearch && !searchBlock.isNullOrBlank(), searchBlock)
    }

    private suspend fun extractQuery(userText: String): String {
        // حذف پیشوندهای دستوری
        var q = userText
            .replace(Regex("(?i)(جستجو|سرچ|search|گوگل)\s*(کن)?[:：]?"), "")
            .trim()
        if (q.length in 3..120) return q
        // از مدل بخواه query بسازد
        val decided = ai.quickDecide(
            "یک عبارت جستجوی کوتاه (حداکثر ۱۰ کلمه) برای این سوال بساز. فقط همان عبارت:\n$userText"
        ).getOrNull()?.trim()
        return decided?.takeIf { it.length in 2..100 } ?: userText.take(100)
    }
}
