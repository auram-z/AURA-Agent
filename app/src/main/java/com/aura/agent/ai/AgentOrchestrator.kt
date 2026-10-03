package com.aura.agent.ai

import android.util.Log
import com.aura.agent.data.ChatMessage

class AgentOrchestrator(
    private val ai: AuraAiService = AuraAiService(),
    private val search: WebSearchService = WebSearchService()
) {

    companion object {
        private const val TAG = "AuraAgent"

        private val SEARCH_HINTS = listOf(
            "جستجو", "سرچ", "search", "گوگل", "خبر", "امروز", "الان",
            "قیمت", "نرخ", "آخرین", "جدیدترین", "چه خبر", "آب و هوا",
            "هواشناسی", "latest", "news", "price of", "کیست", "چیست"
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
        if (lower.contains("؟") || lower.contains("?")) {
            if (listOf("چند", "کجا", "کی ", "آیا", "how", "where", "when").any { lower.contains(it) })
                return true
        }
        return false
    }

    suspend fun run(
        history: List<ChatMessage>,
        userText: String,
        baseContext: String,
        forceSearch: Boolean = false,
        modelName: String? = null
    ): AgentResult {
        val doSearch = forceSearch || needsSearch(userText)
        var searchBlock: String? = null

        if (doSearch) {
            val query = extractQuery(userText, modelName)
            Log.i(TAG, "Web search: $query")
            val report = search.search(query)
            if (report.hits.isNotEmpty()) searchBlock = report.summaryText
        }

        val extra = buildString {
            append(baseContext)
            if (!searchBlock.isNullOrBlank()) {
                append("\n\n=== نتایج جستجوی وب ===\n")
                append(searchBlock)
                append("\nاز این نتایج استفاده کن و منبع را اشاره کن.")
            }
        }

        val result = ai.chat(history, extra, modelName = modelName)
        val reply = result.getOrElse {
            return AgentResult("خطا: ${it.message}", doSearch, searchBlock)
        }
        return AgentResult(reply, doSearch && !searchBlock.isNullOrBlank(), searchBlock)
    }

    private suspend fun extractQuery(userText: String, modelName: String?): String {
        var q = userText
            .replace(Regex("(?i)(جستجو|سرچ|search|گوگل)\s*(کن)?[:：]?"), "")
            .trim()
        if (q.length in 3..120) return q
        val decided = ai.quickDecide(
            "عبارت جستجوی کوتاه (حداکثر ۱۰ کلمه) برای این سوال. فقط همان:\n$userText",
            modelName = modelName
        ).getOrNull()?.trim()
        return decided?.takeIf { it.length in 2..100 } ?: userText.take(100)
    }
}
