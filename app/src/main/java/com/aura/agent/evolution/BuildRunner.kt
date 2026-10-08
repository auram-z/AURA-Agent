package com.aura.agent.evolution

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * اعتبارسنجی ساختاری Build روی دستگاه (فاز E3).
 * بدون JDK کامل: بررسی سینتکس سطحی Kotlin، package، و قوانین Safety.
 * در صورت وجود محیط CI/dev می‌توان بعداً gradle را وصل کرد.
 */
class BuildRunner(
    private val sandbox: ProjectSandbox,
    private val safety: SafetyPolicy = SafetyPolicy()
) {

    companion object {
        private const val TAG = "AuraBuild"
    }

    suspend fun build(patches: List<CodePatch>): BuildResult = withContext(Dispatchers.Default) {
        val t0 = System.currentTimeMillis()
        val errors = mutableListOf<String>()
        val logs = StringBuilder()

        if (patches.isEmpty()) {
            return@withContext BuildResult(
                success = false,
                durationMs = 0,
                logExcerpt = "هیچ پچی برای build نیست",
                errors = listOf("empty patches")
            )
        }

        val safetyReport = safety.validatePatches(patches)
        if (!safetyReport.ok) {
            errors += safetyReport.violations.map { "${it.path}: ${it.reason}" }
        }

        for (p in patches) {
            if (p.action == PlanAction.DELETE || p.action == PlanAction.RENAME) {
                logs.appendLine("skip structural check for ${p.action} ${p.path}")
                continue
            }
            val content = p.content.orEmpty()
            if (content.isBlank()) {
                errors += "${p.path}: محتوای خالی"
                continue
            }

            // نوشتن در sandbox
            val write = sandbox.writeFile(p.path, content)
            if (write.isFailure) {
                errors += "${p.path}: ${write.exceptionOrNull()?.message}"
                continue
            }

            // بررسی‌های سطحی Kotlin
            if (!content.contains("package ")) {
                errors += "${p.path}: فاقد package"
            }
            if (p.path.endsWith(".kt")) {
                val openBraces = content.count { it == '{' }
                val closeBraces = content.count { it == '}' }
                if (openBraces != closeBraces) {
                    errors += "${p.path}: تعداد { } نامتوازن ($openBraces/$closeBraces)"
                }
                // ممنوعیت‌های خطرناک
                if (Regex("""Runtime\.getRuntime\(\)\.exec""").containsMatchIn(content)) {
                    errors += "${p.path}: اجرای shell ممنوع"
                }
                if (Regex("""http://""", RegexOption.IGNORE_CASE).containsMatchIn(content) &&
                    Regex("""http://[^\s\"']+""", RegexOption.IGNORE_CASE).containsMatchIn(content)
                ) {
                    errors += "${p.path}: URL ناامن HTTP"
                }
            }
            logs.appendLine("OK structural: ${p.path} (${content.length} bytes)")
        }

        val duration = System.currentTimeMillis() - t0
        val success = errors.isEmpty()
        Log.i(TAG, "Build ${if (success) "OK" else "FAIL"} in ${duration}ms, errors=${errors.size}")

        BuildResult(
            success = success,
            durationMs = duration,
            apkPath = null, // فاز بعدی: مسیر APK واقعی از CI
            logExcerpt = logs.toString().take(2000),
            errors = errors
        )
    }
}
