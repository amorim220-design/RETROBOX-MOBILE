package com.retrobox.emulation

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.retrobox.data.Game
import com.retrobox.data.GameRepository
import com.retrobox.domain.Console
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Importa uma arvore de pastas escolhida pelo usuario (SAF) — ou a pasta
 * /Android/data/<app>/files/JOGOS do proprio aparelho — para a biblioteca.
 *
 * A estrutura esperada e uma pasta por console (SNES, GBA, N64, MEGA DRIVE,
 * PS1, PSP...), mas o console tambem e detectado pela extensao do arquivo.
 *
 * Cada ROM e copiada para o armazenamento privado do app porque o nucleo
 * libretro precisa abrir o arquivo por um caminho real.
 */
object LibraryImporter {

    data class Result(
        val imported: Int,
        val withCover: Int,
        val skipped: Int,
        val errors: List<String>
    )

    /** Extensoes que sao o "ponto de entrada" de um jogo. */
    private val ENTRY_EXTENSIONS = mapOf(
        "nes" to Console.NES, "unf" to Console.NES, "unif" to Console.NES,
        "smc" to Console.SNES, "sfc" to Console.SNES, "fig" to Console.SNES, "swc" to Console.SNES,
        "gb" to Console.GB, "gbc" to Console.GBC,
        "gba" to Console.GBA, "agb" to Console.GBA,
        "md" to Console.MEGADRIVE, "gen" to Console.MEGADRIVE, "smd" to Console.MEGADRIVE,
        "n64" to Console.N64, "v64" to Console.N64, "z64" to Console.N64,
        "cue" to Console.PS1, "pbp" to Console.PS1, "chd" to Console.PS1, "img" to Console.PS1,
        "mdf" to Console.PS1,
        "cso" to Console.PSP, "iso" to Console.PSP
    )

    /** Extensoes copiadas junto (tracks de CD, imagens de disco). */
    private val SUPPORT_EXTENSIONS = setOf("bin", "iso", "mdf", "img", "cue")

    /** Pastas que identificam o console. */
    private val FOLDER_CONSOLES = mapOf(
        "NES" to Console.NES,
        "SNES" to Console.SNES, "SUPER NINTENDO" to Console.SNES,
        "SUPER NINTENDO ENTERTAINMENT SYSTEM" to Console.SNES,
        "GAME BOY" to Console.GB, "GB" to Console.GB,
        "GAME BOY COLOR" to Console.GBC, "GBC" to Console.GBC,
        "GAME BOY ADVANCE" to Console.GBA, "GBA" to Console.GBA,
        "MEGA DRIVE" to Console.MEGADRIVE, "GENESIS" to Console.MEGADRIVE,
        "SEGA MEGA DRIVE" to Console.MEGADRIVE,
        "NINTENDO 64" to Console.N64, "N64" to Console.N64,
        "PS1" to Console.PS1, "PLAYSTATION" to Console.PS1,
        "PSP" to Console.PSP, "PLAYSTATION PORTABLE" to Console.PSP
    )

    /** Um arquivo a importar e como copiar o conteudo. */
    private class Source(
        val name: String,
        val folder: String?,
        val relativePath: String,
        val copyTo: (File) -> Unit
    )

    // ---------------------------------------------------------------- SAF --

    suspend fun importTree(
        context: Context,
        treeUri: Uri,
        repository: GameRepository,
        onProgress: (current: Int, total: Int, label: String) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        val sources = mutableListOf<Source>()
        try {
            collectSaf(context, treeUri, DocumentsContract.getTreeDocumentId(treeUri), null, "", sources)
        } catch (e: Exception) {
            return@withContext Result(0, 0, 0, listOf("Não foi possível ler a pasta: ${e.message}"))
        }
        process(context, sources, repository, onProgress)
    }

