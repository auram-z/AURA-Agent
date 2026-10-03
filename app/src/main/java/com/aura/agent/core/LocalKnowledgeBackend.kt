package com.aura.agent.core

import com.aura.agent.data.MemoryStore
import com.aura.agent.learning.KnowledgeStore
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * بک‌اند کاملاً آفلاین و اختصاصی:
 * حافظه + پایگاه دانش + پاسخ‌های قاعده‌محور.
 * هیچ تماس شبکه‌ای ندارد.
 */
class LocalKnowledgeBackend(
    private val memoryStore: MemoryStore,
    private val knowledgeStore: KnowledgeStore
) : InferenceBackend {

    override val kind = BackendKind.LOCAL
    override val displayName = "AURA Local Core"

    override fun isAvailable(): Boolean = true

    override suspend fun generate(request: InferenceRequest): Result<InferenceResponse> {
        val text = request.userText.trim()
        val lower = text.lowercase()

        val reply = when {
            lower.matches(Regex(".*(سلام|درود|hi|hello).*")) ->
                greeting()

            lower.contains("کی هستی") || lower.contains("تو کی") || lower.contains("who are you") ->
                identity()

            lower.contains("ساعت") || lower.contains("زمان") || lower.contains("time") ->
                "⏰ ${SimpleDateFormat("HH:mm — EEEE d MMMM yyyy", Locale("fa")).format(Date())}"

            lower.contains("حافظه") || lower.contains("چه یادت") || lower.contains("یادداشت") ->
                dumpMemory()

            lower.contains("دانش") || lower.contains("پایگاه دانش") ->
                dumpKnowledge()

            lower.contains("آفلاین") || lower.contains("offline") || lower.contains("بدون اینترنت") ->
                "📡 هسته محلی AURA بدون اینترنت فعال است.\nحافظه و دانش روی دستگاه شما ذخیره می‌شوند."

            lower.contains("کمک") || lower.contains("help") || lower == "راهنما" ->
                help()

            else -> {
                // جستجو در دانش اختصاصی
                val fromKb = searchKnowledge(text)
                if (fromKb != null) fromKb
                else fallback(text)
            }
        }

        return Result.success(
            InferenceResponse(
                text = reply,
                backend = BackendKind.LOCAL,
                offline = true,
                confidence = if (reply.startsWith("🧠")) 0.55f else 0.9f
            )
        )
    }

    private suspend fun greeting(): String {
        val name = memoryStore.userName.first()
        return if (!name.isNullOrBlank())
            "سلام $name. من AURA هستم — هسته محلی روی دستگاه شما. At your service."
        else
            "سلام. من AURA هستم — هسته محلی و اختصاصی شما. At your service."
    }

    private fun identity(): String = """
من **AURA Local Core** هستم.
• وابسته اجباری به هیچ API شرکتی نیستم
• حافظه و دانش روی همین دستگاه است
• آنلاین فقط وقتی شما بخواهید و کلید اختیاری تنظیم شده باشد
At your service.
    """.trim()

    private suspend fun dumpMemory(): String {
        val name = memoryStore.userName.first()
        val notes = memoryStore.notes.first()
        val goals = memoryStore.goals.first()
        return buildString {
            append("💾 حافظه بلندمدت:\n")
            append("• نام: ${name ?: "—"}\n")
            append("• یادداشت: ${notes?.take(300) ?: "—"}\n")
            append("• اهداف: ${goals?.take(200) ?: "—"}")
        }
    }

    private suspend fun dumpKnowledge(): String {
        val chunks = knowledgeStore.chunks.first()
        if (chunks.isEmpty()) return "📚 پایگاه دانش خالی است. با یادگیری خودکار یا تأیید شما پر می‌شود."
        return buildString {
            append("📚 پایگاه دانش (${chunks.size} مورد):\n")
            chunks.takeLast(8).forEach {
                append("• ${it.title}: ${it.content.take(100)}\n")
            }
        }
    }

    private suspend fun searchKnowledge(query: String): String? {
        val q = query.lowercase()
        val chunks = knowledgeStore.chunks.first()
        val hit = chunks.find {
            it.title.lowercase().contains(q.take(20)) ||
                it.content.lowercase().contains(q.take(30)) ||
                q.split(" ").any { w -> w.length > 3 && it.content.lowercase().contains(w) }
        } ?: return null
        return "📖 از پایگاه دانش:\n**${hit.title}**\n${hit.content}"
    }

    private fun help(): String = """
راهنمای هسته محلی:
• سؤال از حافظه / دانش ذخیره‌شده
• «یادت باشه …» برای ذخیره
• بدون اینترنت هم کار می‌کند
• برای استدلال پیچیده: حالت Hybrid + کلید API اختیاری
    """.trim()

    private fun fallback(text: String): String = """
🧠 هسته محلی این درخواست را با دانش فعلی پوشش کامل نمی‌دهد.

پیشنهاد:
1. اگر آنلاین هستید و کلید API تنظیم شده، حالت Hybrid پاسخ کامل‌تری می‌دهد.
2. یا دانش مرتبط را با «یادت باشه …» ذخیره کنید تا بعداً آفلاین هم بلد باشم.

درخواست شما: «${text.take(120)}»
    """.trim()
}
