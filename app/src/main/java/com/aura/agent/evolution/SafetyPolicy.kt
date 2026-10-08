package com.aura.agent.evolution

/**
 * قوانین قرمز Self-Evolution.
 * هیچ پچ یا planی بدون عبور از این لایه اعمال نمی‌شود.
 */
class SafetyPolicy {

    companion object {
        /** مسیرهای نسبی ممنوع (نسبت به ریشه پروژه / sandbox) */
        val FORBIDDEN_PATH_PREFIXES = listOf(
            "app/src/main/java/com/aura/agent/security/",
            "local.properties",
            "app/src/main/res/xml/",
            ".git/",
            "gradle.properties"
        )

        val FORBIDDEN_EXACT = setOf(
            "app/src/main/AndroidManifest.xml",
            "app/build.gradle.kts",
            "build.gradle.kts",
            "settings.gradle.kts"
        )

        /** حداکثر مجموع حجم محتوای پچ‌ها (بایت) */
        const val MAX_PATCH_TOTAL_BYTES = 50 * 1024

        /** حداکثر تعداد فایل در یک plan */
        const val MAX_FILES_PER_PLAN = 12
    }

    fun validatePlan(plan: EvolutionPlan): SafetyReport {
        val violations = mutableListOf<SafetyViolation>()

        val allPaths = (plan.targetFiles + plan.newFiles + plan.steps.map { it.path }).distinct()

        if (allPaths.size > MAX_FILES_PER_PLAN) {
            violations += SafetyViolation(
                path = "*",
                reason = "تعداد فایل‌ها (${allPaths.size}) از سقف $MAX_FILES_PER_PLAN بیشتر است",
                severity = RiskLevel.HIGH
            )
        }

        for (path in allPaths) {
            val normalized = path.replace('\\', '/').trimStart('/')
            if (FORBIDDEN_EXACT.any { normalized.equals(it, ignoreCase = true) }) {
                violations += SafetyViolation(
                    path = normalized,
                    reason = "فایل سیستمی/حساس — تغییر ممنوع بدون تأیید صریح سطح بالا",
                    severity = RiskLevel.CRITICAL
                )
            }
            for (prefix in FORBIDDEN_PATH_PREFIXES) {
                if (normalized.startsWith(prefix, ignoreCase = true) ||
                    normalized.contains("/security/", ignoreCase = true)
                ) {
                    violations += SafetyViolation(
                        path = normalized,
                        reason = "مسیر ممنوع امنیتی: $prefix",
                        severity = RiskLevel.CRITICAL
                    )
                }
            }
            if (normalized.contains("..")) {
                violations += SafetyViolation(
                    path = normalized,
                    reason = "path traversal ممنوع",
                    severity = RiskLevel.CRITICAL
                )
            }
        }

        for (step in plan.steps) {
            if (step.action == PlanAction.DELETE &&
                step.path.contains("security", ignoreCase = true)
            ) {
                violations += SafetyViolation(
                    path = step.path,
                    reason = "حذف فایل امنیتی ممنوع",
                    severity = RiskLevel.CRITICAL
                )
            }
        }

        val hasCritical = violations.any { it.severity == RiskLevel.CRITICAL }
        val hasHigh = violations.any { it.severity == RiskLevel.HIGH }

        return SafetyReport(
            ok = !hasCritical,
            violations = violations.distinctBy { it.path + it.reason },
            requiresConfirm = plan.requiresUserConfirm || hasHigh || hasCritical
        )
    }

    fun validatePatches(patches: List<CodePatch>): SafetyReport {
        val violations = mutableListOf<SafetyViolation>()
        var totalBytes = 0

        for (p in patches) {
            totalBytes += p.content?.length ?: 0
            val normalized = p.path.replace('\\', '/').trimStart('/')
            if (FORBIDDEN_EXACT.any { normalized.equals(it, ignoreCase = true) } ||
                FORBIDDEN_PATH_PREFIXES.any { normalized.startsWith(it, ignoreCase = true) }
            ) {
                violations += SafetyViolation(
                    path = normalized,
                    reason = "پچ روی مسیر ممنوع",
                    severity = RiskLevel.CRITICAL
                )
            }
            // شبکه ناامن در کد تولیدشده
            val body = p.content.orEmpty()
            if (body.contains("http://", ignoreCase = true) &&
                !body.contains("https://", ignoreCase = true)
            ) {
                // فقط اگر واقعاً URL با http باشد
                if (Regex("""http://[^\s\"']+""", RegexOption.IGNORE_CASE).containsMatchIn(body)) {
                    violations += SafetyViolation(
                        path = normalized,
                        reason = "استفاده از HTTP ناامن در کد",
                        severity = RiskLevel.HIGH
                    )
                }
            }
        }

        if (totalBytes > MAX_PATCH_TOTAL_BYTES) {
            violations += SafetyViolation(
                path = "*",
                reason = "حجم کل پچ‌ها ($totalBytes) از سقف $MAX_PATCH_TOTAL_BYTES بیشتر است",
                severity = RiskLevel.HIGH
            )
        }

        val hasCritical = violations.any { it.severity == RiskLevel.CRITICAL }
        return SafetyReport(
            ok = !hasCritical,
            violations = violations,
            requiresConfirm = violations.any { it.severity >= RiskLevel.HIGH }
        )
    }

    fun isPathAllowed(path: String): Boolean {
        val normalized = path.replace('\\', '/').trimStart('/')
        if (FORBIDDEN_EXACT.any { normalized.equals(it, ignoreCase = true) }) return false
        if (FORBIDDEN_PATH_PREFIXES.any { normalized.startsWith(it, ignoreCase = true) }) return false
        if (normalized.contains("..")) return false
        return true
    }
}
