package com.aura.agent.learning

import android.util.Log
import com.aura.agent.learning.KnowledgeStore.Claim
import com.aura.agent.learning.KnowledgeStore.KnowledgeChunk

/**
 * تشخیص تناقض بین ادعاهای ذخیره‌شده در پایگاه دانش.
 *
 * روش فعلی heuristic + تطبیق ساده کلمات کلیدی است
 * (در نسخه‌های بعدی می‌توان با embedding معنایی تقویت کرد).
 */
class ContradictionDetector {

    companion object {
        private const val TAG = "AuraContradiction"

        private val NEGATION_MARKERS = listOf(
            "نه", "نیست", "نمی‌", "نمي", "هرگز", "هیچ", "غلط", "اشتباه",
            "not", "never", "no ", "false", "incorrect", "deny"
        )

        private val UNCERTAINTY_MARKERS = listOf(
            "شاید", "ممکن", "احتمالا", "گفته می‌شود", "ظاهرا",
            "maybe", "possibly", "allegedly", "reportedly", "uncertain"
        )
    }

    data class Contradiction(
        val claimA: String,
        val claimB: String,
        val chunkIdA: String,
        val chunkIdB: String,
        val severity: Float,          // 0.0 – 1.0
        val reason: String
    )

    data class AnalysisResult(
        val contradictions: List<Contradiction>,
        val uncertainClaims: List<Pair<String, String>>, // chunkId to claim
        val summary: String
    )

    /**
     * تحلیل کل پایگاه یا زیرمجموعه‌ای از chunkها.
     */
    fun analyze(chunks: List<KnowledgeChunk>): AnalysisResult {
        val contradictions = mutableListOf<Contradiction>()
        val uncertain = mutableListOf<Pair<String, String>>()

        // جمع‌آوری تمام ادعاها
        val allClaims = chunks.flatMap { chunk ->
            val claims = if (chunk.claims.isNotEmpty()) {
                chunk.claims.map { it to chunk }
            } else {
                // اگر claims خالی باشد، از content یک ادعای ساده بساز
                listOf(Claim(chunk.content.take(200)) to chunk)
            }
            claims
        }

        // بررسی عدم قطعیت
        allClaims.forEach { (claim, chunk) ->
            if (hasUncertainty(claim.text)) {
                uncertain.add(chunk.id to claim.text)
            }
        }

        // مقایسه دوتایی (ساده و محدود برای عملکرد)
        val limited = allClaims.take(80) // محدودیت عملکرد روی دستگاه
        for (i in limited.indices) {
            for (j in i + 1 until limited.size) {
                val (c1, ch1) = limited[i]
                val (c2, ch2) = limited[j]
                if (ch1.id == ch2.id) continue

                val result = compareClaims(c1, c2, ch1, ch2)
                if (result != null) {
                    contradictions.add(result)
                }
            }
        }

        val summary = buildString {
            append("تحلیل دانش: ${chunks.size} تکه، ")
            append("${contradictions.size} تناقض احتمالی، ")
            append("${uncertain.size} ادعای نامطمئن")
        }

        Log.i(TAG, summary)
        return AnalysisResult(contradictions, uncertain, summary)
    }

    private fun compareClaims(
        a: Claim,
        b: Claim,
        chunkA: KnowledgeChunk,
        chunkB: KnowledgeChunk
    ): Contradiction? {
        val textA = a.text.lowercase().trim()
        val textB = b.text.lowercase().trim()

        if (textA.length < 12 || textB.length < 12) return null

        // شباهت واژگانی ساده
        val tokensA = tokenize(textA)
        val tokensB = tokenize(textB)
        if (tokensA.isEmpty() || tokensB.isEmpty()) return null

        val overlap = tokensA.intersect(tokensB).size.toFloat()
        val union = (tokensA + tokensB).toSet().size.toFloat()
        val jaccard = if (union > 0) overlap / union else 0f

        if (jaccard < 0.35f) return null // شباهت کم → احتمالاً موضوع متفاوت

        val negA = hasNegation(textA)
        val negB = hasNegation(textB)

        // یکی مثبت و یکی منفی با همپوشانی بالا → تناقض
        if (negA != negB && jaccard >= 0.40f) {
            val severity = (jaccard * 0.7f + 0.3f).coerceIn(0.4f, 0.95f)
            return Contradiction(
                claimA = a.text.take(180),
                claimB = b.text.take(180),
                chunkIdA = chunkA.id,
                chunkIdB = chunkB.id,
                severity = severity,
                reason = "قطب مخالف با همپوشانی واژگانی ${"%.0f".format(jaccard * 100)}%"
            )
        }

        // هر دو منفی یا هر دو مثبت اما محتوای متضاد (heuristic ضعیف)
        if (jaccard >= 0.55f && looksConflicting(textA, textB)) {
            return Contradiction(
                claimA = a.text.take(180),
                claimB = b.text.take(180),
                chunkIdA = chunkA.id,
                chunkIdB = chunkB.id,
                severity = 0.55f,
                reason = "شباهت بالا اما نشانه‌های تضاد محتوایی"
            )
        }

        return null
    }

    private fun tokenize(text: String): Set<String> {
        return text.split(Regex("[\\s\\p{Punct}]+") )
            .map { it.trim() }
            .filter { it.length >= 3 }
            .toSet()
    }

    private fun hasNegation(text: String): Boolean {
        return NEGATION_MARKERS.any { text.contains(it) }
    }

    private fun hasUncertainty(text: String): Boolean {
        return UNCERTAINTY_MARKERS.any { text.contains(it) }
    }

    private fun looksConflicting(a: String, b: String): Boolean {
        // مثال‌های ساده: یکی عدد/سال متفاوت، یکی «قبل» و دیگری «بعد»
        val yearRegex = Regex("\\b(19|20)\\d{2}\\b")
        val yearsA = yearRegex.findAll(a).map { it.value }.toSet()
        val yearsB = yearRegex.findAll(b).map { it.value }.toSet()
        if (yearsA.isNotEmpty() && yearsB.isNotEmpty() && yearsA.intersect(yearsB).isEmpty()) {
            return true
        }
        return false
    }
}
