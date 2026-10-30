package com.retrobox.emulation

import android.content.Context
import android.os.Build
import com.retrobox.BuildConfig
import com.retrobox.data.StorageLayout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Erros do núcleo deixam de ser opacos: viram um relatório com versão, ABI,
 * console, núcleo, arquivo e a mensagem nativa. Fica salvo em
 * `files/logs/last_error.txt` (para suporte) e pode ser copiado na tela.
 */
object ErrorReporter {

    data class Report(
        val details: String,
        val savedAt: File?
    )

    fun report(
        context: Context,
        console: String,
        core: String,
        romPath: String?,
        message: String,
        extra: String? = null
    ): Report {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val details = buildString {
            appendLine("RetroBox ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Quando: $stamp")
            appendLine("Aparelho: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine("Console: $console")
            appendLine("Núcleo: $core")
            appendLine("ROM: ${romPath?.let { File(it).name } ?: "—"}")
            appendLine("Caminho: ${romPath ?: "—"}")
            if (romPath != null) appendLine("Tamanho: ${File(romPath).length()} bytes")
            appendLine()
            appendLine("Erro: $message")
            extra?.takeIf { it.isNotBlank() }?.let { appendLine(); appendLine(it) }
        }

        val dir = File(StorageLayout.root(context), "logs").apply { mkdirs() }
        val file = File(dir, "last_error.txt")
        val saved = runCatching { file.writeText(details); file }.getOrNull()
        return Report(details, saved)
    }

    fun lastSaved(context: Context): String? =
        runCatching { File(StorageLayout.root(context), "logs/last_error.txt").readText() }.getOrNull()
}
