package com.aura.agent.evolution

import android.util.Log
import com.aura.agent.learning.KnowledgeStore
import java.util.UUID

/**
 * ثبت تجربهٔ هر چرخه تکامل در پایگاه دانش (فاز E5).
 */
class KnowledgeUpdater(
    private val knowledgeStore: KnowledgeStore
) {

    companion object {
        private const val TAG = "AuraEvoKnow"
    }

    suspend fun recordRun(run: EvolutionRun) {
        val cap = run.capability
        val title = when (run.phase) {
            EvolutionPhase.DEPLOY, EvolutionPhase.LEARN, EvolutionPhase.IDLE ->
                "تکامل موفق: ${cap?.title ?: run.id.take(8)}"
            EvolutionPhase.ROLLBACK ->
                "Rollback: ${cap?.title ?: run.versionTag}"
            EvolutionPhase.FAILED ->
                "تکامل ناموفق: ${cap?.title ?: run.id.take(8)}"
            else -> "چرخه تکامل: ${cap?.title ?: run.phase.name}"
        }

        val content = buildString {
            append("فاز نهایی: ${run.phase}\n")
            if (cap != null) {
                append("قابلیت: ${cap.title}\n")
                append("منبع: ${cap.source} | ریسک: ${cap.estimatedRisk}\n")
            }
            run.plan?.let { append("گام‌ها: ${it.steps.size}\n") }
            run.build?.let {
                append("Build: ${if (it.success) "OK" else "FAIL"} (${it.durationMs}ms)\n")
                if (it.errors.isNotEmpty()) append("خطاها: ${it.errors.take(3).joinToString()}\n")
            }
            run.test?.let {
                append("Test: passed=${it.passed} failed=${it.failed}\n")
            }
            run.compare?.let {
                append("Compare: delta=${it.scoreDelta} improved=${it.improved}\n")
                if (it.regressions.isNotEmpty()) append("Regressions: ${it.regressions}\n")
            }
            run.versionTag?.let { append("نسخه: $it\n") }
            run.snapshotId?.let { append("Snapshot: $it\n") }
            append("پیام: ${run.message.take(400)}")
        }

        knowledgeStore.add(
            KnowledgeStore.KnowledgeChunk(
                id = UUID.randomUUID().toString(),
                title = title.take(80),
                content = content.take(1500),
                source = "self_evolution",
                tags = listOf("evolution", run.phase.name.lowercase())
            )
        )
        Log.i(TAG, "Recorded evolution knowledge: $title")
    }
}
