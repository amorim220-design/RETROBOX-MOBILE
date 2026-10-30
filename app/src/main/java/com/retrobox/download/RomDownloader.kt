package com.retrobox.download

import android.content.Context
import android.util.Log
import com.retrobox.catalog.HttpClientProvider
import com.retrobox.data.CatalogEntry
import com.retrobox.data.StorageLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class DownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Download retomável de uma ROM do catálogo.
 *
 * Fluxo: `.part` (em `files/downloads`) → Range/retomada → tamanho → SHA-256 →
 * `renameTo` atômico para `files/roms/<console>/`.
 *
 * O SHA-256 é a única prova de integridade: se não bater, o arquivo é apagado
 * e o download falha (nunca entra na biblioteca pela metade).
 */
object RomDownloader {

    private const val TAG = "RetroBoxDownload"
    private const val BUFFER = 256 * 1024

    private val client by lazy {
        HttpClientProvider.client.newBuilder()
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    fun partFile(context: Context, entryId: String): File =
        File(StorageLayout.downloads(context), "$entryId.part")

    /**
     * Baixa (ou retoma) o arquivo de [entry] e devolve o caminho final já
     * dentro de `roms/<console>/`.
     *
     * [shouldContinue] permite cancelar (o `.part` fica para retomar depois).
     */
    suspend fun download(
        context: Context,
        entry: CatalogEntry,
        shouldContinue: () -> Boolean = { true },
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        val url = entry.url ?: throw DownloadException("Este item não tem download: traga seu próprio arquivo.")
        val part = partFile(context, entry.id)
        part.parentFile?.mkdirs()

        val expected = entry.sizeBytes
        var resumeFrom = if (part.exists()) part.length() else 0L

        if (expected > 0 && resumeFrom == expected) {
            // já baixou tudo numa tentativa anterior que morreu antes de validar
            return@withContext finish(context, entry, part, onProgress)
        }
        if (expected > 0 && resumeFrom > expected) {
            part.delete()
            resumeFrom = 0
        }
        if (expected > 0) {
            val free = part.parentFile?.usableSpace ?: 0
            val needed = expected - resumeFrom
            if (free in 1 until needed) {
                throw DownloadException(
                    "Espaço insuficiente: faltam ${formatMb(needed - free)} para este jogo."
                )
            }
        }

        val request = Request.Builder()
            .url(url)
            .apply { if (resumeFrom > 0) header("Range", "bytes=$resumeFrom-") }
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "RetroBox/1.0")
            .build()

        client.newCall(request).execute().use { response ->
            if (!shouldContinue()) throw DownloadException("Download cancelado.")

            val bodyLength = response.body?.contentLength() ?: -1L
            val total = when {
                expected > 0 -> expected
                response.code == 206 && bodyLength > 0 -> bodyLength + resumeFrom
                bodyLength > 0 -> bodyLength
                else -> -1L
            }

            when (response.code) {
                206 -> {
                    val contentRange = response.header("Content-Range").orEmpty()
                    val start = contentRange.substringAfter("bytes ", "")
                        .substringBefore('-').toLongOrNull()
                    if (start != null && start != resumeFrom) {
                        part.delete()
                        throw DownloadException("Servidor devolveu um intervalo inesperado; tente de novo.")
                    }
                    append(response.body!!.byteStream(), part, resumeFrom, total, shouldContinue, onProgress)
                }
                200 -> {
                    // servidor ignorou o Range: recomeça do zero
                    part.delete()
                    write(response.body!!.byteStream(), part, total, shouldContinue, onProgress)
                }
                416 -> {
                    if (expected > 0 && part.length() == expected) {
                        // já está completo
                    } else {
                        part.delete()
                        throw DownloadException("O servidor recusou a retomada; baixe de novo.")
                    }
                }
                401, 403 -> throw DownloadException("Acesso negado ao arquivo (HTTP ${response.code}).")
                404, 410 -> throw DownloadException("Arquivo não está mais disponível (HTTP ${response.code}).")
                else -> throw DownloadException("Falha no download (HTTP ${response.code}).")
            }

            onProgress(part.length(), if (total > 0) total else part.length())
        }

        finish(context, entry, part, onProgress)
    }

    private fun write(
        input: java.io.InputStream,
        part: File,
        expected: Long,
        shouldContinue: () -> Boolean,
        onProgress: (Long, Long) -> Unit
    ) {
        var written = 0L
        var lastReport = 0L
        part.outputStream().use { output ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                if (!shouldContinue()) throw DownloadException("Download cancelado.")
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                written += read
                if (written - lastReport >= 512 * 1024) {
                    lastReport = written
                    onProgress(written, expected)
                }
            }
            output.flush()
        }
        onProgress(written, expected)
    }

    private fun append(
        input: java.io.InputStream,
        part: File,
        offset: Long,
        expected: Long,
        shouldContinue: () -> Boolean,
        onProgress: (Long, Long) -> Unit
    ) {
        var written = offset
        var lastReport = offset
        RandomAccessFile(part, "rw").use { file ->
            file.seek(offset)
            val buffer = ByteArray(BUFFER)
            while (true) {
                if (!shouldContinue()) throw DownloadException("Download cancelado.")
                val read = input.read(buffer)
                if (read < 0) break
                file.write(buffer, 0, read)
                written += read
                if (written - lastReport >= 512 * 1024) {
                    lastReport = written
                    onProgress(written, expected)
                }
            }
        }
        onProgress(written, expected)
    }

    private fun finish(
        context: Context,
        entry: CatalogEntry,
        part: File,
        onProgress: (Long, Long) -> Unit
    ): File {
        if (!part.exists()) throw DownloadException("Arquivo temporário desapareceu; baixe de novo.")
        val size = part.length()
        if (entry.sizeBytes > 0 && size != entry.sizeBytes) {
            throw DownloadException(
                "Tamanho não confere: esperado ${formatMb(entry.sizeBytes)}, veio ${formatMb(size)}."
            )
        }
        if (entry.sha256.isNotBlank()) {
            val actual = sha256(part)
            if (!actual.equals(entry.sha256, ignoreCase = true)) {
                part.delete()
                throw DownloadException("Arquivo corrompido (SHA-256 não confere). Baixe de novo.")
            }
        }

        val target = File(StorageLayout.romsFor(context, entry.console.id), entry.fileName)
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            // último recurso: copiar (pode ser outro filesystem)
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        Log.i(TAG, "download concluído: ${entry.id} -> ${target.name} ($size bytes)")
        onProgress(size, size)
        return target
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun formatMb(bytes: Long): String =
        String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))

    fun isRetryable(error: Throwable): Boolean =
        error is IOException || (error is DownloadException && error.message?.contains("HTTP 5") == true)
}
