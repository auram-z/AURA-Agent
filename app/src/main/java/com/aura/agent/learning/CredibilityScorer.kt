package com.aura.agent.learning

import android.util.Log
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * امتیازدهی اعتبار منبع و تازگی اطلاعات.
 *
 * خروجی نهایی بین ۰ تا ۱ است و در KnowledgeChunk.confidence ذخیره می‌شود.
 */
class CredibilityScorer {

    companion object {
        private const val TAG = "AuraCredibility"

        /** وزن‌های پایه برای دامنه‌های شناخته‌شده */
        private val DOMAIN_TRUST = mapOf(
            // منابع علمی و مرجع
            "wikipedia.org" to 0.85f,
            "fa.wikipedia.org" to 0.82f,
            "en.wikipedia.org" to 0.85f,
            "arxiv.org" to 0.90f,
            "nature.com" to 0.92f,
            "science.org" to 0.92f,
            "nih.gov" to 0.93f,
            "who.int" to 0.91f,
            "gov" to 0.80f,          // دامنه‌های دولتی عمومی
            "edu" to 0.83f,

            // اخبار معتبر
            "bbc.com" to 0.78f,
            "reuters.com" to 0.82f,
            "apnews.com" to 0.81f,
            "nytimes.com" to 0.75f,
            "theguardian.com" to 0.76f,

            // فنی و توسعه
            "github.com" to 0.70f,
            "stackoverflow.com" to 0.72f,
            "developer.android.com" to 0.88f,
            "docs.python.org" to 0.88f,
            "kotlinlang.org" to 0.87f,

            // پیش‌فرض
            "default" to 0.55f
        )
    }

    data class ScoreResult(
        val confidence: Float,
        val domainTrust: Float,
        val freshness: Float,
        val reason: String
    )

    /**
     * محاسبه امتیاز نهایی.
     *
     * @param sourceUrl آدرس منبع (می‌تواند خالی باشد)
     * @param sourceLabel برچسب منبع (مثلاً "wikipedia", "conversation", "llm_extract")
     * @param receivedAt زمان دریافت (میلی‌ثانیه)
     * @param lastVerifiedAt آخرین تأیید (اختیاری)
     * @param independentConfirmations تعداد منابع مستقل تأییدکننده (۰+)
     */
    fun score(
        sourceUrl: String = "",
        sourceLabel: String = "",
        receivedAt: Long = System.currentTimeMillis(),
        lastVerifiedAt: Long = receivedAt,
        independentConfirmations: Int = 0
    ): ScoreResult {
        val domainTrust = resolveDomainTrust(sourceUrl, sourceLabel)
        val freshness = computeFreshness(receivedAt, lastVerifiedAt)
        val consensusBoost = min(0.15f, independentConfirmations * 0.05f)

        // فرمول ترکیبی
        var confidence = domainTrust * 0.55f + freshness * 0.30f + consensusBoost
        confidence = confidence.coerceIn(0.05f, 0.98f)

        val reason = buildString {
            append("domain=${"%.2f".format(domainTrust)}, ")
            append("fresh=${"%.2f".format(freshness)}, ")
            append("confirm=$independentConfirmations")
        }

        Log.d(TAG, "Score $confidence ($reason)")
        return ScoreResult(confidence, domainTrust, freshness, reason)
    }

    private fun resolveDomainTrust(url: String, label: String): Float {
        if (url.isNotBlank()) {
            val host = extractHost(url).lowercase()
            // تطبیق دقیق یا پسوند
            DOMAIN_TRUST.entries.forEach { (key, value) ->
                if (key != "default" && (host == key || host.endsWith(".$key") || host.contains(key))) {
                    return value
                }
            }
            // دامنه .gov / .edu
            if (host.endsWith(".gov") || host.endsWith(".gov.ir")) return 0.80f
            if (host.endsWith(".edu") || host.endsWith(".ac.ir")) return 0.83f
        }

        // برچسب منبع
        return when (label.lowercase()) {
            "wikipedia", "wiki" -> 0.82f
            "arxiv", "paper" -> 0.90f
            "official_docs", "docs" -> 0.88f
            "news_reuters", "reuters" -> 0.82f
            "github" -> 0.70f
            "conversation", "user" -> 0.65f
            "llm_extract", "auto_llm" -> 0.50f
            "offline_queue" -> 0.45f
            else -> DOMAIN_TRUST["default"] ?: 0.55f
        }
    }

    /**
     * تازگی: با گذشت زمان کاهش می‌یابد (نیمه‌عمر تقریبی ۹۰ روز).
     */
    private fun computeFreshness(receivedAt: Long, lastVerifiedAt: Long): Float {
        val now = System.currentTimeMillis()
        val ageMs = max(0L, now - max(receivedAt, lastVerifiedAt))
        val ageDays = ageMs / 86_400_000.0
        // e^(-age/90)
        val freshness = exp(-ageDays / 90.0).toFloat()
        return freshness.coerceIn(0.1f, 1.0f)
    }

    private fun extractHost(url: String): String {
        return try {
            val cleaned = url.trim()
                .removePrefix("https://")
                .removePrefix("http://")
                .substringBefore("/")
                .substringBefore("?")
            cleaned.lowercase()
        } catch (_: Exception) {
            ""
        }
    }
}
