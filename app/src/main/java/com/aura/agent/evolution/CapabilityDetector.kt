package com.aura.agent.evolution

import android.util.Log
import com.aura.agent.learning.FeedbackStore
import com.aura.agent.learning.KnowledgeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * تشخیص قابلیت‌ها / نیازهای جدید از منابع مختلف.
 * فاز E1: USER_REQUEST، FEEDBACK_PATTERN، KNOWLEDGE_GAP، SCHEDULED_SCAN سبک.
 */
class CapabilityDetector(
    private val feedbackStore: FeedbackStore,
    private val knowledgeStore: KnowledgeStore,
    private val config: EvolutionConfig
) {

    companion object {
        private const val TAG = "AuraCapDetect"

        /** الگوهای کلیدی برای درخواست صریح قابلیت */
        private val REQUEST_HINTS = listOf(
            "اضافه کن", "قابلیت", "پیاده", "پیاده‌سازی", "feature",
            "بساز", "پشتیبانی از", "بتونم", "می‌خوام بتونم",
            "add", "implement", "support"
        )

        private val MODULE_KEYWORDS = mapOf(
            "voice" to listOf("صوت", "صدا", "گفتار", "voice", "speech", "tts", "stt"),
            "media" to listOf("زیرنویس", "ترجمه فیلم", "تصویر", "ocr", "subtitle", "translate media"),
            "trading" to listOf("معامله", "بورس", "ترید", "trading", "backtest"),
            "learning" to listOf("یادگیری", "دانش", "حافظه", "fine-tune", "learning"),
            "ai" to listOf("جستجو", "search", "مدل", "prompt", "agent"),
            "core" to listOf("آفلاین", "محلی", "on-device", "backend", "هسته")
        )
    }

    /**
     * تشخیص از متن کاربر (دستور صریح).
     */
    fun fromUserRequest(userText: String): Capability? {
        val text = userText.trim()
        if (text.length < 8) return null
        val lower = text.lowercase()

        val looksLikeRequest = REQUEST_HINTS.any { lower.contains(it) }
        if (!looksLikeRequest) return null

        val modules = detectModules(lower)
        val risk = when {
            lower.contains("امنیت") || lower.contains("security") ||
                lower.contains("کلید") || lower.contains("api key") -> RiskLevel.CRITICAL
            lower.contains("حذف") || lower.contains("delete") ||
                lower.contains("شبکه") || lower.contains("permission") -> RiskLevel.HIGH
            modules.contains("trading") -> RiskLevel.HIGH
            else -> RiskLevel.MEDIUM
        }

        // CRITICAL فقط از کاربر — ولی با risk مشخص
        return Capability(
            title = text.take(80).ifBlank { "درخواست کاربر" },
            description = text.take(500),
            source = CapabilitySource.USER_REQUEST,
            priority = if (risk == RiskLevel.CRITICAL) 9 else 7,
            estimatedRisk = risk,
            suggestedModules = modules.ifEmpty { listOf("ai") }
        )
    }

    /**
     * اسکن بازخوردها برای الگوهای تکراری اصلاح.
     */
    suspend fun fromFeedbackPatterns(): List<Capability> = withContext(Dispatchers.Default) {
        val entries = feedbackStore.entries.first()
        val corrections = entries.mapNotNull { it.correction?.trim()?.takeIf { c -> c.length >= 10 } }
        if (corrections.isEmpty()) return@withContext emptyList()

        // گروه‌بندی ساده بر اساس کلمات مشترک
        val groups = mutableMapOf<String, MutableList<String>>()
        for (c in corrections.takeLast(50)) {
            val key = c.lowercase().split(Regex("\\s+"))
                .filter { it.length > 3 }
                .take(3)
                .joinToString(" ")
                .ifBlank { c.take(20).lowercase() }
            groups.getOrPut(key) { mutableListOf() }.add(c)
        }

        groups.filter { it.value.size >= 2 }.map { (key, samples) ->
            Capability(
                title = "بهبود از بازخورد: ${key.take(40)}",
                description = "تکرار اصلاح (${samples.size} بار). نمونه: ${samples.last().take(200)}",
                source = CapabilitySource.FEEDBACK_PATTERN,
                priority = 6,
                estimatedRisk = RiskLevel.LOW,
                suggestedModules = detectModules(samples.joinToString(" ").lowercase())
                    .ifEmpty { listOf("learning", "ai") }
            )
        }.take(3)
    }

    /**
     * gap دانش: اگر دانش ذخیره‌شده کم است و موضوع پرتکرار در عنوان‌ها نیست.
     * فاز E1 سبک — فقط سیگنال کمبود کلی.
     */
    suspend fun fromKnowledgeGaps(): List<Capability> = withContext(Dispatchers.Default) {
        val chunks = knowledgeStore.chunks.first()
        if (chunks.size >= 20) return@withContext emptyList()

        listOf(
            Capability(
                title = "گسترش پایگاه دانش",
                description = "تعداد دانش ذخیره‌شده کم است (${chunks.size}). پیشنهاد: استخراج و ذخیره دانش از مکالمات بیشتر.",
                source = CapabilitySource.KNOWLEDGE_GAP,
                priority = 3,
                estimatedRisk = RiskLevel.LOW,
                suggestedModules = listOf("learning")
            )
        )
    }

    /**
     * اجرای کامل تشخیص بر اساس trigger.
     */
    suspend fun detect(
        trigger: CapabilitySource = CapabilitySource.SCHEDULED_SCAN,
        userText: String? = null
    ): List<Capability> = withContext(Dispatchers.Default) {
        val maxCaps = config.getMaxCapabilitiesPerCycle()
        val found = mutableListOf<Capability>()

        when (trigger) {
            CapabilitySource.USER_REQUEST -> {
                userText?.let { fromUserRequest(it) }?.let { found += it }
            }
            CapabilitySource.FEEDBACK_PATTERN -> {
                found += fromFeedbackPatterns()
            }
            CapabilitySource.KNOWLEDGE_GAP -> {
                found += fromKnowledgeGaps()
            }
            CapabilitySource.SCHEDULED_SCAN, CapabilitySource.MODEL_SIGNAL, CapabilitySource.RUNTIME_ERROR -> {
                userText?.let { fromUserRequest(it) }?.let { found += it }
                found += fromFeedbackPatterns()
                found += fromKnowledgeGaps()
            }
        }

        // CRITICAL فقط از USER_REQUEST
        val filtered = found.filter {
            it.estimatedRisk != RiskLevel.CRITICAL || it.source == CapabilitySource.USER_REQUEST
        }

        val sorted = filtered
            .sortedWith(
                compareByDescending<Capability> { it.priority }
                    .thenBy { it.estimatedRisk.ordinal }
            )
            .distinctBy { it.title.take(40).lowercase() }
            .take(maxCaps)

        Log.i(TAG, "Detected ${sorted.size} capabilities (trigger=$trigger)")
        sorted
    }

    private fun detectModules(lower: String): List<String> {
        return MODULE_KEYWORDS.filter { (_, keys) ->
            keys.any { lower.contains(it) }
        }.keys.toList()
    }
}
