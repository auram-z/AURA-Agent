package com.aura.agent.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * جستجوی وب برای به‌روز بودن AURA (مشابه Search در ChatGPT/Gemini/Grok).
 * منابع رایگان: DuckDuckGo + Wikipedia.
 */
class WebSearchService {

    companion object {
        private const val TAG = "AuraWebSearch"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class SearchHit(
        val title: String,
        val snippet: String,
        val url: String = ""
    )

    data class SearchReport(
        val query: String,
        val hits: List<SearchHit>,
        val summaryText: String
    )

    suspend fun search(query: String, maxResults: Int = 6): SearchReport =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isBlank()) {
                return@withContext SearchReport(q, emptyList(), "")
            }
            coroutineScope {
                val ddg = async { searchDuckDuckGo(q, maxResults) }
                val wiki = async { searchWikipedia(q, 3) }
                val hits = (ddg.await() + wiki.await())
                    .distinctBy { it.title.lowercase().take(40) }
                    .take(maxResults)
                val summary = buildString {
                    append("نتایج جستجو برای «$q»:\n")
                    hits.forEachIndexed { i, h ->
                        append("${i + 1}. ${h.title}\n")
                        if (h.snippet.isNotBlank()) append("   ${h.snippet.take(280)}\n")
                        if (h.url.isNotBlank()) append("   ${h.url}\n")
                    }
                }
                Log.i(TAG, "Search '$q' → ${hits.size} hits")
                SearchReport(q, hits, summary)
            }
        }

    private fun searchDuckDuckGo(query: String, limit: Int): List<SearchHit> {
        return try {
            val enc = URLEncoder.encode(query, "UTF-8")
            // Instant Answer API
            val url = "https://api.duckduckgo.com/?q=$enc&format=json&no_html=1&skip_disambig=1"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "AURA-Agent/2.6")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return emptyList()
                parseDdgJson(body, limit)
            }
        } catch (e: Exception) {
            Log.w(TAG, "DDG failed: ${e.message}")
            emptyList()
        }
    }

    private fun parseDdgJson(json: String, limit: Int): List<SearchHit> {
        val hits = mutableListOf<SearchHit>()
        // Abstract
        val absText = Regex("\"AbstractText\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
            .find(json)?.groupValues?.getOrNull(1)
            ?.unescapeJson()
        val absUrl = Regex("\"AbstractURL\"\\s*:\\s*\"([^\"]*)\"")
            .find(json)?.groupValues?.getOrNull(1).orEmpty()
        val heading = Regex("\"Heading\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
            .find(json)?.groupValues?.getOrNull(1)?.unescapeJson().orEmpty()
        if (!absText.isNullOrBlank()) {
            hits.add(SearchHit(heading.ifBlank { "خلاصه" }, absText, absUrl))
        }
        // RelatedTopics
        Regex("\"Text\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").findAll(json).forEach { m ->
            val text = m.groupValues[1].unescapeJson()
            if (text.length > 20 && hits.none { it.snippet == text }) {
                val title = text.substringBefore(" - ").take(80)
                val snip = text.substringAfter(" - ", text).take(300)
                hits.add(SearchHit(title, snip))
            }
        }
        return hits.take(limit)
    }

    private fun searchWikipedia(query: String, limit: Int): List<SearchHit> {
        return try {
            val enc = URLEncoder.encode(query, "UTF-8")
            val url =
                "https://fa.wikipedia.org/w/api.php?action=opensearch&search=$enc&limit=$limit&namespace=0&format=json"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "AURA-Agent/2.6")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return emptyList()
                parseWikiOpenSearch(body)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Wiki failed: ${e.message}")
            emptyList()
        }
    }

    private fun parseWikiOpenSearch(json: String): List<SearchHit> {
        // ["query", ["t1","t2"], ["d1","d2"], ["u1","u2"]]
        val arrays = Regex("\\[([^\\[\\]]*)\\]").findAll(json).map { it.groupValues[1] }.toList()
        if (arrays.size < 4) return emptyList()
        fun parseStrList(s: String): List<String> =
            Regex("\"((?:\\\\.|[^\"\\\\])*)\"").findAll(s).map { it.groupValues[1].unescapeJson() }.toList()
        val titles = parseStrList(arrays[1])
        val descs = parseStrList(arrays.getOrNull(2).orEmpty())
        val urls = parseStrList(arrays.getOrNull(3).orEmpty())
        return titles.indices.map { i ->
            SearchHit(
                title = titles[i],
                snippet = descs.getOrNull(i).orEmpty(),
                url = urls.getOrNull(i).orEmpty()
            )
        }
    }

    private fun String.unescapeJson(): String =
        replace("\\n", " ")
            .replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace("\\u", "u")
}
