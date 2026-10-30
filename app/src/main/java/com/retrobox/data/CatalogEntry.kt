package com.retrobox.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.retrobox.domain.Console

/**
 * Um item do catálogo remoto (assinado). Não é um jogo da biblioteca: é a
 * "prateleira" — o jogo só entra em [Game] depois de baixado/importado.
 *
 * [url] nulo significa "traga seu próprio arquivo": o app mostra o jogo e
 * pede o arquivo ao usuário, sem baixar nada de terceiros.
 */
@Entity(tableName = "catalog")
data class CatalogEntry(
    @PrimaryKey val id: String,
    val title: String,
    val console: Console,
    val fileName: String,
    val sizeBytes: Long,
    /** SHA-256 do arquivo, em hex minúsculo. Obrigatório: o download usa para validar. */
    val sha256: String,
    val url: String? = null,
    val coverUrl: String? = null,
    val description: String? = null,
    val developer: String? = null,
    val releaseYear: Int? = null,
    val region: String? = null,
    val catalogVersion: Int = 1,
    val addedAt: Long = System.currentTimeMillis()
) {
    val hasDownload: Boolean get() = !url.isNullOrBlank()
}
