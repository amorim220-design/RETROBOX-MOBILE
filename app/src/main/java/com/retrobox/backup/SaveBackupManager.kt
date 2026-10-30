package com.retrobox.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import com.retrobox.BuildConfig
import com.retrobox.data.StorageLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup/restauração de progresso (SRAM + save states).
 *
 * - automático: um zip por dia em `files/backup/`, mantendo os 7 mais recentes;
 * - manual: exporta/importa um `.zip` pelo seletor de arquivos (SAF).
 *
 * Formato: `manifest.json` + `saves/...` + `states/...`. A restauração ignora
 * qualquer caminho fora dessas duas pastas (proteção contra zip-slip).
 */
object SaveBackupManager {

    private const val TAG = "RetroBoxBackup"
    private const val MANIFEST = "manifest.json"
    private const val MAX_KEEP = 7
    private const val AUTO_INTERVAL_MS = 24L * 60 * 60 * 1000

    data class BackupResult(val file: File?, val files: Int, val bytes: Long)
    data class RestoreResult(val restored: Int, val skipped: Int, val error: String? = null)

    /** Um zip com SRAM/states; `null` quando não há nada para salvar. */
    suspend fun createBackup(context: Context, directory: File? = null): BackupResult =
        withContext(Dispatchers.IO) {
            val saves = listOf("saves", "states").map { File(StorageLayout.root(context), it) }
                .filter { it.isDirectory }
                .flatMap { dir -> dir.walkTopDown().filter { it.isFile }.toList() }
            if (saves.isEmpty()) return@withContext BackupResult(null, 0, 0)

            val targetDir = directory ?: StorageLayout.backups(context).also { it.mkdirs() }
            targetDir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val target = File(targetDir, "retrobox-backup-$stamp.zip")
            val temp = File(targetDir, "${target.name}.part")

            var written = 0
            ZipOutputStream(temp.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST))
                val manifest = """
                    {"app":"RetroBox","versionCode":${BuildConfig.VERSION_CODE},
                     "createdAt":${System.currentTimeMillis()},"format":1}
                """.trimIndent()
                zip.write(manifest.toByteArray())
                zip.closeEntry()

                saves.forEach { file ->
                    val entryName = StorageLayout.relative(context, file)
                    if (!entryName.startsWith("saves/") && !entryName.startsWith("states/")) return@forEach
                    zip.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zip, 128 * 1024) }
                    zip.closeEntry()
                    written++
                }
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            Log.i(TAG, "backup: ${target.name} ($written arquivos)")
            prune(directory ?: StorageLayout.backups(context))
            BackupResult(target, written, target.length())
        }

    /** Exporta o backup para o destino escolhido no SAF. */
    suspend fun exportTo(context: Context, uri: Uri): BackupResult = withContext(Dispatchers.IO) {
        val result = createBackup(context)
        val source = result.file ?: return@withContext result
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { it.copyTo(output, 128 * 1024) }
            } ?: throw IllegalStateException("sem permissão de escrita")
        }.onFailure {
            return@withContext BackupResult(null, 0, 0)
        }
        result
    }

    /**
     * Restaura um zip exportado. Nunca sobrescreve fora de saves/ e states/;
     * arquivos existentes são substituídos apenas depois de validados.
     */
    suspend fun restoreFrom(context: Context, uri: Uri): RestoreResult = withContext(Dispatchers.IO) {
        var restored = 0
        var skipped = 0
        val root = StorageLayout.root(context)

        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    var entry: ZipEntry? = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (name != MANIFEST) {
                            val safe = isSafeEntry(name)
                            if (!safe) {
                                skipped++
                            } else {
                                val target = File(root, name)
                                target.parentFile?.mkdirs()
                                val temp = File(target.parentFile, "${target.name}.restore")
                                temp.outputStream().use { out -> zip.copyTo(out, 128 * 1024) }
                                if (target.exists()) target.delete()
                                if (!temp.renameTo(target)) {
                                    temp.copyTo(target, overwrite = true)
                                    temp.delete()
                                }
                                restored++
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: return@withContext RestoreResult(0, 0, "Não foi possível abrir o arquivo.")
        }.onFailure { e ->
            Log.w(TAG, "restore falhou: ${e.message}")
            return@withContext RestoreResult(restored, skipped, e.message ?: "Backup inválido.")
        }

        RestoreResult(restored, skipped)
    }

    suspend fun autoBackupIfDue(context: Context) {
        withContext(Dispatchers.IO) {
            val dir = StorageLayout.backups(context)
            val newest = dir.listFiles { f -> f.name.endsWith(".zip") }?.maxByOrNull { it.lastModified() }
            val due = newest == null || System.currentTimeMillis() - newest.lastModified() > AUTO_INTERVAL_MS
            if (!due) return@withContext
            val result = createBackup(context)
            if (result.file != null) Log.i(TAG, "backup automático criado (${result.files} arquivos)")
        }
    }

    fun backups(context: Context): List<File> =
        StorageLayout.backups(context)
            .listFiles { f -> f.name.endsWith(".zip") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun delete(context: Context, file: File): Boolean = runCatching { file.delete() }.getOrDefault(false)

    private fun prune(dir: File) {
        dir.listFiles { f -> f.name.endsWith(".zip") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_KEEP)
            ?.forEach { it.delete() }
    }

    private fun isSafeEntry(name: String): Boolean {
        if (name.startsWith("/") || name.contains("..") || name.contains("\\")) return false
        return name.startsWith("saves/") || name.startsWith("states/")
    }

    /** Tamanho de saves+states para exibir nas configurações. */
    fun usage(context: Context): Long =
        listOf("saves", "states")
            .map { File(StorageLayout.root(context), it) }
            .filter { it.isDirectory }
            .sumOf { dir -> dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
}
