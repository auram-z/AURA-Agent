package com.aura.agent.evolution

/**
 * مقایسه متریک‌های نسخهٔ کاندید با baseline.
 */
class VersionComparator(
    private val config: EvolutionConfig
) {

    suspend fun compare(
        baseline: VersionRecord?,
        candidateTag: String,
        candidateMetrics: Map<String, Double>,
        test: TestResult
    ): CompareReport {
        val baseTag = baseline?.tag ?: "none"
        val baseMetrics = baseline?.metrics ?: emptyMap()

        val regressions = mutableListOf<String>()
        val gains = mutableListOf<String>()

        if (!test.success) {
            regressions += "تست‌ها شکست خوردند (failed=${test.failed})"
        }

        val baseConf = baseMetrics["confidence"] ?: 0.5
        val candConf = candidateMetrics["confidence"] ?: test.metrics["confidence"] ?: 0.0
        if (candConf + 0.05 < baseConf) {
            regressions += "کاهش confidence: $baseConf → $candConf"
        } else if (candConf > baseConf + 0.05) {
            gains += "بهبود confidence: $baseConf → $candConf"
        }

        val baseLat = baseMetrics["latency_ms"] ?: 0.0
        val candLat = candidateMetrics["latency_ms"] ?: test.metrics["latency_ms"] ?: 0.0
        if (baseLat > 0 && candLat > baseLat * 2 && candLat > 50) {
            regressions += "افزایش شدید latency: $baseLat → $candLat"
        } else if (baseLat > 0 && candLat < baseLat * 0.8) {
            gains += "کاهش latency: $baseLat → $candLat"
        }

        if (test.passed > 0) gains += "تست‌های موفق: ${test.passed}"

        val scoreCand = score(candidateMetrics.ifEmpty { test.metrics }, test)
        val scoreBase = if (baseline != null) score(baseMetrics, null) else 0.0
        val delta = scoreCand - scoreBase
        val minDelta = config.getMinScoreDelta().toDouble()

        val improved = test.success && regressions.isEmpty() && delta >= minDelta

        return CompareReport(
            baselineVersion = baseTag,
            candidateVersion = candidateTag,
            improved = improved,
            regressions = regressions,
            gains = gains,
            scoreDelta = delta
        )
    }

    private fun score(metrics: Map<String, Double>, test: TestResult?): Double {
        val conf = metrics["confidence"] ?: 0.5
        val lat = metrics["latency_ms"] ?: 1.0
        val files = metrics["files"] ?: 0.0
        val testRatio = if (test != null && test.passed + test.failed > 0) {
            test.passed.toDouble() / (test.passed + test.failed)
        } else conf

        // latency کمتر بهتر — نرمال‌سازی ساده
        val latScore = 1.0 / (1.0 + lat / 100.0)
        return 0.4 * conf + 0.3 * testRatio + 0.2 * latScore + 0.1 * (files.coerceAtMost(5.0) / 5.0)
    }
}