    private fun collectSaf(
        context: Context,
        treeUri: Uri,
        documentId: String,
        folderName: String?,
        relativePrefix: String,
        out: MutableList<Source>
    ) {
        val resolver = context.contentResolver
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            ),
            null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                val mime = cursor.getString(2) ?: ""
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val consoleFolder = FOLDER_CONSOLES[name.trim().uppercase()]?.let { name } ?: folderName
                    collectSaf(
                        context, treeUri, id, consoleFolder,
                        if (relativePrefix.isEmpty()) name else "$relativePrefix/$name", out
                    )
                } else {
                    val extension = name.substringAfterLast('.', "").lowercase()
                    if (extension in ENTRY_EXTENSIONS || extension in SUPPORT_EXTENSIONS) {
                        val relative = if (relativePrefix.isEmpty()) name else "$relativePrefix/$name"
                        out += Source(
                            name = name,
                            folder = folderName,
                            relativePath = relative
                        ) { target ->
                            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                            val input = context.contentResolver.openInputStream(uri)
                                ?: throw IllegalStateException("sem permissão de leitura")
                            input.use { source ->
                                target.outputStream().use { output -> source.copyTo(output, 256 * 1024) }
                            }
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------- pasta do app --

    /**
     * Pasta /Android/data/<app>/files/JOGOS no proprio aparelho: nao precisa de
     * permissao nem de SAF, e o jeito mais simples de colocar os jogos.
     */
    suspend fun importAppFolder(
        context: Context,
        repository: GameRepository,
        onProgress: (current: Int, total: Int, label: String) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        val root = File(context.getExternalFilesDir(null), "JOGOS")
        if (!root.isDirectory) {
            return@withContext Result(0, 0, 0, emptyList())
        }
        val sources = mutableListOf<Source>()
        collectFiles(root, null, "", sources)
        if (sources.isEmpty()) return@withContext Result(0, 0, 0, emptyList())
        process(context, sources, repository, onProgress)
    }

    private fun collectFiles(
        directory: File,
        consoleFolder: String?,
        relativePrefix: String,
        out: MutableList<Source>
    ) {
        val children = directory.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                val folder = FOLDER_CONSOLES[child.name.trim().uppercase()]?.let { child.name } ?: consoleFolder
                collectFiles(child, folder, if (relativePrefix.isEmpty()) child.name else "$relativePrefix/${child.name}", out)
            } else {
                val extension = child.name.substringAfterLast('.', "").lowercase()
                if (extension in ENTRY_EXTENSIONS || extension in SUPPORT_EXTENSIONS) {
                    val relative = if (relativePrefix.isEmpty()) child.name else "$relativePrefix/${child.name}"
                    out += Source(
                        name = child.name,
                        folder = consoleFolder,
                        relativePath = relative
                    ) { target -> child.copyTo(target, overwrite = true) }
                }
            }
        }
    }

    // ------------------------------------------------------------ comum ----

    private suspend fun process(
        context: Context,
        sources: List<Source>,
        repository: GameRepository,
        onProgress: (current: Int, total: Int, label: String) -> Unit
    ): Result {
        val games = sources.filter { source ->
            val extension = source.name.substringAfterLast('.', "").lowercase()
            extension in ENTRY_EXTENSIONS
        }

        var imported = 0
        var withCover = 0
        var skipped = 0
        val errors = mutableListOf<String>()

        games.forEachIndexed { index, source ->
            onProgress(index + 1, games.size, source.name)

            val extension = source.name.substringAfterLast('.', "").lowercase()
            val console = consoleFor(source, extension)
            if (console == null) {
                skipped++
                return@forEachIndexed
            }

            // .bin/.img/.iso sao tracks/imagens de CD: so viram jogo se nao
            // houver um .cue/pao no mesmo lugar.
            if (extension in SUPPORT_EXTENSIONS && extension != "iso") {
                skipped++
                return@forEachIndexed
            }

            try {
                val relative = relativePathFor(source, console)
                val destination = File(context.filesDir, "roms/${console.id}/$relative")
                if (!destination.exists() || destination.length() == 0L) {
                    destination.parentFile?.mkdirs()
                    source.copyTo(destination)
                }

                val title = GameTitle.clean(source.name)
                repository.addGame(
                    Game(
                        title = title,
                        console = console,
                        filePath = destination.absolutePath,
                        coverUri = null,
                        rawTitle = source.name
                    )
                )
                imported++

                val cover = CoverStore.coverFor(context, console, source.name)
                if (cover != null) {
                    repository.updateCover(destination.absolutePath, cover)
                    withCover++
                }
            } catch (e: Exception) {
                errors += "${source.name}: ${e.message}"
            }
        }

        return Result(imported, withCover, skipped, errors)
    }

    private fun consoleFor(source: Source, extension: String): Console? {
        val folder = source.folder?.trim()?.uppercase()
        if (folder != null) {
            FOLDER_CONSOLES[folder]?.let { return it }
        }
        return ENTRY_EXTENSIONS[extension]
    }

    /** Caminho do arquivo dentro da pasta do console (preserva subpastas de CD). */
    private fun relativePathFor(source: Source, console: Console): String {
        val allParts = source.relativePath.split("/")
        val consoleFolder = FOLDER_CONSOLES.keys.firstOrNull { key ->
            allParts.any { it.trim().uppercase() == key }
        }
        val parts = if (consoleFolder != null) {
            val index = allParts.indexOfFirst { it.trim().uppercase() == consoleFolder }
            if (index >= 0) allParts.subList(index + 1, allParts.size) else allParts
        } else {
            allParts
        }
        return parts.map { sanitizeName(it) }.joinToString("/")
    }

    private fun sanitizeName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "jogo" }
}
