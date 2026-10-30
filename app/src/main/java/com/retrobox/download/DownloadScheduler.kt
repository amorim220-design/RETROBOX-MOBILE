package com.retrobox.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.retrobox.data.CatalogEntry
import com.retrobox.data.DownloadRecord
import com.retrobox.data.DownloadState
import com.retrobox.data.GameRepository
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Agenda downloads com WorkManager.
 *
 * Uma obra única por item (`download-<id>`): pedir de novo enquanto baixa não
 * cria duas cópias concorrentes; depois de concluída, baixar de novo é no-op.
 */
object DownloadScheduler {

    const val TAG = "rom-download"

    fun enqueue(context: Context, entry: CatalogEntry) {
        if (entry.url.isNullOrBlank()) return
        val request = OneTimeWorkRequestBuilder<GameDownloadWorker>()
            .setInputData(workDataOf(GameDownloadWorker.KEY_CATALOG_ID to entry.id))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .addTag("entry:${entry.id}")
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork("download-${entry.id}", ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context, entryId: String) {
        WorkManager.getInstance(context).cancelUniqueWork("download-$entryId")
    }

    /**
     * Reconcilia o estado do WorkManager com a tabela `downloads`.
     *
     * Se o processo morreu no meio do download, o registro pode ter ficado em
     * RUNNING/QUEUED sem trabalho ativo. Nesse caso o item volta para a fila (o
     * `.part` permite retomar de onde parou em vez de baixar tudo de novo).
     */
    suspend fun reconcile(context: Context, repository: GameRepository) {
        val workManager = WorkManager.getInstance(context)
        val records = repository.downloads.first()
        records.filter { it.state == DownloadState.RUNNING || it.state == DownloadState.QUEUED }
            .forEach { record ->
                val infos = workManager.getWorkInfosForUniqueWork("download-${record.catalogId}").get()
                val active = infos.any {
                    it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED ||
                        it.state == WorkInfo.State.BLOCKED
                }
                if (active) return@forEach

                val entry = repository.catalogEntry(record.catalogId)
                if (entry?.url.isNullOrBlank()) {
                    repository.setDownload(
                        record.copy(state = DownloadState.QUEUED, updatedAt = System.currentTimeMillis())
                    )
                } else {
                    // reenfileira: o worker retoma do .part
                    enqueue(context, entry)
                }
            }
    }

    fun isActive(info: WorkInfo): Boolean =
        info.state == WorkInfo.State.RUNNING ||
            info.state == WorkInfo.State.ENQUEUED ||
            info.state == WorkInfo.State.BLOCKED

    fun progressPercent(record: DownloadRecord?): Int? {
        val total = record?.totalBytes ?: return null
        if (total <= 0) return null
        return ((record.bytes * 100) / total).toInt().coerceIn(0, 100)
    }
}
