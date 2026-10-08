package com.aura.agent.evolution

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * فضای ایزوله برای تغییرات تکاملی.
 * فاز E1: فهرست ماژول‌ها، مسیرهای مجاز، خواندن/نوشتن در workspace روی دستگاه.
 *
 * توجه: سورس کامل اپ داخل APK نیست؛ workspace یک درخت کاری جداست
 * که CodeGenerator (فاز E2) در آن فایل می‌سازد.
 */
class ProjectSandbox(
    private val context: Context,
    private val safety: SafetyPolicy = SafetyPolicy()
) {

    companion object {
        private const val TAG = "AuraSandbox"
        private const val WORKSPACE = "evolution/workspace"

        /** ماژول‌های شناخته‌شده پروژه AURA (برای تشخیص و plan) */
        val KNOWN_MODULES = listOf(
            "core",
            "ai",
            "ai/tools",
            "learning",
            "data",
            "voice",
            "media",
            "trading",
            "trading/data",
            "trading/models",
            "ui",
            "ui/theme",
            "security",
            "evolution"
        )

        val MODULE_TO_PATH = mapOf(
            "core" to "app/src/main/java/com/aura/agent/core",
            "ai" to "app/src/main/java/com/aura/agent/ai",
            "ai/tools" to "app/src/main/java/com/aura/agent/ai/tools",
            "learning" to "app/src/main/java/com/aura/agent/learning",
            "data" to "app/src/main/java/com/aura/agent/data",
            "voice" to "app/src/main/java/com/aura/agent/voice",
            "media" to "app/src/main/java/com/aura/agent/media",
            "trading" to "app/src/main/java/com/aura/agent/trading",
            "trading/data" to "app/src/main/java/com/aura/agent/trading/data",
            "trading/models" to "app/src/main/java/com/aura/agent/trading/models",
            "ui" to "app/src/main/java/com/aura/agent/ui",
            "ui/theme" to "app/src/main/java/com/aura/agent/ui/theme",
            "security" to "app/src/main/java/com/aura/agent/security",
            "evolution" to "app/src/main/java/com/aura/agent/evolution"
        )
    }

    val workspaceDir: File
        get() = File(context.filesDir, WORKSPACE).also { if (!it.exists()) it.mkdirs() }

    fun listKnownModules(): List<String> = KNOWN_MODULES

    fun pathForModule(module: String): String? =
        MODULE_TO_PATH[module.trim().lowercase()]

    fun isPathAllowed(relativePath: String): Boolean = safety.isPathAllowed(relativePath)

    /** فهرست فایل‌های فعلی داخل workspace (نسبی) */
    suspend fun listWorkspaceFiles(): List<String> = withContext(Dispatchers.IO) {
        val root = workspaceDir
        if (!root.exists()) return@withContext emptyList()
        root.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(root).path.replace('\\', '/') }
            .toList()
            .sorted()
    }

    suspend fun readFile(relativePath: String): Result<String> = withContext(Dispatchers.IO) {
        if (!isPathAllowed(relativePath)) {
            return@withContext Result.failure(SecurityException("مسیر غیرمجاز: $relativePath"))
        }
        val f = File(workspaceDir, relativePath)
        if (!f.exists() || !f.isFile) {
            return@withContext Result.failure(IllegalArgumentException("فایل در sandbox نیست: $relativePath"))
        }
        runCatching { f.readText(Charsets.UTF_8) }
    }

    suspend fun writeFile(relativePath: String, content: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (!isPathAllowed(relativePath)) {
                return@withContext Result.failure(SecurityException("مسیر غیرمجاز: $relativePath"))
            }
            runCatching {
                val f = File(workspaceDir, relativePath)
                f.parentFile?.mkdirs()
                f.writeText(content, Charsets.UTF_8)
                Log.i(TAG, "Wrote ${content.length} bytes → $relativePath")
            }
        }

    suspend fun deleteFile(relativePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isPathAllowed(relativePath)) {
            return@withContext Result.failure(SecurityException("مسیر غیرمجاز: $relativePath"))
        }
        runCatching {
            val f = File(workspaceDir, relativePath)
            if (f.exists()) f.delete()
            Unit
        }
    }

    suspend fun clearWorkspace() = withContext(Dispatchers.IO) {
        workspaceDir.deleteRecursively()
        workspaceDir.mkdirs()
    }

    fun summary(): String {
        val files = workspaceDir.walkTopDown().count { it.isFile }
        return buildString {
            append("📦 Project Sandbox\n")
            append("• مسیر: ${workspaceDir.absolutePath}\n")
            append("• فایل‌های workspace: $files\n")
            append("• ماژول‌های شناخته‌شده: ${KNOWN_MODULES.size}\n")
        }
    }
}
