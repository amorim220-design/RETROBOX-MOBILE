package com.retrobox.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.retrobox.domain.Console

/**
 * Um item do catálogo remoto (assinado). Não é um jogo da biblioteca: é a
 * "prateleira" — o jogo só entra em [Game] depois de baixado/importado.
 *
 * [url] nulo significa "traga seu próprio arquivo": o app mostra o jogo e
 * pede o arquivo ao usuário, sem baixar nada de terceiros.
 *
 * [md5] é a identidade do jogo (a mesma usada pela base oficial do libretro):
 * com ela o app reconhece a ROM do usuário mesmo renomeada, casa com o
 * catálogo e valida o arquivo importado.
 */
@Entity(
    tableName = "catalog",
    indices = [
        Index(value = ["md5"]),
        Index(value = ["console"])
    ]
)
data class CatalogEntry(
    @PrimaryKey val id: String,
    val title: String,
    val console: Console,
    val fileName: String,
    val sizeBytes: Long,
    /** SHA-256 do arquivo, em hex minúsculo. Obrigatório quando há download. */
    val sha256: String,
    val url: String? = null,
    val coverUrl: String? = null,
    val description: String? = null,
    val developer: String? = null,
    val releaseYear: Int? = null,
    val region: String? = null,
    val catalogVersion: Int = 1,
    val addedAt: Long = System.currentTimeMillis(),
    /** MD5 do cartucho (identidade usada pela base do libretro). */
    val md5: String? = null,
    /** CRC32 do cartucho (segunda identidade, em hex). */
    val crc32: String? = null,
    /** Gênero ("Plataforma", "RPG"…). */
    val genre: String? = null
) {
    val hasDownload: Boolean get() = !url.isNullOrBlank()
}
