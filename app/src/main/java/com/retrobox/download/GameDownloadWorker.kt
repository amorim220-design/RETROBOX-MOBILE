package com.retrobox.download

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.retrobox.RetroBoxApplication
import com.retrobox.data.DownloadRecord
import com.retrobox.data.DownloadState
import com.retrobox.data.StorageLayout
import com.retrobox.emulation.CoverStore
import com.retrobox.emulation.GameTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Baixa um item do catálogo em background:
 *
 * - sobrevive ao app em segundo plano (WorkManager);
 * - retoma do `.part` com HTTP Range;
 * - só entra na biblioteca depois de conferir tamanho + SHA-256;
 * - atualiza [DownloadRecord] para a UI mostrar progresso/erro.
 */
class GameDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? RetroBoxApplication ?: return Result.failure()
        val repository = app.gameRepository
        val catalogId = inputData.getString(KEY_CATALOG_ID)
            ?: return Result.failure(error("Item sem identificador."))

        val entry = repository.catalogEntry(catalogId)
            ?: return Result.failure(error("Este item não está mais no catálogo."))

        // já instalado (ex.: worker reexecutado depois de um kill): nada a fazer
        repository.gameByCatalogId(catalogId)?.let {
            repository.setDownload(
                DownloadRecord(
                    catalogId = catalogId,
                    state = DownloadState.COMPLETED,
                    bytes = 0,
                    totalBytes = entry.sizeBytes
                )
            )
            return Result.success()
        }

        repository.setDownload(
            DownloadRecord(
                catalogId = catalogId,
                state = DownloadState.RUNNING,
                totalBytes = entry.sizeBytes
            )
        )

        val showForeground = entry.sizeBytes == 0L || entry.sizeBytes > FOREGROUND_THRESHOLD
        if (showForeground) runCatching { setForeground(foreground(entry.title, 0, entry.sizeBytes)) }

        return try {
            // RomDownloader roda num laço de I/O bloqueante: o callback não pode
            // ser suspend. Ele apenas publica o progresso; quem grava no Room e
            // atualiza a notificação é o coletor abaixo.
            val progress = MutableStateFlow(0L to entry.sizeBytes)
            var downloadedPath: File? = null

            kotlinx.coroutines.coroutineScope {
                val reporter = launch {
                    progress.collect { (done, total) ->
                        repository.setDownload(
                            DownloadRecord(
                                catalogId = catalogId,
                                state = DownloadState.RUNNING,
                                bytes = done,
                                totalBytes = if (total > 0) total else entry.sizeBytes
                            )
                        )
                        setProgress(progressData(done, total))
                        if (showForeground && total > 0) {
                            runCatching { setForeground(foreground(entry.title, done, total)) }
                        }
                    }
                }
                try {
                    downloadedPath = RomDownloader.download(
                        context = applicationContext,
                        entry = entry,
                        shouldContinue = { !isStopped },
                        onProgress = { done, total -> progress.value = done to total }
                    )
                } finally {
                    reporter.cancel()
                }
            }

            val file = downloadedPath ?: throw IOException("Download não produziu arquivo.")

            val cover = resolveCover(entry)
            val title = GameTitle.clean(entry.fileName).ifBlank { entry.title }
            val inserted = repository.addDownloaded(
                entry = entry,
                filePath = file.absolutePath,
                title = title,
                coverUri = cover
            )
            if (inserted) {
                Log.i(TAG, "jogo instalado: ${entry.id} (${StorageLayout.relative(applicationContext, file)})")
            } else {
                // arquivo idêntico já existia em outro caminho: remove a cópia
                file.delete()
            }

            repository.setDownload(
                DownloadRecord(
                    catalogId = catalogId,
                    state = DownloadState.COMPLETED,
                    bytes = file.length(),
                    totalBytes = file.length()
                )
            )
            Result.success(workDataOf(KEY_FILE to file.absolutePath))
        } catch (e: kotlinx.coroutines.CancellationException) {
            repository.setDownload(
                DownloadRecord(catalogId, DownloadState.CANCELED, error = "Cancelado")
            )
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "download de ${entry.id} falhou: ${e.message}")
            val retry = (e is IOException || RomDownloader.isRetryable(e)) && runAttemptCount < MAX_ATTEMPTS
            repository.setDownload(
                DownloadRecord(
                    catalogId = catalogId,
                    state = if (retry) DownloadState.QUEUED else DownloadState.FAILED,
                    bytes = RomDownloader.partFile(applicationContext, catalogId).length(),
                    totalBytes = entry.sizeBytes,
                    error = e.message ?: "Falha no download."
                )
            )
            if (retry) Result.retry() else Result.failure(error(e.message ?: "Falha no download."))
        }
    }

    private suspend fun resolveCover(entry: com.retrobox.data.CatalogEntry): String? {
        val fromCatalog = entry.coverUrl?.let { url ->
            withContext(Dispatchers.IO) {
                CoverStore.coverFromUrl(applicationContext, entry.console, url, entry.id)
            }
        }
        if (fromCatalog != null) return fromCatalog
        return CoverStore.coverFor(applicationContext, entry.console, entry.fileName)
    }

    private fun foreground(title: String, done: Long, total: Long): ForegroundInfo {
        DownloadNotifications.ensureChannel(applicationContext)
        val notification = DownloadNotifications.progress(applicationContext, title, done, total)
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            ForegroundInfo(
                DownloadNotifications.notificationId(),
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(DownloadNotifications.notificationId(), notification)
        }
    }

    private fun progressData(done: Long, total: Long): Data = workDataOf(
        KEY_BYTES to done,
        KEY_TOTAL to total
    )

    private fun error(message: String): Data = workDataOf(KEY_ERROR to message)

    companion object {
        private const val TAG = "RetroBoxDownload"
        const val KEY_CATALOG_ID = "catalog_id"
        const val KEY_ERROR = "error"
        const val KEY_BYTES = "bytes"
        const val KEY_TOTAL = "total"
        const val KEY_FILE = "file"
        private const val MAX_ATTEMPTS = 3
        private const val FOREGROUND_THRESHOLD = 24L * 1024 * 1024
    }
}
