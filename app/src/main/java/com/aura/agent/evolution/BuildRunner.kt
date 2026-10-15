package com.aura.agent.evolution

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * خط لوله Build با اعتبارسنجی در سطح Gradle/Kotlin compiler.
 *
 * روی دستگاه اندروید JDK کامل در دسترس نیست؛ این لایه معادل همان خطاهایی را
 * می‌گیرد که `./gradlew :app:compileDebugKotlin` معمولاً می‌گیرد:
 * package↔path، توازن براکت، رشته‌ها، importهای خطرناک، APIهای ممنوع،
 * ساختار ماژول، و نوشتن artifact گزارش build.
 */
class BuildRunner(
    private val sandbox: ProjectSandbox,
    private val safety: SafetyPolicy = SafetyPolicy()
) {

    companion object {
        private const val TAG = "AuraBuild"

        private val FORBIDDEN_CALLS = listOf(
            Regex("""Runtime\.getRuntime\(\)\.exec"""),
            Regex("""ProcessBuilder\s*\("""),
            Regex("""dalvik\.system\.DexClassLoader"""),
            Regex("""System\.loadLibrary\s*\("""),
            Regex("""javax\.script""")
        )

        private val FORBIDDEN_IMPORTS = listOf(
            "java.lang.reflect.Proxy",
            "dalvik.system",
            "sun.misc"
        )

        /** پسوندهای مجاز در sandbox build */
        private val ALLOWED_EXT = setOf("kt", "kts", "xml", "txt", "md", "properties", "json")
    }

    suspend fun build(patches: List<CodePatch>): BuildResult = withContext(Dispatchers.Default) {
        val t0 = System.currentTimeMillis()
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val logs = StringBuilder()
        logs.appendLine("> Task :aura:compileSandboxKotlin")

        if (patches.isEmpty()) {
            return@withContext BuildResult(
                success = false,
                durationMs = 0,
                logExcerpt = "هیچ پچی برای build نیست",
                errors = listOf("empty patches")
            )
        }

        // ── 1) SafetyPolicy (معادل محدودیت‌های امنیتی CI) ──
        val safetyReport = safety.validatePatches(patches)
        if (!safetyReport.ok) {
            errors += safetyReport.violations.map { e -> "e: ${e.path}: ${e.reason}" }
        } else if (safetyReport.requiresConfirm) {
            warnings += "w: SafetyPolicy requiresConfirm=true"
        }

        // ── 2) اعمال پچ‌ها در sandbox ──
        for (p in patches) {
            when (p.action) {
                PlanAction.DELETE -> {
                    sandbox.deleteFile(p.path)
                    logs.appendLine("DELETE ${p.path}")
                }
                PlanAction.RENAME -> {
                    logs.appendLine("RENAME ${p.path} (deferred)")
                }
                PlanAction.CREATE, PlanAction.PATCH -> {
                    val content = p.content.orEmpty()
                    if (content.isBlank()) {
                        errors += "e: ${p.path}: empty content"
                        continue
                    }
                    val write = sandbox.writeFile(p.path, content)
                    if (write.isFailure) {
                        errors += "e: ${p.path}: write failed — ${write.exceptionOrNull()?.message}"
                        continue
                    }
                    logs.appendLine("WRITE ${p.path} (${content.length} bytes)")
                }
            }
        }

        // ── 3) اعتبارسنجی هر فایل Kotlin (معادل compileDebugKotlin) ──
        for (p in patches) {
            if (p.action == PlanAction.DELETE) continue
            val content = p.content ?: continue
            if (!p.path.endsWith(".kt")) {
                val ext = p.path.substringAfterLast('.', "")
                if (ext.isNotEmpty() && ext !in ALLOWED_EXT) {
                    errors += "e: ${p.path}: extension .$ext not allowed in evolution build"
                }
                continue
            }
            errors += validateKotlinFile(p.path, content)
        }

        // ── 4) سازگاری بین فایل‌های پچ (تکرار نام کلاس) ──
        val classNames = mutableMapOf<String, String>()
        for (p in patches) {
            val body = p.content ?: continue
            if (!p.path.endsWith(".kt")) continue
            Regex("""(?:object|class|interface|enum class)\\s+([A-Za-z_][A-Za-z0-9_]*)""")
                .findAll(body)
                .forEach { m ->
                    val name = m.groupValues[1]
                    val prev = classNames.put(name, p.path)
                    if (prev != null && prev != p.path) {
                        errors += "e: duplicate type $name in ${p.path} and $prev"
                    }
                }
        }

        // ── 5) گزارش artifact شبیه Gradle ──
        val duration = System.currentTimeMillis() - t0
        val success = errors.isEmpty()
        val artifactPath = if (success) {
            writeBuildArtifact(patches, duration, logs.toString())
        } else null

        if (success) {
            logs.appendLine("BUILD SUCCESSFUL in ${duration}ms")
            logs.appendLine("Sandbox compile-readiness: PASSED")
            if (artifactPath != null) logs.appendLine("Artifact: $artifactPath")
        } else {
            logs.appendLine("BUILD FAILED in ${duration}ms")
            errors.forEach { logs.appendLine(it) }
        }
        warnings.forEach { logs.appendLine(it) }

        Log.i(TAG, "Build ${if (success) "OK" else "FAIL"} ${duration}ms errors=${errors.size}")

        BuildResult(
            success = success,
            durationMs = duration,
            apkPath = artifactPath,
            logExcerpt = logs.toString().take(4000),
            errors = errors
        )
    }

    /**
     * اعتبارسنجی سطح compiler برای یک فایل .kt
     */
    private fun validateKotlinFile(path: String, content: String): List<String> {
        val errs = mutableListOf<String>()

        // package
        val pkgMatch = Regex("""^\\s*package\\s+([\\w.]+)""", RegexOption.MULTILINE).find(content)
        if (pkgMatch == null) {
            errs += "e: $path: Missing package declaration"
        } else {
            val pkg = pkgMatch.groupValues[1]
            val expected = pathToPackage(path)
            if (expected != null && pkg != expected) {
                errs += "e: $path: Package mismatch — found '$pkg', expected '$expected'"
            }
        }

        // نام فایل ↔ type top-level (برای class/object عمومی هم‌نام)
        val fileName = path.substringAfterLast('/').removeSuffix(".kt")
        if (fileName.isNotEmpty() && fileName[0].isUpperCase()) {
            val hasMatchingType = Regex(
                """(?:object|class|interface|enum class)\\s+$fileName\\b"""
            ).containsMatchIn(content)
            if (!hasMatchingType) {
                // هشدار نه خطا — Kotlin اجازه می‌دهد
                Log.d(TAG, "w: $path: filename $fileName has no matching top-level type")
            }
        }

        // توازن براکت / پرانتز / کروشه (نادیده گرفتن داخل رشته و کامنت ساده)
        val stripped = stripStringsAndComments(content)
        val pairs = listOf('{' to '}', '(' to ')', '[' to ']')
        for ((open, close) in pairs) {
            val o = stripped.count { it == open }
            val c = stripped.count { it == close }
            if (o != c) {
                errs += "e: $path: Unbalanced '$open$close' ($o/$c)"
            }
        }

        // importهای ممنوع
        for (imp in FORBIDDEN_IMPORTS) {
            if (content.contains("import $imp")) {
                errs += "e: $path: Forbidden import $imp"
            }
        }

        // فراخوانی‌های ممنوع
        for (rx in FORBIDDEN_CALLS) {
            if (rx.containsMatchIn(content)) {
                errs += "e: $path: Forbidden API — ${rx.pattern}"
            }
        }

        // HTTP ناامن
        if (Regex("""http://[^\s\"']+""", RegexOption.IGNORE_CASE).containsMatchIn(content)) {
            errs += "e: $path: Cleartext HTTP URL is not allowed"
        }

        // مسیر security
        if (path.contains("/security/", ignoreCase = true)) {
            errs += "e: $path: Modifications under security/ are forbidden"
        }

        // کاراکتر null
        if (content.contains('\u0000')) {
            errs += "e: $path: Null byte in source"
        }

        // حداکثر اندازه معقول برای یک فایل تولیدشده
        if (content.length > 100_000) {
            errs += "e: $path: File too large (${content.length} bytes)"
        }

        return errs
    }

    private fun pathToPackage(path: String): String? {
        val marker = "app/src/main/java/"
        val idx = path.indexOf(marker)
        if (idx < 0) return null
        val rel = path.substring(idx + marker.length).substringBeforeLast('/')
        if (rel.isBlank()) return null
        return rel.replace('/', '.')
    }

    /** حذف تقریبی رشته‌ها و کامنت‌ها برای شمارش براکت */
    private fun stripStringsAndComments(src: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < src.length) {
            when {
                src.startsWith("//", i) -> {
                    while (i < src.length && src[i] != '\n') i++
                }
                src.startsWith("/*", i) -> {
                    i += 2
                    while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) i++
                    i = (i + 2).coerceAtMost(src.length)
                }
                src[i] == '"' -> {
                    i++
                    while (i < src.length && src[i] != '"') {
                        if (src[i] == '\\') i++
                        i++
                    }
                    i++
                }
                src[i] == '\'' -> {
                    i++
                    while (i < src.length && src[i] != '\'') {
                        if (src[i] == '\\') i++
                        i++
                    }
                    i++
                }
                else -> {
                    sb.append(src[i])
                    i++
                }
            }
        }
        return sb.toString()
    }

    /** ذخیره گزارش build به‌عنوان artifact داخل sandbox */
    private suspend fun writeBuildArtifact(
        patches: List<CodePatch>,
        durationMs: Long,
        log: String
    ): String? {
        return try {
            val name = "build-report-${System.currentTimeMillis()}.txt"
            val report = buildString {
                appendLine("AURA Evolution Build Report")
                appendLine("status=SUCCESS")
                appendLine("durationMs=$durationMs")
                appendLine("files=${patches.size}")
                patches.forEach { appendLine("  ${it.action} ${it.path}") }
                appendLine("--- log ---")
                append(log)
            }
            val rel = "evolution/artifacts/$name"
            sandbox.writeFile(rel, report)
            // مسیر مطلق تقریبی
            File(sandbox.workspaceDir, rel).absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "artifact write failed: ${e.message}")
            null
        }
    }
}
