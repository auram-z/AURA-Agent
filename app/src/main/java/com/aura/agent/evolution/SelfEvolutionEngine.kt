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
 * Self-Evolution Engine — فازهای E0 تا E6
 *
 * IDLE → DETECT → PLAN → CODE → BUILD → TEST → COMPARE → DEPLOY|ROLLBACK → LEARN → IDLE
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
    private val planner: EvolutionPlanner = EvolutionPlanner(sandbox, safety),
    private val codeGenerator: CodeGenerator = CodeGenerator(safety),
    private val buildRunner: BuildRunner = BuildRunner(sandbox, safety),
    private val testRunner: TestRunner = TestRunner(sandbox),
    private val comparator: VersionComparator = VersionComparator(config),
    private val deployGate: DeployGate = DeployGate(context, config, snapshots, sandbox),
    private val knowledgeUpdater: KnowledgeUpdater = KnowledgeUpdater(knowledgeStore),
    private val promptEvolver: PromptEvolver = PromptEvolver(context)
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
            append("🧬 Self-Evolution Engine (E0–E6)\n")
            append("• فاز: ${_phase.value}\n")
            if (run != null) {
                append("• run: ${run.id.take(8)}…\n")
                run.capability?.let { append("• قابلیت: ${it.title}\n") }
                run.versionTag?.let { append("• نسخه: $it\n") }
                append("• پیام: ${run.message.take(160)}\n")
            }
            append("\n")
            append(sandbox.summary())
        }
    }

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
     * چرخه کامل E2–E6.
     * @param autoApprove اگر true، تأیید plan برای ریسک MEDIUM+ نیز پذیرفته می‌شود (دستور صریح کاربر).
     * در غیر این صورت برای ریسک MEDIUM یا بالاتر، چرخه در PLAN متوقف می‌شود تا کاربر تأیید کند.
     */
    suspend fun runCycle(
        trigger: CapabilitySource = CapabilitySource.USER_REQUEST,
        userText: String? = null,
        autoApprove: Boolean = false
    ): EvolutionRun = withContext(Dispatchers.Default) {
        cancel()
        val started = System.currentTimeMillis()
        var run = EvolutionRun(phase = EvolutionPhase.DETECT, startedAt = started, message = "DETECT…")
        _currentRun.value = run
        _phase.value = EvolutionPhase.DETECT

        try {
            // ── DETECT ──
            val caps = detector.detect(trigger, userText)
            if (caps.isEmpty()) {
                return@withContext finish(run, EvolutionPhase.IDLE, "قابلیتی برای تکامل پیدا نشد.")
            }
            val cap = caps.first()

            // Auto فقط برای LOW
            if (trigger == CapabilitySource.SCHEDULED_SCAN ||
                (config.isAutoEvolution() && trigger != CapabilitySource.USER_REQUEST)
            ) {
                if (cap.estimatedRisk > RiskLevel.LOW) {
                    return@withContext finish(
                        run.copy(capability = cap),
                        EvolutionPhase.IDLE,
                        "تکامل خودکار فقط برای ریسک LOW — «${cap.title}» رد شد (${cap.estimatedRisk})."
                    )
                }
            }

            run = run.copy(capability = cap, message = "PLAN: ${cap.title}")
            _currentRun.value = run
            _phase.value = EvolutionPhase.PLAN

            // ── PLAN ──
            val plan = planner.plan(cap).getOrElse { err ->
                return@withContext fail(run, "شکست PLAN: ${err.message}")
            }
            val enriched = planner.enrichWithWorkspace(plan, cap)
            run = run.copy(plan = enriched)

            // توقف برای تأیید کاربر روی ریسک MEDIUM+
            if (enriched.requiresUserConfirm && !autoApprove &&
                cap.estimatedRisk >= RiskLevel.MEDIUM
            ) {
                val msg = buildString {
                    append("⏸️ طرح نیاز به تأیید دارد (ریسک ${cap.estimatedRisk})\n\n")
                    append(enriched.summary)
                    append("\n\nبرای ادامه بگویید: «شروع تکامل» یا «تکامل کن»")
                }
                return@withContext finish(
                    run.copy(message = msg),
                    EvolutionPhase.IDLE,
                    msg
                )
            }

            val snap = snapshots.create("pre-${cap.id.take(8)}", sandbox.workspaceDir)
            snapshots.prune(config.getKeepVersions())
            run = run.copy(plan = enriched, snapshotId = snap.id)

            // ── CODE ──
            _phase.value = EvolutionPhase.CODE
            run = run.copy(phase = EvolutionPhase.CODE, message = "CODE…")
            _currentRun.value = run

            val patches = codeGenerator.generate(enriched, cap).getOrElse { err ->
                return@withContext fail(run, "شکست CODE: ${err.message}")
            }
            run = run.copy(patches = patches)

            // ── BUILD ──
            _phase.value = EvolutionPhase.BUILD
            run = run.copy(phase = EvolutionPhase.BUILD, message = "BUILD…")
            _currentRun.value = run

            val build = buildRunner.build(patches)
            run = run.copy(build = build)
            if (!build.success) {
                snapshots.restore(snap.id, sandbox.workspaceDir)
                run = run.copy(phase = EvolutionPhase.ROLLBACK, message = "Build fail → rollback snapshot")
                knowledgeUpdater.recordRun(run)
                promptEvolver.onFailure(build.errors.joinToString())
                return@withContext finish(
                    run,
                    EvolutionPhase.ROLLBACK,
                    "❌ Build ناموفق — Rollback شد.\n${build.errors.take(5).joinToString("\n")}"
                )
            }

            // ── TEST ──
            _phase.value = EvolutionPhase.TEST
            run = run.copy(phase = EvolutionPhase.TEST, message = "TEST…")
            _currentRun.value = run

            val test = testRunner.run(patches, cap, enriched.testHints)
            run = run.copy(test = test)
            if (!test.success) {
                snapshots.restore(snap.id, sandbox.workspaceDir)
                knowledgeUpdater.recordRun(run.copy(phase = EvolutionPhase.ROLLBACK))
                promptEvolver.onFailure("test failed ${test.failed}")
                return@withContext finish(
                    run.copy(phase = EvolutionPhase.ROLLBACK),
                    EvolutionPhase.ROLLBACK,
                    "❌ تست ناموفق — Rollback.\npassed=${test.passed} failed=${test.failed}"
                )
            }

            // ── COMPARE ──
            _phase.value = EvolutionPhase.COMPARE
            run = run.copy(phase = EvolutionPhase.COMPARE, message = "COMPARE…")
            _currentRun.value = run

            val baseline = deployGate.activeVersion()
            val candidateTag = "candidate-${System.currentTimeMillis()}"
            val compare = comparator.compare(baseline, candidateTag, test.metrics, test)
            run = run.copy(compare = compare)

            if (!compare.improved) {
                snapshots.restore(snap.id, sandbox.workspaceDir)
                knowledgeUpdater.recordRun(run.copy(phase = EvolutionPhase.ROLLBACK))
                promptEvolver.onFailure(compare.regressions.joinToString())
                return@withContext finish(
                    run.copy(phase = EvolutionPhase.ROLLBACK),
                    EvolutionPhase.ROLLBACK,
                    "❌ Regression — Rollback.\n${compare.regressions.joinToString("\n")}\ndelta=${compare.scoreDelta}"
                )
            }

            // ── DEPLOY ──
            _phase.value = EvolutionPhase.DEPLOY
            run = run.copy(phase = EvolutionPhase.DEPLOY, message = "DEPLOY…")
            _currentRun.value = run

            val deployed = deployGate.deploy(
                capabilityIds = listOf(cap.id),
                snapshotId = snap.id,
                metrics = test.metrics,
                patches = patches
            ).getOrElse { err ->
                return@withContext fail(run, "شکست DEPLOY: ${err.message}")
            }

            run = run.copy(versionTag = deployed.tag)

            // ── LEARN ──
            _phase.value = EvolutionPhase.LEARN
            knowledgeUpdater.recordRun(run.copy(phase = EvolutionPhase.LEARN))
            promptEvolver.onSuccessfulDeploy(cap, compare)

            val report = buildString {
                append("✅ تکامل موفق\n\n")
                append("قابلیت: ${cap.title}\n")
                append("نسخه: ${deployed.tag}\n")
                append("Sandbox only: ${config.isSandboxOnly()}\n")
                append("پچ‌ها: ${patches.size}\n")
                append("Build: OK (${build.durationMs}ms)\n")
                append("Test: ${test.passed} passed\n")
                append("Score delta: ${"%.4f".format(compare.scoreDelta)}\n")
                if (compare.gains.isNotEmpty()) append("Gains: ${compare.gains.joinToString()}\n")
                append("\n${enriched.summary}")
            }
            return@withContext finish(run.copy(phase = EvolutionPhase.LEARN), EvolutionPhase.IDLE, report)
        } catch (e: Exception) {
            Log.e(TAG, "runCycle failed", e)
            return@withContext fail(run, "خطا: ${e.message}")
        }
    }

    suspend fun rollback(toTag: String? = null): Result<String> = withContext(Dispatchers.IO) {
        _phase.value = EvolutionPhase.ROLLBACK
        val result = deployGate.rollback(toTag)
        result.fold(
            onSuccess = { ver ->
                val msg = "↩️ Rollback به ${ver.tag} انجام شد."
                _lastReport.value = msg
                _phase.value = EvolutionPhase.IDLE
                knowledgeUpdater.recordRun(
                    EvolutionRun(
                        phase = EvolutionPhase.ROLLBACK,
                        versionTag = ver.tag,
                        snapshotId = ver.snapshotId,
                        message = msg,
                        finishedAt = System.currentTimeMillis()
                    )
                )
                Result.success(msg)
            },
            onFailure = {
                _phase.value = EvolutionPhase.FAILED
                Result.failure(it)
            }
        )
    }

    suspend fun listVersions(): List<VersionRecord> = deployGate.listVersions()

    suspend fun promptAddon(): String = promptEvolver.buildPromptAddon()

    fun runCycleAsync(
        trigger: CapabilitySource = CapabilitySource.USER_REQUEST,
        userText: String? = null,
        autoApprove: Boolean = true,
        onDone: ((EvolutionRun) -> Unit)? = null
    ) {
        runningJob?.cancel()
        runningJob = scope.launch {
            val result = runCycle(trigger, userText, autoApprove)
            onDone?.invoke(result)
        }
    }

    fun maybeAutoEvolve() {
        scope.launch {
            if (!config.isAutoEvolution()) return@launch
            if (_phase.value != EvolutionPhase.IDLE) return@launch
            runCycle(
                trigger = CapabilitySource.SCHEDULED_SCAN,
                userText = null,
                autoApprove = true
            )
        }
    }

    fun cancel() {
        runningJob?.cancel()
        runningJob = null
    }

    suspend fun configStatus(): String = config.statusSummary()
    suspend fun setAutoEvolution(enabled: Boolean) = config.setAutoEvolution(enabled)
    suspend fun listSnapshots(): List<SnapshotInfo> = snapshots.list()

    private suspend fun finish(
        run: EvolutionRun,
        phase: EvolutionPhase,
        message: String
    ): EvolutionRun {
        val done = run.copy(
            phase = phase,
            finishedAt = System.currentTimeMillis(),
            message = message
        )
        _currentRun.value = done
        _phase.value = if (phase == EvolutionPhase.LEARN) EvolutionPhase.IDLE else phase
        if (phase == EvolutionPhase.LEARN || phase == EvolutionPhase.ROLLBACK) {
            _phase.value = EvolutionPhase.IDLE
        }
        _lastReport.value = message
        return done
    }

    private suspend fun fail(run: EvolutionRun, message: String): EvolutionRun {
        val failed = run.copy(
            phase = EvolutionPhase.FAILED,
            finishedAt = System.currentTimeMillis(),
            message = message
        )
        _currentRun.value = failed
        _phase.value = EvolutionPhase.FAILED
        _lastReport.value = message
        runCatching { knowledgeUpdater.recordRun(failed) }
        runCatching { promptEvolver.onFailure(message) }
        return failed
    }
}
