package com.retrobox.data

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Layout de armazenamento do app, versionado.
 *
 * ```
 * files/
 * ├── storage.version        1
 * ├── roms/<console>/        ROMs importadas ou baixadas
 * ├── saves/<jogo>.srm       SRAM (save do cartão)
 * ├── states/<jogo>.<slot>.state
 * ├── system/                BIOS/firmware do usuário
 * ├── covers/<console>/      cache de capas
 * ├── downloads/             *.part em andamento
 * └── backup/                snapshots de saves (rotação)
 * ```
 *
 * A versão existe para permitir migrações futuras sem adivinhar o que é o quê.
 * Tudo que grava arquivo deve passar por aqui em vez de montar caminhos na mão.
 */
object StorageLayout {

    const val VERSION = 1
    private const val TAG = "RetroBoxStorage"
    private const val VERSION_FILE = "storage.version"

    fun root(context: Context): File = context.filesDir

    fun roms(context: Context): File = dir(context, "roms")

    fun romsFor(context: Context, console: String): File =
        File(roms(context), console).also { it.mkdirs() }

    fun saves(context: Context): File = dir(context, "saves")

    fun states(context: Context): File = dir(context, "states")

    fun system(context: Context): File = dir(context, "system")

    fun covers(context: Context): File = dir(context, "covers")

    fun downloads(context: Context): File = dir(context, "downloads")

    fun backups(context: Context): File = dir(context, "backup")

    private fun dir(context: Context, name: String): File =
        File(root(context), name).also { if (!it.isDirectory) it.mkdirs() }

    /**
     * Cria as pastas e grava a versão. Idempotente; roda uma vez por processo.
     */
    fun ensure(context: Context) {
        listOf(roms(context), saves(context), states(context), system(context),
            covers(context), downloads(context), backups(context)).forEach { it.mkdirs() }

        val versionFile = File(root(context), VERSION_FILE)
        val current = runCatching { versionFile.readText().trim().toInt() }.getOrDefault(0)
        if (current == VERSION) return

        if (current in 1 until VERSION) {
            Log.i(TAG, "migrando storage v$current -> v$VERSION")
            // Nenhuma migração ainda: v1 já é o layout plano acima.
        }
        runCatching {
            versionFile.writeText("$VERSION\n")
        }.onFailure { Log.w(TAG, "não foi possível gravar storage.version", it) }

        if (current == 0) {
            removeEmptyLegacyDirs(context)
        }
        Log.i(TAG, "storage v$VERSION pronto em ${root(context).absolutePath}")
    }

    /** Restos de versões antigas do frontend (sem conteúdo). */
    private fun removeEmptyLegacyDirs(context: Context) {
        listOf("tmp", "rom").forEach { name ->
            val legacy = File(root(context), name)
            if (legacy.isDirectory && legacy.listFiles().isNullOrEmpty()) legacy.delete()
        }
    }

    /** Caminho relativo (para exibir/logar) de um arquivo dentro do app. */
    fun relative(context: Context, file: File): String =
        runCatching { file.relativeTo(root(context)).path }.getOrDefault(file.name)
}
