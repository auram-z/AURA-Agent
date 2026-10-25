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
 * جستجوی وب چندزبانه برای به‌روز بودن AURA.
 * منابع: DuckDuckGo Instant Answer + Wikipedia (fa/en) + خلاصه.
 */
class WebSearchService {

    companion object {
        private const val TAG = "AuraWebSearch"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(18, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class SearchHit(
        val title: String,
        val snippet: String,
        val url: String = "",
        val language: String = "und",
        val sourceLabel: String = "web"
    )

    data class SearchReport(
        val query: String,
        val hits: List<SearchHit>,
        val summaryText: String,
        val languagesUsed: List<String> = emptyList()
    )

    /**
     * جستجوی اصلی.
     * @param preferredLangs ترتیب ترجیح زبان (مثلاً listOf("fa", "en"))
     */
    suspend fun search(
        query: String,
        maxResults: Int = 8,
        preferredLangs: List<String> = listOf("fa", "en")
    ): SearchReport = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isBlank()) {
            return@withContext SearchReport(q, emptyList(), "")
        }

        coroutineScope {
            val ddg = async { searchDuckDuckGo(q, maxResults) }
            val wikiJobs = preferredLangs.take(2).map { lang ->
                async { searchWikipedia(q, lang, 3) }
            }

            val allHits = mutableListOf<SearchHit>()
            allHits += ddg.await()
            wikiJobs.forEach { allHits += it.await() }

            val unique = allHits
                .distinctBy { it.title.lowercase().take(50) + it.url.take(40) }
                .take(maxResults)

            val langs = unique.map { it.language }.filter { it != "und" }.distinct()

            val summary = buildString {
                append("نتایج جستجو برای «$q»:\n")
                unique.forEachIndexed { i, h ->
                    append("${i + 1}. [${h.language}] ${h.title}\n")
                    if (h.snippet.isNotBlank()) append("   ${h.snippet.take(260)}\n")
                    if (h.url.isNotBlank()) append("   ${h.url}\n")
                }
            }

            Log.i(TAG, "Search '$q' → ${unique.size} hits (langs=$langs)")
            SearchReport(q, unique, summary, langs)
        }
    }

    private fun searchDuckDuckGo(query: String, limit: Int): List<SearchHit> {
        return try {
            val enc = URLEncoder.encode(query, "UTF-8")
            val url = "https://api.duckduckgo.com/?q=$enc&format=json&no_html=1&skip_disambig=1"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "AURA-Agent/3.1")
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
        val absText = Regex("\"AbstractText\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
            .find(json)?.groupValues?.getOrNull(1)?.unescapeJson()
        val absUrl = Regex("\"AbstractURL\"\\s*:\\s*\"([^\"]*)\"")
            .find(json)?.groupValues?.getOrNull(1).orEmpty()
        val heading = Regex("\"Heading\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
            .find(json)?.groupValues?.getOrNull(1)?.unescapeJson().orEmpty()

        if (!absText.isNullOrBlank()) {
            hits.add(
                SearchHit(
                    title = heading.ifBlank { "خلاصه" },
                    snippet = absText,
                    url = absUrl,
                    language = detectLang(absText),
                    sourceLabel = "duckduckgo"
                )
            )
        }

        Regex("\"Text\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").findAll(json).forEach { m ->
            val text = m.groupValues[1].unescapeJson()
            if (text.length > 20 && hits.none { it.snippet == text }) {
                val title = text.substringBefore(" - ").take(80)
                val snip = text.substringAfter(" - ", text).take(300)
                hits.add(
                    SearchHit(
                        title = title,
                        snippet = snip,
                        language = detectLang(text),
                        sourceLabel = "duckduckgo"
                    )
                )
            }
        }
        return hits.take(limit)
    }

    private fun searchWikipedia(query: String, lang: String, limit: Int): List<SearchHit> {
        return try {
            val enc = URLEncoder.encode(query, "UTF-8")
            val base = when (lang) {
                "fa" -> "https://fa.wikipedia.org"
                "en" -> "https://en.wikipedia.org"
                "ar" -> "https://ar.wikipedia.org"
                else -> "https://en.wikipedia.org"
            }
            val url =
                "$base/w/api.php?action=opensearch&search=$enc&limit=$limit&namespace=0&format=json"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "AURA-Agent/3.1")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return emptyList()
                parseWikiOpenSearch(body, lang)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Wiki($lang) failed: ${e.message}")
            emptyList()
        }
    }

    private fun parseWikiOpenSearch(json: String, lang: String): List<SearchHit> {
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
                url = urls.getOrNull(i).orEmpty(),
                language = lang,
                sourceLabel = "wikipedia"
            )
        }
    }

    /** تشخیص بسیار ساده زبان بر اساس کاراکترها */
    private fun detectLang(text: String): String {
        val persian = text.count { it in '\u0600'..'\u06FF' }
        val arabic = text.count { it in '\u0621'..'\u064A' }
        val latin = text.count { it in 'a'..'z' || it in 'A'..'Z' }
        return when {
            persian > 5 || (persian > 0 && persian >= latin) -> "fa"
            arabic > 5 -> "ar"
            latin > 5 -> "en"
            else -> "und"
        }
    }

    private fun String.unescapeJson(): String =
        replace("\\n", " ")
            .replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace("\\u", "u")
}
