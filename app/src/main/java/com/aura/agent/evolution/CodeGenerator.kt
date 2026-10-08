package com.aura.agent.evolution

import android.util.Log

/**
 * تولید اسکلت کد Kotlin بر اساس EvolutionPlan.
 * فاز E2: templateمحور، بدون وابستگی اجباری به Remote.
 * در صورت وجود مدل قوی‌تر می‌توان بعداً خروجی را با LLM غنی کرد.
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
                    // فاز E2: پچ حداقلی — افزودن کامنت و هوک
                    val content = patchStub(step.path, capability, step.rationale)
                    patches += CodePatch(
                        path = step.path,
                        action = PlanAction.PATCH,
                        content = content
                    )
                }
                PlanAction.DELETE, PlanAction.RENAME -> {
                    // در E2 حذف/تغییرنام فقط با تأیید و بدون محتوای مخرب
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

        val safeTitle = capability.title.replace('"', '\'')
        val safeDesc = capability.description.take(200).replace('"', '\'')

        return """
package $packageName

/**
 * تولیدشده توسط Self-Evolution Engine
 * قابلیت: $safeTitle
 * منبع: ${capability.source}
 * ریسک: ${capability.estimatedRisk}
 * دلیل: $rationale
 *
 * این اسکلت نقطهٔ شروع است؛ منطق کامل را در چرخه‌های بعدی تکمیل کنید.
 */
object $className {

    const val FEATURE_ID = "${capability.id}"
    const val FEATURE_TITLE = "$safeTitle"

    /** وضعیت فعال بودن قابلیت */
    @Volatile
    var enabled: Boolean = true

    fun description(): String = "$safeDesc"

    /**
     * نقطه ورود قابلیت — پیاده‌سازی واقعی در نسخه‌های بعدی تکامل.
     */
    fun execute(input: String = ""): String {
        if (!enabled) return "قابلیت «$safeTitle» غیرفعال است."
        return buildString {
            append("✅ [$className] $safeTitle\n")
            if (input.isNotBlank()) append("ورودی: ${input.take(120)}\n")
            append("اسکلت E2 — منطق دامنه را تکمیل کنید.")
        }
    }

    fun healthCheck(): Boolean = enabled
}
""".trim() + "\n"
    }

    private fun patchStub(path: String, capability: Capability, rationale: String): String {
        // محتوای کامل جایگزین برای فایل‌های workspace که از قبل وجود ندارند
        // اگر فایل موجود باشد، DeployGate می‌تواند merge کند؛ اینجا اسکلت امن می‌نویسیم
        return scaffoldKotlin(path, capability, "PATCH: $rationale")
    }
}
