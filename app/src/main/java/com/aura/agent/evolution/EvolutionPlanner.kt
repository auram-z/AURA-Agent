package com.aura.agent.evolution

import android.util.Log

/**
 * تبدیل Capability به EvolutionPlan.
 * فاز E1: برنامه‌ریز مبتنی بر قوانین + نگاشت ماژول‌ها (بدون نیاز اجباری به Remote).
 */
class EvolutionPlanner(
    private val sandbox: ProjectSandbox,
    private val safety: SafetyPolicy = SafetyPolicy()
) {

    companion object {
        private const val TAG = "AuraEvoPlan"

        /** نگاشت ساده حروف فارسی به لاتین برای نام کلاس امن */
        private val FA_MAP = mapOf(
            'ا' to "a", 'آ' to "a", 'ب' to "b", 'پ' to "p", 'ت' to "t", 'ث' to "s",
            'ج' to "j", 'چ' to "ch", 'ح' to "h", 'خ' to "kh", 'د' to "d", 'ذ' to "z",
            'ر' to "r", 'ز' to "z", 'ژ' to "zh", 'س' to "s", 'ش' to "sh", 'ص' to "s",
            'ض' to "z", 'ط' to "t", 'ظ' to "z", 'ع' to "a", 'غ' to "gh", 'ف' to "f",
            'ق' to "gh", 'ک' to "k", 'گ' to "g", 'ل' to "l", 'م' to "m", 'ن' to "n",
            'و' to "v", 'ه' to "h", 'ی' to "y", 'ئ' to "y", 'ة' to "h", 'ي' to "y"
        )
    }

    fun plan(capability: Capability): Result<EvolutionPlan> {
        val modules = capability.suggestedModules
            .ifEmpty { listOf("ai") }
            .map { it.lowercase() }
            .distinct()

        val steps = mutableListOf<PlanStep>()
        val targetFiles = mutableListOf<String>()
        val newFiles = mutableListOf<String>()
        val testHints = mutableListOf<String>()
        var order = 1

        for (mod in modules) {
            val basePath = sandbox.pathForModule(mod) ?: continue

            if (mod == "security") {
                // ماژول security هرگز در plan خودکار قرار نمی‌گیرد
                continue
            }

            val featureSlug = slugify(capability.title)
            val newFile = "$basePath/${featureSlug}Feature.kt"

            steps += PlanStep(
                order = order++,
                action = PlanAction.CREATE,
                path = newFile,
                rationale = "پیاده‌سازی اسکلت قابلیت «${capability.title}» در ماژول $mod"
            )
            newFiles += newFile
            testHints += "unit: $featureSlug در $mod"
        }

        if (steps.isEmpty()) {
            val fallbackName = slugify(capability.title)
            val fallback = "app/src/main/java/com/aura/agent/ai/${fallbackName}Capability.kt"
            steps += PlanStep(
                order = 1,
                action = PlanAction.CREATE,
                path = fallback,
                rationale = "مسیر پیش‌فرض برای قابلیت بدون ماژول مشخص"
            )
            newFiles += fallback
            testHints += "smoke: بارگذاری قابلیت جدید"
        }

        val requiresConfirm = capability.estimatedRisk >= RiskLevel.MEDIUM ||
            capability.source == CapabilitySource.USER_REQUEST

        val plan = EvolutionPlan(
            capabilityId = capability.id,
            steps = steps.sortedBy { it.order },
            targetFiles = targetFiles.distinct(),
            newFiles = newFiles.distinct(),
            testHints = testHints.distinct(),
            requiresUserConfirm = requiresConfirm,
            summary = buildString {
                append("طرح برای: ${capability.title}\n")
                append("منبع: ${capability.source} | ریسک: ${capability.estimatedRisk}\n")
                append("گام‌ها: ${steps.size} | فایل‌های جدید: ${newFiles.size}\n")
                steps.forEach { append("  ${it.order}. ${it.action} ${it.path}\n") }
            }
        )

        val report = safety.validatePlan(plan)
        if (!report.ok) {
            val msg = report.violations.joinToString("\n") { "• ${it.path}: ${it.reason}" }
            Log.w(TAG, "Plan rejected by SafetyPolicy:\n$msg")
            return Result.failure(
                SecurityException("طرح توسط SafetyPolicy رد شد:\n$msg")
            )
        }

        Log.i(TAG, "Plan ready for ${capability.id}: ${steps.size} steps, confirm=${plan.requiresUserConfirm}")
        return Result.success(
            if (report.requiresConfirm) plan.copy(requiresUserConfirm = true) else plan
        )
    }

    suspend fun enrichWithWorkspace(plan: EvolutionPlan, capability: Capability): EvolutionPlan {
        val files = sandbox.listWorkspaceFiles()
        if (files.isEmpty()) return plan

        val extraSteps = mutableListOf<PlanStep>()
        var order = (plan.steps.maxOfOrNull { it.order } ?: 0) + 1
        val modules = capability.suggestedModules.map { it.lowercase() }

        for (mod in modules) {
            val base = sandbox.pathForModule(mod) ?: continue
            val related = files.filter { it.startsWith(base) || it.contains("/$mod/") }
            for (rel in related.take(3)) {
                if (plan.steps.any { it.path == rel }) continue
                if (!sandbox.isPathAllowed(rel)) continue
                extraSteps += PlanStep(
                    order = order++,
                    action = PlanAction.PATCH,
                    path = rel,
                    rationale = "هماهنگی با فایل موجود workspace برای ${capability.title}"
                )
            }
        }

        if (extraSteps.isEmpty()) return plan

        val updated = plan.copy(
            steps = plan.steps + extraSteps,
            targetFiles = (plan.targetFiles + extraSteps.map { it.path }).distinct(),
            summary = plan.summary + "\n+ ${extraSteps.size} پچ workspace"
        )
        val report = safety.validatePlan(updated)
        return if (report.ok) updated else plan
    }

    /**
     * نام کلاس امن ASCII از عنوان (فارسی یا انگلیسی).
     */
    private fun slugify(title: String): String {
        val sb = StringBuilder()
        for (ch in title.trim()) {
            when {
                ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' -> sb.append(ch)
                ch in FA_MAP -> sb.append(FA_MAP[ch])
                ch.isWhitespace() || ch == '-' || ch == '_' -> {
                    if (sb.isNotEmpty() && sb.last() != '_') sb.append('_')
                }
            }
            if (sb.length >= 28) break
        }
        var base = sb.toString().trim('_')
        if (base.isBlank()) {
            base = "Feat" + Integer.toHexString(title.hashCode()).takeLast(6)
        }
        // PascalCase
        return base.split('_')
            .filter { it.isNotBlank() }
            .joinToString("") { part ->
                part.replaceFirstChar { c -> c.uppercaseChar() }
            }
            .take(32)
            .ifBlank { "NewFeature" }
    }
}
