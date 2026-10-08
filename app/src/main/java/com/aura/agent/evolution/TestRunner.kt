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

        // 1) فایل‌های CREATE/PATCH باید در workspace باشند
        for (p in patches) {
            if (p.action == PlanAction.DELETE) {
                passed++
                logs.appendLine("PASS delete scheduled: ${p.path}")
                continue
            }
            val read = sandbox.readFile(p.path)
            if (read.isSuccess) {
                val body = read.getOrDefault("")
                if (body.contains(capability.id) || body.contains("FEATURE_TITLE") || body.length > 20) {
                    passed++
                    logs.appendLine("PASS present: ${p.path}")
                } else {
                    failed++
                    logs.appendLine("FAIL thin content: ${p.path}")
                }
            } else {
                failed++
                logs.appendLine("FAIL missing: ${p.path}")
            }
        }

        // 2) healthCheck الگو در اسکلت‌ها
        for (p in patches.filter { it.action == PlanAction.CREATE }) {
            val body = p.content.orEmpty()
            if (body.contains("fun healthCheck") || body.contains("fun execute")) {
                passed++
                logs.appendLine("PASS API surface: ${p.path}")
            } else if (p.path.endsWith(".kt")) {
                failed++
                logs.appendLine("FAIL no execute/healthCheck: ${p.path}")
            }
        }

        // 3) hints
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
