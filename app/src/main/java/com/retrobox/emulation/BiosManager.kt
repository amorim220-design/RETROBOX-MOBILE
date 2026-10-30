package com.retrobox.emulation

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.retrobox.data.StorageLayout
import com.retrobox.domain.Console
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Gerenciador de BIOS/firmware **do usuário**.
 *
 * O app não inclui, não baixa e não redistribui BIOS de console: o usuário
 * importa o arquivo que já possui. A validação é por tamanho (sólido o
 * suficiente para pegar troca de arquivo/pacote errado) e o resultado aparece
 * na tela de BIOS. Alternativas livres (OpenBIOS, FreeBIOS) são aceitas.
 */
object BiosManager {

    data class BiosFile(
        val name: String,
        val label: String,
        /** Tamanhos aceitos em bytes (dumps conhecidos). */
        val sizes: Set<Long>
    )

    data class Requirement(
        val console: Console,
        /** Cada grupo é um AND; dentro do grupo qualquer arquivo satisfaz (OR). */
        val groups: List<List<String>>,
        val files: List<BiosFile>,
        val required: Boolean,
        val note: String
    )

    data class FileStatus(
        val file: BiosFile,
        val exists: Boolean,
        val bytes: Long,
        val sizeOk: Boolean
    )

    data class Status(
        val requirement: Requirement,
        val files: List<FileStatus>,
        val satisfied: Boolean,
        val allPresent: Boolean
    )

    data class ImportResult(val ok: Boolean, val message: String)

    private const val KB = 1024L
    private val PS1_SIZE = 512 * KB
    private val NDS_BIOS7 = 16 * KB
    private val NDS_BIOS9 = 4 * KB
    private val NDS_FIRMWARE = setOf(256 * KB, 512 * KB)

    val requirements: List<Requirement> = listOf(
        Requirement(
            console = Console.PS1,
            groups = listOf(
                listOf("scph5501.bin", "scph5500.bin", "scph5502.bin", "scph1001.bin", "scph7001.bin", "openbios.bin")
            ),
            files = listOf(
                BiosFile("scph5501.bin", "SCPH5501 (NTSC-U)", setOf(PS1_SIZE)),
                BiosFile("scph5500.bin", "SCPH5500 (NTSC-J)", setOf(PS1_SIZE)),
                BiosFile("scph5502.bin", "SCPH5502 (PAL)", setOf(PS1_SIZE)),
                BiosFile("openbios.bin", "OpenBIOS (livre)", setOf(PS1_SIZE))
            ),
            required = false,
            note = "Sem BIOS o PCSX-ReARMed usa HLE; alguns jogos têm glitches. " +
                "OpenBIOS é uma alternativa livre."
        ),
        Requirement(
            console = Console.NDS,
            groups = listOf(listOf("bios7.bin"), listOf("bios9.bin")),
            files = listOf(
                BiosFile("bios7.bin", "ARM7 BIOS", setOf(NDS_BIOS7)),
                BiosFile("bios9.bin", "ARM9 BIOS", setOf(NDS_BIOS9)),
                BiosFile("firmware.bin", "Firmware (opcional)", NDS_FIRMWARE)
            ),
            required = false,
            note = "O melonDS inclui FreeBIOS; o firmware do aparelho melhora " +
                "compatibilidade de saves e configurações."
        ),
        Requirement(
            console = Console.PSP,
            groups = emptyList(),
            files = emptyList(),
            required = false,
            note = "O PPSSPP não precisa de BIOS: ele cria o flash0 a partir dos " +
                "próprios arquivos na primeira execução."
        )
    )

    fun requirementFor(console: Console): Requirement? =
        requirements.firstOrNull { it.console == console }

    fun systemFile(context: Context, name: String): File =
        File(StorageLayout.system(context), name)

    suspend fun status(context: Context, console: Console): Status? = withContext(Dispatchers.IO) {
        val requirement = requirementFor(console) ?: return@withContext null
        val files = requirement.files.map { file ->
            val path = systemFile(context, file.name)
            val bytes = if (path.isFile) path.length() else 0L
            FileStatus(file, path.isFile, bytes, path.isFile && bytes in file.sizes)
        }
        val present = files.filter { it.exists }.map { it.file.name }.toSet()
        val satisfied = requirement.groups.all { group -> group.any { it in present } }
        Status(
            requirement = requirement,
            files = files,
            satisfied = satisfied,
            allPresent = files.all { it.exists }
        )
    }

    /** true quando o console tem todos os grupos obrigatórios atendidos. */
    suspend fun isSatisfied(context: Context, console: Console): Boolean =
        status(context, console)?.satisfied ?: true

    /**
     * Copia o arquivo escolhido pelo usuário para `system/<nome canônico>`.
     * Rejeita tamanho inesperado (provável arquivo errado) e arquivos vazios.
     */
    suspend fun import(
        context: Context,
        console: Console,
        uri: Uri,
        preferredName: String? = null
    ): ImportResult = withContext(Dispatchers.IO) {
        val requirement = requirementFor(console)
            ?: return@withContext ImportResult(false, "Este console não usa BIOS.")

        val displayName = queryName(context, uri)
            ?: return@withContext ImportResult(false, "Não foi possível ler o nome do arquivo.")

        // a tela chama com o nome canônico da linha escolhida; sem ele, aceita
        // apenas um nome que exista na lista do console
        val canonical = (preferredName?.let { name -> requirement.files.firstOrNull { it.name == name } }
            ?: requirement.files.firstOrNull { it.name.equals(displayName, true) })
            ?: return@withContext ImportResult(
                false,
                "Escolha a linha do arquivo (${requirement.files.joinToString(", ") { it.name }}) antes de importar."
            )

        val temp = File(StorageLayout.system(context), "${canonical.name}.import")
        temp.delete()
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
            } ?: throw IllegalStateException("sem permissão de leitura")
        }.getOrElse { e ->
            temp.delete()
            return@withContext ImportResult(false, "Falha ao copiar: ${e.message}")
        }

        val bytes = temp.length()
        if (bytes == 0L) {
            temp.delete()
            return@withContext ImportResult(false, "O arquivo está vazio.")
        }
        if (bytes !in canonical.sizes) {
            temp.delete()
            return@withContext ImportResult(
                false,
                "${canonical.label}: tamanho inesperado (${bytes} bytes; esperado " +
                    canonical.sizes.joinToString(" ou ") + ")."
            )
        }

        val destination = systemFile(context, canonical.name)
        if (destination.exists()) destination.delete()
        if (!temp.renameTo(destination)) {
            temp.copyTo(destination, overwrite = true)
            temp.delete()
        }
        ImportResult(true, "${canonical.name} instalado (${bytes / 1024} KB).")
    }

    suspend fun delete(context: Context, name: String): Boolean = withContext(Dispatchers.IO) {
        systemFile(context, name).delete()
    }

    private fun queryName(context: Context, uri: Uri): String? {
        var name: String? = null
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) name = cursor.getString(index)
            }
        }
        return name ?: uri.lastPathSegment?.substringAfterLast('/')
    }
}
