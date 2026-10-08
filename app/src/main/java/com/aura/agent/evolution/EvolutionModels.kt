package com.aura.agent.evolution

import java.util.UUID

/** فازهای ماشین حالت Self-Evolution Engine */
enum class EvolutionPhase {
    IDLE,
    DETECT,
    PLAN,
    CODE,
    BUILD,
    TEST,
    COMPARE,
    DEPLOY,
    ROLLBACK,
    LEARN,
    FAILED
}

/** منبع تشخیص قابلیت */
enum class CapabilitySource {
    USER_REQUEST,
    FEEDBACK_PATTERN,
    KNOWLEDGE_GAP,
    RUNTIME_ERROR,
    MODEL_SIGNAL,
    SCHEDULED_SCAN
}

enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

enum class PlanAction {
    CREATE,
    PATCH,
    DELETE,
    RENAME
}

data class Capability(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String,
    val source: CapabilitySource,
    val priority: Int = 5,
    val estimatedRisk: RiskLevel = RiskLevel.MEDIUM,
    val suggestedModules: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)

data class PlanStep(
    val order: Int,
    val action: PlanAction,
    val path: String,
    val rationale: String
)

data class EvolutionPlan(
    val capabilityId: String,
    val steps: List<PlanStep>,
    val targetFiles: List<String>,
    val newFiles: List<String>,
    val testHints: List<String> = emptyList(),
    val requiresUserConfirm: Boolean = true,
    val summary: String = ""
)

data class CodePatch(
    val path: String,
    val action: PlanAction,
    val content: String? = null,
    val oldContentHash: String? = null
)

data class BuildResult(
    val success: Boolean,
    val durationMs: Long,
    val apkPath: String? = null,
    val logExcerpt: String = "",
    val errors: List<String> = emptyList()
)

data class TestResult(
    val success: Boolean,
    val durationMs: Long,
    val passed: Int = 0,
    val failed: Int = 0,
    val metrics: Map<String, Double> = emptyMap(),
    val logExcerpt: String = ""
)

data class CompareReport(
    val baselineVersion: String,
    val candidateVersion: String,
    val improved: Boolean,
    val regressions: List<String> = emptyList(),
    val gains: List<String> = emptyList(),
    val scoreDelta: Double = 0.0
)

data class EvolutionRun(
    val id: String = UUID.randomUUID().toString(),
    val capability: Capability? = null,
    val phase: EvolutionPhase = EvolutionPhase.IDLE,
    val plan: EvolutionPlan? = null,
    val patches: List<CodePatch> = emptyList(),
    val build: BuildResult? = null,
    val test: TestResult? = null,
    val compare: CompareReport? = null,
    val snapshotId: String? = null,
    val versionTag: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val message: String = ""
)

data class VersionRecord(
    val tag: String,
    val snapshotId: String,
    val capabilityIds: List<String> = emptyList(),
    val metrics: Map<String, Double> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
    val active: Boolean = false
)

data class SnapshotInfo(
    val id: String,
    val label: String,
    val createdAt: Long = System.currentTimeMillis(),
    val path: String,
    val fileCount: Int = 0
)

data class SafetyViolation(
    val path: String,
    val reason: String,
    val severity: RiskLevel
)

data class SafetyReport(
    val ok: Boolean,
    val violations: List<SafetyViolation> = emptyList(),
    val requiresConfirm: Boolean = false
)
