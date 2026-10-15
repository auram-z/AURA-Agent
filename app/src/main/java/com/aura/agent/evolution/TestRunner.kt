package com.aura.agent.evolution

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * تست‌های smoke و ساختاری روی خروجی sandbox (فاز E3).
 */
class TestRunner(
    private val sandbox: ProjectSandbox
) {

    companion object {
        private const val TAG = "AuraTest"

        private val FORBIDDEN = listOf(
            Regex("""Runtime\.getRuntime\(\)\.exec"""),
            Regex("""http://[^\s\"']+""", RegexOption.IGNORE_CASE),
            Regex("""local\.properties""", RegexOption.IGNORE_CASE),
            Regex("""/security/""", RegexOption.IGNORE_CASE)
        )
    }

    suspend fun run(
        patches: List<CodePatch>,
        capability: Capability,
        testHints: List<String> = emptyList()
    ): TestResult = withContext(Dispatchers.Default) {
        val t0 = System.currentTimeMillis()
        var passed = 0
        var failed = 0
        val logs = StringBuilder()
        val metrics = mutableMapOf<String, Double>()

        for (p in patches) {
            if (p.action == PlanAction.DELETE) {
                passed++
                logs.appendLine("PASS delete scheduled: ${p.path}")
                continue
            }

            val bodyFromPatch = p.content.orEmpty()
            val body = if (bodyFromPatch.isNotBlank()) {
                bodyFromPatch
            } else {
                sandbox.readFile(p.path).getOrDefault("")
            }

            if (body.isBlank()) {
                failed++
                logs.appendLine("FAIL empty: ${p.path}")
                continue
            }

            // وجود فایل / محتوا
            if (body.contains(capability.id) || body.contains("FEATURE_TITLE") || body.length > 40) {
                passed++
                logs.appendLine("PASS present: ${p.path}")
            } else {
                failed++
                logs.appendLine("FAIL thin content: ${p.path}")
            }

            // package
            if (p.path.endsWith(".kt")) {
                if (body.contains("package ")) {
                    passed++
                    logs.appendLine("PASS package: ${p.path}")
                } else {
                    failed++
                    logs.appendLine("FAIL no package: ${p.path}")
                }

                // API surface
                if (body.contains("fun healthCheck") || body.contains("fun execute")) {
                    passed++
                    logs.appendLine("PASS API surface: ${p.path}")
                } else {
                    failed++
                    logs.appendLine("FAIL no execute/healthCheck: ${p.path}")
                }

                // braces
                val open = body.count { it == '{' }
                val close = body.count { it == '}' }
                if (open == close && open > 0) {
                    passed++
                    logs.appendLine("PASS braces: ${p.path}")
                } else {
                    failed++
                    logs.appendLine("FAIL braces $open/$close: ${p.path}")
                }

                // forbidden patterns
                val bad = FORBIDDEN.any { it.containsMatchIn(body) }
                if (bad) {
                    failed++
                    logs.appendLine("FAIL forbidden pattern: ${p.path}")
                } else {
                    passed++
                    logs.appendLine("PASS safe patterns: ${p.path}")
                }
            }
        }

        testHints.take(5).forEach { hint ->
            passed++
            logs.appendLine("PASS hint recorded: $hint")
        }

        val duration = System.currentTimeMillis() - t0
        val totalBytes = patches.sumOf { it.content?.length ?: 0 }.toDouble()
        metrics["patch_bytes"] = totalBytes
        metrics["latency_ms"] = duration.toDouble()
        metrics["files"] = patches.size.toDouble()
        metrics["confidence"] = if (passed + failed == 0) 0.0 else passed.toDouble() / (passed + failed)

        val success = failed == 0 && passed > 0
        Log.i(TAG, "Tests passed=$passed failed=$failed success=$success")

        TestResult(
            success = success,
            durationMs = duration,
            passed = passed,
            failed = failed,
            metrics = metrics,
            logExcerpt = logs.toString().take(2000)
        )
    }
}
