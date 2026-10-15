package com.aura.agent.evolution

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Context.evolutionVersionsStore: DataStore<Preferences> by preferencesDataStore("aura_evolution_versions")

/**
 * فعال‌سازی نسخه یا Rollback (فاز E4).
 * با sandbox_only=true فایل‌ها در workspace می‌مانند و VersionRecord + manifest ثبت می‌شود.
 * با sandbox_only=false همان + نوشتن manifest استقرار برای CI/Gradle.
 */
class DeployGate(
    private val context: Context,
    private val config: EvolutionConfig,
    private val snapshots: SnapshotManager,
    private val sandbox: ProjectSandbox
) {

    companion object {
        private const val TAG = "AuraDeploy"
        private val KEY_VERSIONS = stringPreferencesKey("version_records")
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class VersionList(val items: List<VersionRecordDto> = emptyList())

    @Serializable
    private data class VersionRecordDto(
        val tag: String,
        val snapshotId: String,
        val capabilityIds: List<String> = emptyList(),
        val metrics: Map<String, Double> = emptyMap(),
        val createdAt: Long = 0L,
        val active: Boolean = false
    )

    private fun VersionRecord.toDto() = VersionRecordDto(
        tag, snapshotId, capabilityIds, metrics, createdAt, active
    )

    private fun VersionRecordDto.toModel() = VersionRecord(
        tag, snapshotId, capabilityIds, metrics, createdAt, active
    )

    suspend fun listVersions(): List<VersionRecord> = withContext(Dispatchers.IO) {
        val raw = context.evolutionVersionsStore.data.first()[KEY_VERSIONS] ?: return@withContext emptyList()
        runCatching {
            json.decodeFromString<VersionList>(raw).items.map { it.toModel() }
        }.getOrElse { emptyList() }
    }

    suspend fun activeVersion(): VersionRecord? = listVersions().firstOrNull { it.active }

    suspend fun deploy(
        capabilityIds: List<String>,
        snapshotId: String,
        metrics: Map<String, Double>,
        patches: List<CodePatch>
    ): Result<VersionRecord> = withContext(Dispatchers.IO) {
        runCatching {
            val tag = newTag()
            val sandboxOnly = config.isSandboxOnly()

            val record = VersionRecord(
                tag = tag,
                snapshotId = snapshotId,
                capabilityIds = capabilityIds,
                metrics = metrics,
                active = true
            )

            // manifest استقرار برای CI / Gradle sync
            val manifest = buildString {
                appendLine("tag=$tag")
                appendLine("snapshot=$snapshotId")
                appendLine("sandboxOnly=$sandboxOnly")
                appendLine("createdAt=${System.currentTimeMillis()}")
                appendLine("capabilities=${capabilityIds.joinToString(",")}")
                appendLine("metrics=$metrics")
                appendLine("patches=${patches.size}")
                patches.forEach { p ->
                    appendLine("  ${p.action}\t${p.path}\t${p.content?.length ?: 0}")
                }
            }
            sandbox.writeFile("evolution/artifacts/deploy-$tag.txt", manifest)

            if (!sandboxOnly) {
                // آماده‌سازی لیست فایل‌ها برای اعمال توسط CI (نه خود APK روی دستگاه)
                val applyList = patches.joinToString("\n") { "${it.action}|${it.path}" }
                sandbox.writeFile("evolution/artifacts/apply-$tag.list", applyList)
                Log.i(TAG, "Deploy manifest written for CI apply: $tag")
            }

            val current = listVersions().map { it.copy(active = false) }
            val updated = (listOf(record) + current).take(config.getKeepVersions())
            persist(updated)

            Log.i(TAG, "Deployed $tag sandboxOnly=$sandboxOnly patches=${patches.size}")
            record
        }
    }

    suspend fun rollback(toTag: String? = null): Result<VersionRecord> = withContext(Dispatchers.IO) {
        runCatching {
            val versions = listVersions()
            if (versions.isEmpty()) error("هیچ نسخه‌ای برای Rollback نیست")

            val target = if (toTag != null) {
                versions.firstOrNull { it.tag == toTag }
                    ?: error("نسخه پیدا نشد: $toTag")
            } else {
                versions.firstOrNull { !it.active } ?: versions.getOrNull(1)
                    ?: error("نسخهٔ قبلی موجود نیست")
            }

            snapshots.restore(target.snapshotId, sandbox.workspaceDir).getOrThrow()

            val updated = versions.map { it.copy(active = it.tag == target.tag) }
            persist(updated)
            Log.i(TAG, "Rollback → ${target.tag}")
            target.copy(active = true)
        }
    }

    private suspend fun persist(items: List<VersionRecord>) {
        context.evolutionVersionsStore.edit { prefs ->
            prefs[KEY_VERSIONS] = json.encodeToString(
                VersionList(items.map { it.toDto() })
            )
        }
    }

    private fun newTag(): String {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "evo-$ts"
    }
}
