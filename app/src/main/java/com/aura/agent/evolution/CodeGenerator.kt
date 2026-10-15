package com.aura.agent.evolution

import android.util.Log

/**
 * تولید اسکلت کد Kotlin بر اساس EvolutionPlan.
 * فاز E2: templateمحور، بدون وابستگی اجباری به Remote.
 */
class CodeGenerator(
    private val safety: SafetyPolicy = SafetyPolicy()
) {

    companion object {
        private const val TAG = "AuraCodeGen"
    }

    fun generate(plan: EvolutionPlan, capability: Capability): Result<List<CodePatch>> {
        val patches = mutableListOf<CodePatch>()

        for (step in plan.steps) {
            if (!safety.isPathAllowed(step.path)) {
                return Result.failure(
                    SecurityException("مسیر غیرمجاز در تولید کد: ${step.path}")
                )
            }
            when (step.action) {
                PlanAction.CREATE -> {
                    val content = scaffoldKotlin(step.path, capability, step.rationale)
                    patches += CodePatch(
                        path = step.path,
                        action = PlanAction.CREATE,
                        content = content
                    )
                }
                PlanAction.PATCH -> {
                    val content = patchStub(step.path, capability, step.rationale)
                    patches += CodePatch(
                        path = step.path,
                        action = PlanAction.PATCH,
                        content = content
                    )
                }
                PlanAction.DELETE, PlanAction.RENAME -> {
                    if (capability.estimatedRisk >= RiskLevel.HIGH) {
                        return Result.failure(
                            SecurityException("حذف/تغییرنام برای ریسک بالا در E2 مجاز نیست: ${step.path}")
                        )
                    }
                    patches += CodePatch(path = step.path, action = step.action, content = null)
                }
            }
        }

        val report = safety.validatePatches(patches)
        if (!report.ok) {
            val msg = report.violations.joinToString("\n") { "• ${it.path}: ${it.reason}" }
            Log.w(TAG, "Patches rejected:\n$msg")
            return Result.failure(SecurityException("پچ‌ها رد شدند:\n$msg"))
        }

        Log.i(TAG, "Generated ${patches.size} patches for ${capability.id}")
        return Result.success(patches)
    }

    private fun scaffoldKotlin(path: String, capability: Capability, rationale: String): String {
        val className = path.substringAfterLast('/').removeSuffix(".kt")
            .ifBlank { "GeneratedFeature" }
        val packageName = path
            .removePrefix("app/src/main/java/")
            .substringBeforeLast('/')
            .replace('/', '.')
            .ifBlank { "com.aura.agent.evolution" }

        val module = when {
            path.contains("/learning/") -> "learning"
            path.contains("/media/") -> "media"
            path.contains("/trading/") -> "trading"
            path.contains("/voice/") -> "voice"
            path.contains("/core/") -> "core"
            else -> "ai"
        }

        val safeTitle = capability.title.replace('"', '\'').take(80)
        val safeDesc = capability.description.take(200).replace('"', '\'')

        val extraApi = when (module) {
            "learning" -> """
    fun learnHint(text: String): String {
        if (!enabled) return "غیرفعال"
        return "یادگیری ثبت شد: ${'$'}{text.take(80)}"
    }
"""
            "media" -> """
    fun processMediaHint(kind: String = "text"): String {
        if (!enabled) return "غیرفعال"
        return "آماده پردازش رسانه ($kind) — اسکلت E2"
    }
"""
            "trading" -> """
    fun paperSignalHint(symbol: String = "BTC"): String {
        if (!enabled) return "غیرفعال"
        return "سیگنال کاغذی برای $symbol — فقط شبیه‌سازی، بدون LIVE"
    }
"""
            "voice" -> """
    fun voiceHint(): String {
        if (!enabled) return "غیرفعال"
        return "هوک صوتی آماده — STT/TTS را در چرخه بعدی وصل کنید"
    }
"""
            else -> """
    fun handle(input: String): String = execute(input)
"""
        }

        return """
package $packageName

/**
 * تولیدشده توسط Self-Evolution Engine
 * قابلیت: $safeTitle
 * منبع: ${capability.source}
 * ریسک: ${capability.estimatedRisk}
 * دلیل: $rationale
 * ماژول: $module
 *
 * اسکلت امن — منطق دامنه را در چرخه‌های بعدی تکمیل کنید.
 * بدون شبکه ناامن، بدون Runtime.exec، بدون دسترسی به security/.
 */
object $className {

    const val FEATURE_ID = "${capability.id}"
    const val FEATURE_TITLE = "$safeTitle"
    const val MODULE = "$module"

    @Volatile
    var enabled: Boolean = true

    fun description(): String = "$safeDesc"

    fun execute(input: String = ""): String {
        if (!enabled) return "قابلیت «$safeTitle» غیرفعال است."
        return buildString {
            append("✅ [$className] $safeTitle\n")
            append("ماژول: $module | ریسک: ${capability.estimatedRisk}\n")
            if (input.isNotBlank()) append("ورودی: ${'$'}{input.take(120)}\n")
            append("اسکلت E2 — منطق دامنه را تکمیل کنید.")
        }
    }

    fun healthCheck(): Boolean = enabled

$extraApi
}
""".trim() + "\n"
    }

    private fun patchStub(path: String, capability: Capability, rationale: String): String {
        return scaffoldKotlin(path, capability, "PATCH: $rationale")
    }
}
