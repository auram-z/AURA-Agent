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

            // امنیت: ماژول security فقط با CRITICAL و تأیید — عملاً در plan خالی می‌ماند مگر LOW path
            if (mod == "security" && capability.estimatedRisk != RiskLevel.CRITICAL) {
                continue
            }
            if (mod == "security") {
                // عمداً step نمی‌سازیم؛ SafetyPolicy رد می‌کند اگر path اضافه شود
                continue
            }

            val featureSlug = slugify(capability.title)
            val newFile = "$basePath/${featureSlug.replaceFirstChar { it.uppercase() }}Feature.kt"

            steps += PlanStep(
                order = order++,
                action = PlanAction.CREATE,
                path = newFile,
                rationale = "پیاده‌سازی اسکلت قابلیت «${capability.title}» در ماژول $mod"
            )
            newFiles += newFile

            // در صورت وجود فایل‌های workspace مرتبط، PATCH پیشنهاد بده
            // (فاز E1: فقط اگر قبلاً در sandbox نوشته شده باشند — لیست در engine پر می‌شود)

            testHints += "unit: ${featureSlug} در $mod"
        }

        if (steps.isEmpty()) {
            // حداقل یک فایل در evolution یا ai
            val fallback = "app/src/main/java/com/aura/agent/ai/${slugify(capability.title).replaceFirstChar { it.uppercase() }}Capability.kt"
            steps += PlanStep(
                order = 1,
                action = PlanAction.CREATE,
                path = fallback,
                rationale = "مسیر پیش‌فرض برای قابلیت بدون ماژول مشخص"
            )
            newFiles += fallback
            testHints += "smoke: بارگذاری قابلیت جدید"
        }

        // برای risk بالا همیشه تأیید
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

        // اگر نیاز به تأیید دارد، plan را برمی‌گردانیم ولی engine باید ConfirmationGate را صدا بزند
        Log.i(TAG, "Plan ready for ${capability.id}: ${steps.size} steps, confirm=${plan.requiresUserConfirm}")
        return Result.success(
            if (report.requiresConfirm) plan.copy(requiresUserConfirm = true) else plan
        )
    }

    /** افزودن PATCH برای فایل‌های موجود در workspace که با ماژول هم‌خوان‌اند */
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

    private fun slugify(title: String): String {
        val ascii = title.lowercase()
            .replace(Regex("[^a-z0-9ا-ی\\s-]"), "")
            .trim()
            .split(Regex("\\s+"))
            .take(4)
            .joinToString("") { part ->
                // برای فارسی یک نام لاتین ساده
                part.map { ch ->
                    when (ch) {
                        in 'a'..'z', in '0'..'9' -> ch
                        else -> null
                    }
                }.filterNotNull().joinToString("").ifBlank { "X" }
            }
        val base = ascii.filter { it.isLetterOrDigit() }.take(32)
        return if (base.isBlank()) "NewFeature" else base.replaceFirstChar { it.uppercase() }
    }
}
