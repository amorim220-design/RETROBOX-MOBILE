package com.retrobox.catalog

import com.squareup.moshi.JsonClass

/**
 * Envelope do catálogo remoto:
 *
 * ```json
 * { "format": 1, "keyId": "dev-1", "payload": "base64", "signature": "base64" }
 * ```
 *
 * `payload` são os bytes UTF-8 do JSON do catálogo; `signature` é Ed25519
 * sobre exatamente esses bytes (não sobre o envelope). Assim o parser pode
 * mudar sem quebrar a assinatura.
 *
 * **Formato 2**: o `payload` é o JSON comprimido em deflate (cru, sem cabeçalho
 * zlib). A assinatura continua cobrindo os bytes do payload, então a
 * verificação acontece *antes* de descompactar — um payload adulterado nunca
 * chega ao parser. O formato 1 (sem compressão) continua aceito.
 */
@JsonClass(generateAdapter = true)
data class CatalogEnvelope(
    val format: Int = 1,
    val keyId: String = "",
    val payload: String = "",
    val signature: String = ""
)

@JsonClass(generateAdapter = true)
data class CatalogPayload(
    val version: Int = 1,
    val updatedAt: String = "",
    val entries: List<CatalogEntryDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class CatalogEntryDto(
    val id: String = "",
    val title: String = "",
    val console: String = "",
    val fileName: String? = null,
    val sizeBytes: Long = 0,
    val sha256: String? = null,
    val url: String? = null,
    val coverUrl: String? = null,
    val description: String? = null,
    val developer: String? = null,
    val releaseYear: Int? = null,
    val region: String? = null,
    /** MD5 do cartucho (identidade do jogo). */
    val md5: String? = null,
    /** CRC32 do cartucho. */
    val crc32: String? = null,
    /** Gênero do jogo. */
    val genre: String? = null
)

class CatalogException(message: String, cause: Throwable? = null) : Exception(message, cause)
