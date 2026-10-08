package com.aura.agent.evolution

import android.content.Context
import android.util.Log
import com.aura.agent.learning.FeedbackStore
import com.aura.agent.learning.KnowledgeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Orchestrator فاز E0+E1:
 * - DETECT (CapabilityDetector)
 * - PLAN (EvolutionPlanner + SafetyPolicy)
 * - Snapshot پیش‌نویس
 * - LEARN حداقلی (ثبت پیام در lastReport)
 *
 * فازهای CODE/BUILD/TEST/DEPLOY در E2+ پیاده می‌شوند.
 */
class SelfEvolutionEngine(
    context: Context,
    feedbackStore: FeedbackStore,
    knowledgeStore: KnowledgeStore,
    private val scope: CoroutineScope,
    private val config: EvolutionConfig = EvolutionConfig(context),
    private val safety: SafetyPolicy = SafetyPolicy(),
    private val snapshots: SnapshotManager = SnapshotManager(context),
    private val sandbox: ProjectSandbox = ProjectSandbox(context, safety),
    private val detector: CapabilityDetector = CapabilityDetector(
        feedbackStore, knowledgeStore, config
    ),
    private val planner: EvolutionPlanner = EvolutionPlanner(sandbox, safety)
) {

    companion object {
        private const val TAG = "AuraEvolution"
    }

    private val _phase = MutableStateFlow(EvolutionPhase.IDLE)
    val phase: StateFlow<EvolutionPhase> = _phase.asStateFlow()

    private val _currentRun = MutableStateFlow<EvolutionRun?>(null)
    val currentRun: StateFlow<EvolutionRun?> = _currentRun.asStateFlow()

    private val _lastReport = MutableStateFlow<String?>(null)
    val lastReport: StateFlow<String?> = _lastReport.asStateFlow()

    private var runningJob: Job? = null

    fun statusSummary(): String {
        val run = _currentRun.value
        return buildString {
            append("🧬 Self-Evolution Engine (E0+E1)\n")
            append("• فاز: ${_phase.value}\n")
            if (run != null) {
                append("• run: ${run.id.take(8)}…\n")
                append("• پیام: ${run.message.take(120)}\n")
            }
            append("\n")
            append(sandbox.summary())
        }
    }

    /** فقط تشخیص — بدون plan یا تغییر */
    suspend fun detectOnly(
        trigger: CapabilitySource = CapabilitySource.SCHEDULED_SCAN,
        userText: String? = null
    ): List<Capability> = withContext(Dispatchers.Default) {
        _phase.value = EvolutionPhase.DETECT
        val caps = detector.detect(trigger, userText)
        _phase.value = EvolutionPhase.IDLE
        val report = if (caps.isEmpty()) {
            "قابلیت جدیدی پیدا نشد."
        } else {
            buildString {
                append("🔍 ${caps.size} قابلیت:\n")
                caps.forEachIndexed { i, c ->
                    append("${i + 1}. [${c.estimatedRisk}] ${c.title}\n")
                    append("   منبع: ${c.source} | ماژول‌ها: ${c.suggestedModules.joinToString()}\n")
                }
            }
        }
        _lastReport.value = report
        caps
    }

    /**
     * چرخه E1: DETECT → PLAN → (snapshot) → توقف قبل از CODE.
     * برای قابلیت‌های نیازمند تأیید، plan برمی‌گردد و phase روی PLAN می‌ماند تا UI تأیید کند.
     */
    suspend fun runCycle(
        trigger: CapabilitySource = CapabilitySource.USER_REQUEST,
        userText: String? = null,
        skipConfirm: Boolean = false
    ): EvolutionRun = withContext(Dispatchers.Default) {
        cancel()
        val started = System.currentTimeMillis()
        var run = EvolutionRun(
            phase = EvolutionPhase.DETECT,
            startedAt = started,
            message = "شروع تشخیص…"
        )
        _currentRun.value = run
        _phase.value = EvolutionPhase.DETECT

        try {
            val caps = detector.detect(trigger, userText)
            if (caps.isEmpty()) {
                run = run.copy(
                    phase = EvolutionPhase.IDLE,
                    finishedAt = System.currentTimeMillis(),
                    message = "قابلیتی برای تکامل پیدا نشد."
                )
                _currentRun.value = run
                _phase.value = EvolutionPhase.IDLE
                _lastReport.value = run.message
                return@withContext run
            }

            val cap = caps.first()
            run = run.copy(capability = cap, message = "طرح‌ریزی: ${cap.title}")
            _currentRun.value = run
            _phase.value = EvolutionPhase.PLAN

            val planResult = planner.plan(cap)
            val plan = planResult.getOrElse { err ->
                run = run.copy(
                    phase = EvolutionPhase.FAILED,
                    finishedAt = System.currentTimeMillis(),
                    message = "شکست PLAN: ${err.message}"
                )
                _currentRun.value = run
                _phase.value = EvolutionPhase.FAILED
                _lastReport.value = run.message
                return@withContext run
            }

            val enriched = planner.enrichWithWorkspace(plan, cap)

            // snapshot پیش از هر مرحله بعدی
            val snap = snapshots.create(
                label = "pre-plan-${cap.id.take(8)}",
                sourceDir = sandbox.workspaceDir
            )
            snapshots.prune(config.getKeepVersions())

            run = run.copy(
                plan = enriched,
                snapshotId = snap.id,
                phase = if (enriched.requiresUserConfirm && !skipConfirm)
                    EvolutionPhase.PLAN
                else
                    EvolutionPhase.IDLE,
                finishedAt = System.currentTimeMillis(),
                message = buildString {
                    append("✅ طرح آماده (E1 — قبل از CODE)\n\n")
                    append(enriched.summary)
                    append("\nSnapshot: ${snap.id}\n")
                    if (enriched.requiresUserConfirm && !skipConfirm) {
                        append("\n⚠️ نیاز به تأیید کاربر قبل از تولید کد (فاز E2).")
                    } else {
                        append("\nآماده برای فاز E2 (CodeGenerator).")
                    }
                }
            )
            _currentRun.value = run
            _phase.value = run.phase
            _lastReport.value = run.message
            Log.i(TAG, "Cycle E1 done: ${cap.title}")
            run
        } catch (e: Exception) {
            Log.e(TAG, "runCycle failed", e)
            val failed = run.copy(
                phase = EvolutionPhase.FAILED,
                finishedAt = System.currentTimeMillis(),
                message = "خطا: ${e.message}"
            )
            _currentRun.value = failed
            _phase.value = EvolutionPhase.FAILED
            _lastReport.value = failed.message
            failed
        }
    }

    /** اجرای غیرمسدود در scope موتور */
    fun runCycleAsync(
        trigger: CapabilitySource = CapabilitySource.USER_REQUEST,
        userText: String? = null,
        skipConfirm: Boolean = false,
        onDone: ((EvolutionRun) -> Unit)? = null
    ) {
        runningJob?.cancel()
        runningJob = scope.launch {
            val result = runCycle(trigger, userText, skipConfirm)
            onDone?.invoke(result)
        }
    }

    fun cancel() {
        runningJob?.cancel()
        runningJob = null
        if (_phase.value != EvolutionPhase.IDLE && _phase.value != EvolutionPhase.FAILED) {
            _phase.value = EvolutionPhase.IDLE
        }
    }

    suspend fun configStatus(): String = config.statusSummary()

    suspend fun setAutoEvolution(enabled: Boolean) = config.setAutoEvolution(enabled)

    suspend fun listSnapshots(): List<SnapshotInfo> = snapshots.list()
}
