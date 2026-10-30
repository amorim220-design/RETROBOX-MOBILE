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
    val region: String? = null
)

class CatalogException(message: String, cause: Throwable? = null) : Exception(message, cause)
