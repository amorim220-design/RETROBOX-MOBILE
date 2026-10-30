package com.retrobox.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class DownloadState { QUEUED, RUNNING, COMPLETED, FAILED, CANCELED }

/**
 * Estado de download de um item do catálogo. Fica separado da tabela
 * `catalog` porque o catálogo é substituído a cada atualização, enquanto o
 * progresso de um download precisa sobreviver a isso.
 */
@Entity(tableName = "downloads")
data class DownloadRecord(
    @PrimaryKey val catalogId: String,
    val state: DownloadState = DownloadState.QUEUED,
    val bytes: Long = 0,
    val totalBytes: Long = 0,
    val error: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)
