package com.retrobox.catalog

import android.util.Base64
import com.retrobox.BuildConfig

/**
 * Chaves públicas Ed25519 aceitas para assinar catálogos.
 *
 * - `sample`: assina o catálogo de exemplo embutido em `assets/catalog.json`
 *   (sem ROMs, só metadados de homebrew). A chave privada (seed) fica fora do
 *   repositório (`tools/catalog/keys/`, ignorado pelo git); para trocá-la, gere
 *   um par novo com `tools/catalog/CatalogTool.java keygen` e cole o valor de
 *   `<keyId>.pub` aqui.
 * - `app`: chave do operador, definida no build com
 *   `RETROBOX_CATALOG_PUBLIC_KEY` (base64, 32 bytes) e `RETROBOX_CATALOG_KEY_ID`.
 *
 * Para gerar um par de chaves veja `tools/catalog/README.md`.
 */
object CatalogKeys {

    private val bundled: Map<String, String> = mapOf(
        // Gerada por tools/catalog/CatalogTool.java keygen sample (descartável).
        "sample" to "4jwN0O8+YUKuO6OSIG22l/Bc9FmkBRVGfL5f+yt3qmQ="
    )

    private val configured: Map<String, String> = buildMap {
        val key = BuildConfig.CATALOG_PUBLIC_KEY.trim()
        if (key.isNotEmpty()) {
            put(BuildConfig.CATALOG_KEY_ID.ifBlank { "app" }, key)
        }
    }

    fun publicKey(keyId: String): ByteArray? {
        val base64 = configured[keyId] ?: bundled[keyId] ?: return null
        return runCatching { Base64.decode(base64, Base64.NO_WRAP) }
            .getOrNull()
            ?.takeIf { it.size == 32 }
    }

    val ids: List<String> get() = (bundled.keys + configured.keys).distinct()

    val hasOperatorKey: Boolean get() = configured.isNotEmpty()
}
