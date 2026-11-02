package com.aura.agent.core

import com.aura.agent.data.MemoryStore
import com.aura.agent.learning.ContradictionDetector
import com.aura.agent.learning.KnowledgeStore
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * هسته محلی فوق‌هوشمند و مستقل AURA.
 *
 * بدون هیچ تماس شبکه‌ای کار می‌کند:
 * - حافظه کاربر
 * - پایگاه دانش با سطح اطمینان
 * - تشخیص تناقض
 * - استدلال قاعده‌محور + بازیابی دانش
 */
class LocalKnowledgeBackend(
    private val memoryStore: MemoryStore,
    private val knowledgeStore: KnowledgeStore,
    private val contradictionDetector: ContradictionDetector = ContradictionDetector()
) : InferenceBackend {

    override val kind = BackendKind.LOCAL
    override val displayName = "AURA Super Independent Core"

    override fun isAvailable(): Boolean = true

    override suspend fun generate(request: InferenceRequest): Result<InferenceResponse> {
        val text = request.userText.trim()
        val lower = normalizeFa(text.lowercase())

        val (reply, confidence) = when {
            lower.matches(Regex(".*(سلام|درود|hi|hello).*")) ->
                greeting() to 0.95f

            lower.contains("کی هستی") || lower.contains("تو کی") || lower.contains("who are you") ||
            (lower.contains("هسته") && (lower.contains("چی") || lower.contains("چیست"))) ->
                identity() to 0.98f

            lower.contains("ساعت") || lower.contains("زمان") || lower.contains("time") ->
                ("⏰ " + SimpleDateFormat("HH:mm — EEEE d MMMM yyyy", Locale("fa")).format(Date())) to 1.0f

            lower.contains("حافظه") || lower.contains("چه یادت") || lower.contains("یادداشت") ->
                dumpMemory() to 0.9f

            lower.contains("دانش") || lower.contains("پایگاه دانش") || lower.contains("وضعیت دانش") ->
                dumpKnowledge() to 0.9f

            lower.contains("تناقض") || lower.contains("contradiction") ->
                analyzeContradictions() to 0.85f

            lower.contains("آفلاین") || lower.contains("offline") || lower.contains("بدون اینترنت") ->
                ("📡 هسته فوق‌هوشمند مستقل AURA بدون اینترنت فعال است.\n" +
                    "حافظه، دانش و استدلال روی دستگاه شماست.") to 0.95f

            lower.contains("کمک") || lower.contains("help") || lower == "راهنما" ->
                help() to 0.95f

            else -> {
                val kb = reasonFromKnowledge(text)
                if (kb != null) kb
                else fallback(text) to 0.35f
            }
        }

        return Result.success(
            InferenceResponse(
                text = reply,
                backend = BackendKind.LOCAL,
                offline = true,
                confidence = confidence
            )
        )
    }

    private fun normalizeFa(s: String): String {
        return s
            .replace('\u200c', ' ')
            .replace('ي', 'ی')
            .replace('ك', 'ک')
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private suspend fun greeting(): String {
        val name = memoryStore.userName.first()
        return if (!name.isNullOrBlank())
            "سلام $name. من AURA هستم — هسته فوق‌هوشمند مستقل روی دستگاه شما. At your service."
        else
            "سلام. من AURA هستم — هسته فوق‌هوشمند و مستقل شما. At your service."
    }

    private fun identity(): String = """
من **AURA Super Independent Core** هستم.

• بدون وابستگی اجباری به هیچ شرکت یا API خارجی
• حافظه و دانش فقط روی همین دستگاه
• استدلال با پایگاه دانش + سطح اطمینان + تشخیص تناقض
• خودترمیم خودکار ضعف‌های رفتاری
• آنلاین فقط وقتی شما بخواهید (اختیاری)

At your service.
    """.trim()

    private suspend fun dumpMemory(): String {
        val name = memoryStore.userName.first()
        val notes = memoryStore.notes.first()
        val goals = memoryStore.goals.first()
        val prefs = memoryStore.preferences.first()
        return buildString {
            append("💾 حافظه بلندمدت:\n")
            append("• نام: ${name ?: "—"}\n")
            append("• یادداشت: ${notes?.take(300) ?: "—"}\n")
            append("• اهداف: ${goals?.take(200) ?: "—"}\n")
            append("• ترجیحات: ${prefs?.take(200) ?: "—"}")
        }
    }

    private suspend fun dumpKnowledge(): String {
        val chunks = knowledgeStore.chunks.first()
        if (chunks.isEmpty()) {
            return "📚 پایگاه دانش خالی است.\nبا یادگیری خودکار، جستجوی آنلاین یا «یادت باشه …» پر می‌شود."
        }
        val sorted = chunks.sortedByDescending { it.confidence }
        return buildString {
            append("📚 پایگاه دانش (${chunks.size} مورد) — مرتب‌شده بر اساس اطمینان:\n")
            sorted.take(10).forEach {
                val pct = (it.confidence * 100).toInt()
                append("• [${pct}%] ${it.title}: ${it.content.take(90)}\n")
            }
        }
    }

    private suspend fun analyzeContradictions(): String {
        val chunks = knowledgeStore.chunks.first()
        if (chunks.size < 2) return "برای تشخیص تناقض حداقل ۲ تکه دانش لازم است."
        val analysis = contradictionDetector.analyze(chunks)
        return buildString {
            append("🔍 ${analysis.summary}\n")
            if (analysis.contradictions.isEmpty()) {
                append("تناقض جدی یافت نشد.")
            } else {
                analysis.contradictions.take(5).forEach { c ->
                    append("\n⚠️ شدت ${(c.severity * 100).toInt()}%:\n")
                    append("  الف) ${c.claimA.take(100)}\n")
                    append("  ب) ${c.claimB.take(100)}\n")
                }
            }
            if (analysis.uncertainClaims.isNotEmpty()) {
                append("\nادعاهای نامطمئن: ${analysis.uncertainClaims.size}")
            }
        }
    }

    private suspend fun reasonFromKnowledge(query: String): Pair<String, Float>? {
        val hits = knowledgeStore.search(query, limit = 5)
        if (hits.isEmpty()) return null

        val top = hits.first()
        if (top.confidence < 0.35f && hits.size == 1) {
            return buildString {
                append("📖 از پایگاه دانش (اطمینان پایین ${(top.confidence * 100).toInt()}%):\n")
                append("**${top.title}**\n${top.content}\n")
                append("\n⚠️ این مورد اطمینان کمی دارد؛ در صورت امکان با منبع دیگر تأیید کنید.")
            } to top.confidence
        }

        val avgConf = hits.map { it.confidence }.average().toFloat()
        return buildString {
            append("📖 پاسخ از هسته مستقل (اطمینان میانگین ${(avgConf * 100).toInt()}%):\n\n")
            hits.take(3).forEachIndexed { i, h ->
                val pct = (h.confidence * 100).toInt()
                append("${i + 1}. [${pct}%] **${h.title}**\n")
                append("   ${h.content.take(220)}\n")
                if (h.sourceUrl.isNotBlank()) append("   منبع: ${h.sourceUrl}\n")
                append("\n")
            }
            if (hits.size > 1) {
                append("— ترکیب ${hits.size} منبع از دانش اختصاصی شما")
            }
        } to avgConf.coerceIn(0.4f, 0.95f)
    }

    private fun help(): String = """
راهنمای هسته فوق‌هوشمند مستقل:

• سؤال از حافظه و دانش ذخیره‌شده
• «یادت باشه …» برای ذخیره دانش
• «وضعیت دانش» / «تناقض» برای بررسی پایگاه
• «خودترمیم» — تشخیص و رفع خودکار ضعف‌های هسته
• بدون اینترنت کامل کار می‌کند
• برای جستجوی جهان: نسخه آنلاین یا حالت Hybrid

At your service.
    """.trim()

    private fun fallback(text: String): String = """
🧠 هسته مستقل این درخواست را با دانش فعلی پوشش کامل نمی‌دهد.

پیشنهاد:
1. اگر آنلاین هستید: «جستجو کن ${text.take(40)}» یا حالت Hybrid
2. دانش مرتبط را با «یادت باشه …» ذخیره کنید
3. «خودترمیم» را اجرا کنید تا هسته خودش را تقویت کند
4. از نسخه آنلاین AURA بسته دانش بگیرید و همگام کنید

درخواست: «${text.take(100)}»
    """.trim()
}
