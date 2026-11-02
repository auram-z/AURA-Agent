package com.aura.agent.core

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aura.agent.learning.ContradictionDetector
import com.aura.agent.learning.KnowledgeStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

private val Context.coreHealStore: DataStore<Preferences> by preferencesDataStore(name = "aura_core_self_heal")

/**
 * خودترمیم فوق‌هوشمند هسته AURA.
 *
 * هسته خودش را پایش می‌کند، ضعف‌ها و باگ‌های رفتاری را تشخیص می‌دهد
 * و فیکس‌های امن (آستانه confidence، وزن جستجوی دانش، یادداشت prompt،
 * کاهش اطمینان دانش متناقض، ثبت یافته در پایگاه دانش) را اعمال می‌کند.
 *
 * هیچ تغییری در فایل‌های امنیتی یا build اعمال نمی‌شود.
 */
class CoreSelfHealer(
    private val context: Context,
    private val knowledgeStore: KnowledgeStore,
    private val contradictionDetector: ContradictionDetector = ContradictionDetector()
) {

    companion object {
        private const val TAG = "AuraCoreSelfHeal"
        private const val MAX_RECENT = 120
        private val KEY_CONF_THRESHOLD = floatPreferencesKey("conf_threshold")
        private val KEY_SEARCH_TITLE_BOOST = floatPreferencesKey("search_title_boost")
        private val KEY_SEARCH_CONTENT_BOOST = floatPreferencesKey("search_content_boost")
        private val KEY_HEAL_COUNT = intPreferencesKey("heal_count")
        private val KEY_LAST_REPORT = stringPreferencesKey("last_report")
        private val KEY_NOTES = stringPreferencesKey("heal_notes")

        /** آستانه پیش‌فرض پذیرش پاسخ LOCAL در HYBRID */
        const val DEFAULT_CONF_THRESHOLD = 0.70f
    }

    data class InferenceSample(
        val confidence: Float,
        val backend: BackendKind,
        val offline: Boolean,
        val wasFallback: Boolean,
        val userTextLen: Int,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class Finding(
        val id: String = UUID.randomUUID().toString(),
        val code: String,
        val severity: String,
        val title: String,
        val detail: String,
        val autoFixed: Boolean = false,
        val fixSummary: String = ""
    )

    data class HealReport(
        val findings: List<Finding>,
        val appliedFixes: List<String>,
        val confThreshold: Float,
        val knowledgeCount: Int,
        val avgConfidence: Float,
        val fallbackRate: Float,
        val summary: String
    )

    private val recent = ConcurrentLinkedQueue<InferenceSample>()
    private val mutex = Mutex()

    fun recordInference(resp: InferenceResponse, userText: String) {
        val sample = InferenceSample(
            confidence = resp.confidence,
            backend = resp.backend,
            offline = resp.offline,
            wasFallback = resp.confidence < 0.40f ||
                resp.text.contains("پوشش کامل نمی‌دهد") ||
                resp.text.contains("موقتاً پاسخ نداد"),
            userTextLen = userText.length
        )
        recent.add(sample)
        while (recent.size > MAX_RECENT) recent.poll()
    }

    suspend fun getConfidenceThreshold(): Float {
        val prefs = context.coreHealStore.data.first()
        return prefs[KEY_CONF_THRESHOLD] ?: DEFAULT_CONF_THRESHOLD
    }

    suspend fun getSearchBoosts(): Pair<Float, Float> {
        val prefs = context.coreHealStore.data.first()
        val title = prefs[KEY_SEARCH_TITLE_BOOST] ?: 3.0f
        val content = prefs[KEY_SEARCH_CONTENT_BOOST] ?: 1.0f
        return title to content
    }

    suspend fun lastReport(): String {
        return context.coreHealStore.data.first()[KEY_LAST_REPORT].orEmpty()
    }

    suspend fun healNotes(): String {
        return context.coreHealStore.data.first()[KEY_NOTES].orEmpty()
    }

    suspend fun diagnoseAndHeal(force: Boolean = false): HealReport = mutex.withLock {
        val samples = recent.toList()
        val chunks = knowledgeStore.chunks.first()
        val findings = mutableListOf<Finding>()
        val fixes = mutableListOf<String>()

        val avgConf = if (samples.isEmpty()) 0.75f
        else samples.map { it.confidence }.average().toFloat()
        val fallbackRate = if (samples.isEmpty()) 0f
        else samples.count { it.wasFallback }.toFloat() / samples.size
        val localShare = if (samples.isEmpty()) 1f
        else samples.count { it.backend == BackendKind.LOCAL }.toFloat() / samples.size

        if (samples.size >= 8 && fallbackRate >= 0.45f) {
            findings += Finding(
                code = "HIGH_FALLBACK",
                severity = "MEDIUM",
                title = "نرخ پاسخ ضعیف محلی بالا",
                detail = "از ${samples.size} استنتاج اخیر، ${(fallbackRate * 100).toInt()}% fallback ضعیف بود."
            )
        }

        if (samples.size >= 6 && avgConf < 0.50f) {
            findings += Finding(
                code = "LOW_AVG_CONF",
                severity = "MEDIUM",
                title = "میانگین اطمینان پایین",
                detail = "avgConfidence=${\"%.2f\".format(avgConf)}"
            )
        }

        if (chunks.size < 8) {
            findings += Finding(
                code = "KNOWLEDGE_POVERTY",
                severity = "LOW",
                title = "پایگاه دانش کم‌حجم",
                detail = "فقط ${chunks.size} تکه دانش — هسته محلی ضعیف عمل می‌کند."
            )
        }

        val analysis = if (chunks.size >= 2) contradictionDetector.analyze(chunks) else null
        val severeContradictions = analysis?.contradictions?.count { it.severity >= 0.6f } ?: 0
        if (severeContradictions >= 2) {
            findings += Finding(
                code = "KNOWLEDGE_CONTRADICTION",
                severity = "HIGH",
                title = "تناقض جدی در پایگاه دانش",
                detail = "$severeContradictions تناقض با شدت ≥ ۶۰٪"
            )
        }

        if (samples.none { it.backend == BackendKind.ON_DEVICE } && samples.size >= 5) {
            findings += Finding(
                code = "ONDEVICE_ABSENT",
                severity = "LOW",
                title = "موتور OnDevice فعال نیست",
                detail = "مدل محلی نصب نشده — فقط LOCAL و در صورت نیاز Remote."
            )
        }

        var currentThreshold = getConfidenceThreshold()

        val newThreshold = when {
            fallbackRate >= 0.50f && samples.size >= 8 -> (currentThreshold - 0.05f).coerceAtLeast(0.50f)
            avgConf < 0.45f && samples.size >= 6 -> (currentThreshold - 0.03f).coerceAtLeast(0.52f)
            avgConf > 0.85f && fallbackRate < 0.10f && samples.size >= 12 ->
                (currentThreshold + 0.02f).coerceAtMost(0.82f)
            else -> currentThreshold
        }
        if (kotlin.math.abs(newThreshold - currentThreshold) > 0.001f) {
            context.coreHealStore.edit { it[KEY_CONF_THRESHOLD] = newThreshold }
            fixes += "آستانه confidence: ${\"%.2f\".format(currentThreshold)} → ${\"%.2f\".format(newThreshold)}"
            currentThreshold = newThreshold
        }

        val (titleBoost, contentBoost) = getSearchBoosts()
        var newTitle = titleBoost
        var newContent = contentBoost
        if (chunks.size < 15 && fallbackRate > 0.30f) {
            newTitle = (titleBoost + 0.4f).coerceAtMost(5.0f)
            newContent = (contentBoost + 0.15f).coerceAtMost(2.0f)
        }
        if (kotlin.math.abs(newTitle - titleBoost) > 0.01f ||
            kotlin.math.abs(newContent - contentBoost) > 0.01f
        ) {
            context.coreHealStore.edit {
                it[KEY_SEARCH_TITLE_BOOST] = newTitle
                it[KEY_SEARCH_CONTENT_BOOST] = newContent
            }
            fixes += "وزن جستجو: title=${\"%.1f\".format(newTitle)} content=${\"%.1f\".format(newContent)}"
        }

        if (severeContradictions > 0 && analysis != null) {
            var demoted = 0
            analysis.contradictions
                .filter { it.severity >= 0.6f }
                .take(8)
                .forEach { c ->
                    runCatching {
                        knowledgeStore.markVerified(c.chunkIdA, 0.32f)
                        knowledgeStore.markVerified(c.chunkIdB, 0.32f)
                        demoted++
                    }
                }
            if (demoted > 0) {
                fixes += "کاهش اطمینان $demoted جفت دانش متناقض"
            }
        }

        if (findings.isNotEmpty() || fixes.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val body = buildString {
                append("خودتشخیص هسته AURA\n")
                findings.forEach { append("• [${it.severity}] ${it.title}: ${it.detail}\n") }
                if (fixes.isNotEmpty()) {
                    append("فیکس‌های اعمال‌شده:\n")
                    fixes.forEach { append("• $it\n") }
                }
            }
            runCatching {
                knowledgeStore.add(
                    KnowledgeStore.KnowledgeChunk(
                        id = UUID.randomUUID().toString(),
                        title = "خودترمیم هسته — ${java.text.SimpleDateFormat(\"yyyy-MM-dd HH:mm\", java.util.Locale.US).format(java.util.Date())}",
                        content = body.take(900),
                        source = "core_self_healer",
                        language = "fa",
                        tags = listOf("self_heal", "core", "auto"),
                        confidence = 0.85f,
                        claims = listOf(KnowledgeStore.Claim(body.take(200))),
                        timestamp = now,
                        lastVerifiedAt = now,
                        receivedAt = now
                    )
                )
            }
            fixes += "ثبت گزارش خودترمیم در پایگاه دانش"
        }

        if (findings.any { it.code == "HIGH_FALLBACK" || it.code == "KNOWLEDGE_POVERTY" }) {
            val note = "• وقتی دانش کافی نیست صریح بگو و پیشنهاد «یادت باشه» یا حالت Hybrid بده."
            context.coreHealStore.edit { prefs ->
                val prev = prefs[KEY_NOTES].orEmpty()
                val merged = (prev.lines().filter { it.isNotBlank() } + note)
                    .distinct()
                    .takeLast(20)
                    .joinToString("\n")
                prefs[KEY_NOTES] = merged
            }
            fixes += "به‌روزرسانی یادداشت‌های خودترمیم"
        }

        context.coreHealStore.edit {
            val n = (it[KEY_HEAL_COUNT] ?: 0) + 1
            it[KEY_HEAL_COUNT] = n
        }

        val summary = buildString {
            append("🧬 خودترمیم هسته AURA\n")
            append("• نمونه‌های اخیر: ${samples.size}\n")
            append("• میانگین confidence: ${\"%.0f\".format(avgConf * 100)}%\n")
            append("• نرخ fallback: ${\"%.0f\".format(fallbackRate * 100)}%\n")
            append("• دانش: ${chunks.size} تکه\n")
            append("• آستانه فعلی: ${\"%.2f\".format(currentThreshold)}\n")
            if (findings.isEmpty() && fixes.isEmpty()) {
                append("\n✅ هسته سالم — باگ رفتاری جدی یافت نشد.")
            } else {
                append("\n🔍 یافته‌ها (${findings.size}):\n")
                findings.forEach { append("  • [${it.severity}] ${it.title}\n") }
                if (fixes.isNotEmpty()) {
                    append("\n🛠️ فیکس‌های اعمال‌شده:\n")
                    fixes.forEach { append("  • $it\n") }
                }
            }
            append("\nAt your service.")
        }

        context.coreHealStore.edit { it[KEY_LAST_REPORT] = summary }
        Log.i(TAG, "Self-heal done: findings=${findings.size} fixes=${fixes.size}")

        HealReport(
            findings = findings,
            appliedFixes = fixes,
            confThreshold = currentThreshold,
            knowledgeCount = chunks.size,
            avgConfidence = avgConf,
            fallbackRate = fallbackRate,
            summary = summary
        )
    }

    suspend fun quickStatus(): String {
        val samples = recent.toList()
        val avg = if (samples.isEmpty()) "—" else "${\"%.0f\".format(samples.map { it.confidence }.average() * 100)}%"
        val fb = if (samples.isEmpty()) "—" else "${\"%.0f\".format(samples.count { it.wasFallback }.toFloat() / samples.size * 100)}%"
        val th = getConfidenceThreshold()
        val last = lastReport().take(200)
        return buildString {
            append("🧬 وضعیت خودترمیم:\n")
            append("• نمونه‌ها: ${samples.size}\n")
            append("• avg conf: $avg | fallback: $fb\n")
            append("• threshold: ${\"%.2f\".format(th)}\n")
            if (last.isNotBlank()) append("• آخرین گزارش: ${last.take(120)}…")
        }
    }
}
