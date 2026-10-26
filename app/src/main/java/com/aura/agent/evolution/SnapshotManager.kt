package com.aura.agent.evolution

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class SnapshotManager(private val context: Context) {

    companion object {
        private const val TAG = "AuraSnapshot"
        private const val DIR_NAME = "evolution/snapshots"
    }

    private val root: File
        get() = File(context.filesDir, DIR_NAME).also { if (!it.exists()) it.mkdirs() }

    suspend fun create(label: String, sourceDir: File? = null): SnapshotInfo =
        withContext(Dispatchers.IO) {
            val id = "snap-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
            val outFile = File(root, "$id.zip")
            var fileCount = 0

            ZipOutputStream(outFile.outputStream().buffered()).use { zos ->
                val src = sourceDir?.takeIf { it.exists() && it.isDirectory }
                if (src != null) {
                    src.walkTopDown().filter { it.isFile }.forEach { file ->
                        val entryName = file.relativeTo(src).path.replace('\\', '/')
                        zos.putNextEntry(ZipEntry(entryName))
                        file.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                        fileCount++
                    }
                }
                zos.putNextEntry(ZipEntry("_aura_snapshot_meta.txt"))
                zos.write(
                    "id=$id\nlabel=$label\ncreated=${System.currentTimeMillis()}\nfiles=$fileCount\n"
                        .toByteArray(Charsets.UTF_8)
                )
                zos.closeEntry()
            }

            Log.i(TAG, "Snapshot created: $id ($fileCount files)")
            SnapshotInfo(
                id = id,
                label = label,
                path = outFile.absolutePath,
                fileCount = fileCount
            )
        }

    suspend fun list(): List<SnapshotInfo> = withContext(Dispatchers.IO) {
        root.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".zip") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { f ->
                val id = f.name.removeSuffix(".zip")
                SnapshotInfo(
                    id = id,
                    label = id,
                    createdAt = f.lastModified(),
                    path = f.absolutePath,
                    fileCount = -1
                )
            }
            ?: emptyList()
    }

    suspend fun restore(snapshotId: String, targetDir: File): Result<Unit> =
        withContext(Dispatchers.IO) {
            val zip = File(root, "$snapshotId.zip")
            if (!zip.exists()) {
                Result.failure(IllegalArgumentException("Snapshot پیدا نشد: $snapshotId"))
            } else {
                try {
                    if (targetDir.exists()) targetDir.deleteRecursively()
                    targetDir.mkdirs()
                    ZipInputStream(zip.inputStream().buffered()).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory && entry.name != "_aura_snapshot_meta.txt") {
                                val out = File(targetDir, entry.name)
                                out.parentFile?.mkdirs()
                                out.outputStream().use { zis.copyTo(it) }
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                    Log.i(TAG, "Restored snapshot $snapshotId → ${targetDir.absolutePath}")
                    Result.success(Unit)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
        }

    suspend fun delete(snapshotId: String): Boolean = withContext(Dispatchers.IO) {
        File(root, "$snapshotId.zip").delete()
    }

    suspend fun prune(keep: Int) = withContext(Dispatchers.IO) {
        val files = root.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".zip") }
            ?.sortedByDescending { it.lastModified() }
            ?: return@withContext
        files.drop(keep.coerceAtLeast(1)).forEach { it.delete() }
    }
}
