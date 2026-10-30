package com.retrobox.catalog

import android.content.Context
import android.util.Log
import com.retrobox.BuildConfig
import com.retrobox.data.CatalogEntry
import com.retrobox.data.Game
import com.retrobox.data.GameRepository
import com.retrobox.data.UserPreferencesRepository
import com.retrobox.domain.Console
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URI

/**
 * Carrega o catálogo (remoto ou o de exemplo embutido), valida a assinatura e
 * grava em Room.
 *
 * O catálogo NUNCA traz BIOS nem ROM embutida no APK: ele só descreve jogos
 * (metadados + URL opcional + SHA-256). Quem opera o catálogo é responsável
 * pelo conteúdo e pelos direitos dos arquivos apontados.
 */
class CatalogRepository(
    private val context: Context,
    private val gameRepository: GameRepository,
    private val preferences: UserPreferencesRepository
) {

    data class Result(
        val count: Int,
        val version: Int,
        val signatureVerified: Boolean,
        val source: String,
        val warnings: List<String> = emptyList()
    )

    private val moshi: Moshi = Moshi.Builder().build()
    private val payloadAdapter = moshi.adapter(CatalogPayload::class.java)

    /**
     * Atualiza o catálogo. Com [force] = false e o catálogo já carregado, usa o
     * catálogo embutido só uma vez (o remoto é sempre consultado).
     */
    suspend fun refresh(force: Boolean = false): Result = withContext(Dispatchers.IO) {
        val url = preferences.catalogUrl.first().ifBlank { BuildConfig.CATALOG_URL.trim() }

        val (bytes, source) = if (url.isBlank()) {
            val asset = readAsset()
                ?: throw CatalogException("Nenhum catálogo configurado e o de exemplo não está no APK.")
            asset to "embutido"
        } else {
            fetch(url) to url
        }

        val verified = CatalogVerifier.verify(bytes)
        val payload = parse(verified.jsonBytes)
        val (entries, warnings) = validate(payload)

        gameRepository.replaceCatalog(entries)
        preferences.updateCatalogResult(payload.version, null)

        Log.i(
            "RetroBoxCatalog",
            "catálogo v${payload.version} de $source: ${entries.size} itens " +
                "(assinatura ${if (verified.signatureVerified) "Ed25519" else "hash fixado"})"
        )
        Result(
            count = entries.size,
            version = payload.version,
            signatureVerified = verified.signatureVerified,
            source = source,
            warnings = warnings
        )
    }

    /**
     * Garante que existe catálogo na primeira execução.
     *
     * Com o catálogo **embutido** no APK, recarrega quando o asset muda (nova
     * versão do app); com URL remota, só busca na primeira vez (depois é o
     * botão "Atualizar"). Falhas são silenciosas: o app continua utilizável
     * com a biblioteca local.
     */
    suspend fun ensureLoaded(): Boolean = withContext(Dispatchers.IO) {
        val url = preferences.catalogUrl.first().ifBlank { BuildConfig.CATALOG_URL.trim() }

        if (url.isBlank()) {
            val signature = assetSignature()
            if (signature != null && signature != preferences.catalogAssetSignature.first()) {
                val ok = runCatching { refresh(force = false) }.isSuccess
                if (ok) preferences.updateCatalogAssetSignature(signature)
                return@withContext ok
            }
        }

        if (gameRepository.catalogCount() > 0) return@withContext true
        runCatching { refresh(force = false) }.onFailure {
            Log.w("RetroBoxCatalog", "catálogo inicial não carregado: ${it.message}")
        }.isSuccess
    }

    /** SHA-256 do asset de catálogo embutido (o app sabe quando ele muda). */
    private suspend fun assetSignature(): String? = withContext(Dispatchers.IO) {
        runCatching {
            context.assets.open("catalog.json").use { input ->
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
        }.getOrNull()
    }

    /** Liga o arquivo importado ao item do catálogo (para não "perder" a origem). */
    suspend fun attachImportedFile(entry: CatalogEntry, game: Game): Boolean {
        val existing = gameRepository.gameByPath(game.filePath) ?: return false
        if (existing.catalogId != null) return false
        gameRepository.updateGame(
            existing.copy(catalogId = entry.id, sha256 = existing.sha256 ?: entry.sha256.takeIf { it.isNotBlank() })
        )
        return true
    }

    // ------------------------------------------------------------ internos ----

    private fun readAsset(): ByteArray? = runCatching {
        context.assets.open("catalog.json").use { it.readBytes() }
    }.getOrNull()

    private fun fetch(url: String): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .build()

        HttpClientProvider.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw CatalogException("Falha ao baixar o catálogo (HTTP ${response.code}).")
            }
            val body = response.body ?: throw CatalogException("Resposta do catálogo vazia.")
            if (body.contentLength() > MAX_CATALOG_BYTES) {
                throw CatalogException("Catálogo grande demais (${body.contentLength()} bytes).")
            }
            val bytes = body.bytes()
            if (bytes.size > MAX_CATALOG_BYTES) {
                throw CatalogException("Catálogo grande demais (${bytes.size} bytes).")
            }
            return bytes
        }
    }

    private fun parse(payloadBytes: ByteArray): CatalogPayload {
        val payload = try {
            payloadAdapter.fromJson(payloadBytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw CatalogException(
                "Conteúdo do catálogo inválido: ${e.javaClass.simpleName}: ${e.message}",
                e
            )
        } ?: throw CatalogException("Conteúdo do catálogo vazio.")
        if (payload.version <= 0) throw CatalogException("Versão do catálogo inválida.")
        return payload
    }

    private fun validate(payload: CatalogPayload): Pair<List<CatalogEntry>, List<String>> {
        val warnings = mutableListOf<String>()
        val byId = LinkedHashMap<String, CatalogEntry>()

        payload.entries.forEach { dto ->
            val console = consoleFor(dto.console)
            if (console == null) {
                warnings += "Console desconhecido em '${dto.title.ifBlank { dto.id }}': ${dto.console}"
                return@forEach
            }
            if (!ID_PATTERN.matches(dto.id)) {
                warnings += "Item com id inválido ignorado: '${dto.id}'"
                return@forEach
            }
            if (dto.title.isBlank()) {
                warnings += "Item ${dto.id} sem título ignorado."
                return@forEach
            }

            val sha = dto.sha256?.trim()?.lowercase().orEmpty()
            val hasUrl = !dto.url.isNullOrBlank()
            if (hasUrl && !SHA256_PATTERN.matches(sha)) {
                warnings += "'${dto.title}' ignorado: download sem SHA-256."
                return@forEach
            }
            if (hasUrl && !isAllowedUrl(dto.url!!)) {
                warnings += "'${dto.title}' ignorado: URL não permitida (use https)."
                return@forEach
            }
            if (dto.sizeBytes < 0) {
                warnings += "'${dto.title}' ignorado: tamanho inválido."
                return@forEach
            }

            val md5 = dto.md5?.trim()?.lowercase()?.takeIf { MD5_PATTERN.matches(it) }
            val crc32 = dto.crc32?.trim()?.lowercase()?.takeIf { CRC32_PATTERN.matches(it) }
            if (!md5.isNullOrEmpty() && md5 == ZERO_HASH) {
                warnings += "'${dto.title}' ignorado: MD5 vazio."
                return@forEach
            }

            val fileName = sanitizeFileName(
                dto.fileName?.takeIf { it.isNotBlank() }
                    ?: "${dto.id}.${console.extensions.first()}"
            )
            if (fileName.isEmpty()) {
                warnings += "'${dto.title}' ignorado: nome de arquivo inválido."
                return@forEach
            }

            byId[dto.id] = CatalogEntry(
                id = dto.id,
                title = dto.title.trim(),
                console = console,
                fileName = fileName,
                sizeBytes = dto.sizeBytes,
                sha256 = sha,
                url = dto.url?.trim()?.takeIf { it.isNotBlank() },
                coverUrl = dto.coverUrl?.trim()?.takeIf { it.startsWith("https://") },
                description = dto.description?.trim()?.takeIf { it.isNotBlank() },
                developer = dto.developer?.trim()?.takeIf { it.isNotBlank() },
                releaseYear = dto.releaseYear?.takeIf { it in 1970..2100 },
                region = dto.region?.trim()?.takeIf { it.isNotBlank() },
                catalogVersion = payload.version,
                addedAt = System.currentTimeMillis(),
                md5 = md5,
                crc32 = crc32,
                genre = dto.genre?.trim()?.takeIf { it.isNotBlank() }
            )
        }

        if (byId.isEmpty()) throw CatalogException("O catálogo não tem nenhum item válido.")
        return byId.values.toList() to warnings
    }

    private fun consoleFor(raw: String): Console? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        Console.fromId(value.lowercase())?.let { return it }
        return Console.entries.firstOrNull {
            it.name.equals(value, ignoreCase = true) ||
                it.displayName.equals(value, ignoreCase = true)
        }
    }

    private fun isAllowedUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        when (uri.scheme?.lowercase()) {
            "https" -> true
            // só para desenvolvimento (adb reverse / emulador)
            "http" -> uri.host in setOf("127.0.0.1", "localhost", "10.0.2.2")
            else -> false
        }
    }.getOrDefault(false)

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_")
            .replace("..", "_")
            .trim()
            .take(120)

    companion object {
        private const val MAX_CATALOG_BYTES = 32 * 1024 * 1024L
        private val ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]{1,63}")
        private val SHA256_PATTERN = Regex("[a-f0-9]{64}")
        private val MD5_PATTERN = Regex("[a-f0-9]{32}")
        private val CRC32_PATTERN = Regex("[a-f0-9]{8}")
        private const val ZERO_HASH = "00000000000000000000000000000000"
    }
}
