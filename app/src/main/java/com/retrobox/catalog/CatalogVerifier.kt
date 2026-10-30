package com.retrobox.catalog

import android.util.Base64
import android.util.Log
import com.retrobox.BuildConfig
import com.squareup.moshi.Moshi
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Verifica o catálogo antes de confiar nele.
 *
 * Duas provas são aceitas:
 *
 * 1. **Ed25519** — a prova normal. A chave pública (32 bytes) vem de
 *    [CatalogKeys] (embutida ou de `RETROBOX_CATALOG_PUBLIC_KEY` no build).
 * 2. **SHA-256 fixado (pin)** — só para aparelhos cujo provedor de segurança
 *    não tem Ed25519 (Android < 13) ou para builds internos. O hash do
 *    *payload* precisa bater com `RETROBOX_CATALOG_PINNED_SHA256`. Como o hash
 *    está no APK, atualizar o catálogo exige atualizar o app — é o preço de
 *    não ter Ed25519; em Android 13+ a assinatura funciona normalmente.
 *
 * Sem uma das duas, o catálogo é recusado. Nunca há "modo inseguro".
 */
object CatalogVerifier {

    private const val TAG = "RetroBoxCatalog"
    private const val MAX_PAYLOAD_BYTES = 32 * 1024 * 1024
    private const val MAX_JSON_BYTES = 48 * 1024 * 1024

    /** Prefixo ASN.1 de uma chave pública Ed25519 (SPKI) + os 32 bytes crus. */
    private val ED25519_SPKI_PREFIX = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
    )

    data class Verified(
        val payloadBytes: ByteArray,
        val keyId: String,
        /** true = assinatura Ed25519 válida; false = aceito só pelo hash fixado. */
        val signatureVerified: Boolean,
        /** JSON do catálogo (já descompactado quando o envelope é formato 2). */
        val jsonBytes: ByteArray
    )

    private val moshi: Moshi = Moshi.Builder().build()
    private val envelopeAdapter = moshi.adapter(CatalogEnvelope::class.java)

    fun verify(envelopeBytes: ByteArray): Verified {
        val envelope = try {
            envelopeAdapter.fromJson(envelopeBytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw CatalogException("Catálogo ilegível (JSON inválido).", e)
        } ?: throw CatalogException("Catálogo vazio.")

        if (envelope.format != 1 && envelope.format != 2) {
            throw CatalogException("Formato de catálogo não suportado (${envelope.format}).")
        }

        val payload = runCatching { Base64.decode(envelope.payload, Base64.NO_WRAP) }
            .getOrElse { throw CatalogException("Payload do catálogo inválido.") }
        if (payload.isEmpty()) throw CatalogException("Payload do catálogo vazio.")
        if (payload.size > MAX_PAYLOAD_BYTES) {
            throw CatalogException("Catálogo grande demais (${payload.size} bytes).")
        }

        val signature = runCatching { Base64.decode(envelope.signature, Base64.NO_WRAP) }
            .getOrElse { byteArrayOf() }

        val verified = verifySignature(payload, signature, envelope.keyId)
            ?: throw CatalogException("Catálogo recusado: ${reasonFor(envelope.keyId, signature)}.")

        // A assinatura cobre os bytes do payload: só agora descompactamos.
        val json = if (envelope.format == 2) inflate(payload) else payload
        if (json.size > MAX_JSON_BYTES) {
            throw CatalogException("Catálogo grande demais (${json.size} bytes).")
        }
        return Verified(payload, envelope.keyId, verified, json)
    }

    /** true = Ed25519 conferiu; false = aceito pelo hash fixado; null = recusado. */
    private fun verifySignature(payload: ByteArray, signature: ByteArray, keyId: String): Boolean? {
        if (signature.size == 64 && verifyEd25519(payload, signature, keyId)) return true

        val pinned = BuildConfig.CATALOG_PINNED_SHA256.trim().lowercase()
        if (pinned.length == 64 && sha256Hex(payload) == pinned) {
            Log.w(TAG, "catálogo aceito pelo hash fixado (sem Ed25519 neste aparelho)")
            return false
        }
        return null
    }

    private fun reasonFor(keyId: String, signature: ByteArray): String = when {
        signature.isEmpty() -> "sem assinatura"
        CatalogKeys.publicKey(keyId) == null -> "chave '$keyId' desconhecida"
        !ed25519Supported() ->
            "este Android não oferece Ed25519 e não há hash fixado para este catálogo"
        else -> "assinatura inválida"
    }

    /** Descompacta um payload no formato 2 (deflate cru). */
    private fun inflate(payload: ByteArray): ByteArray {
        val inflater = Inflater(true)
        return try {
            inflater.setInput(payload)
            val out = ByteArrayOutputStream(payload.size * 6)
            val buffer = ByteArray(64 * 1024)
            while (!inflater.finished()) {
                val n = try {
                    inflater.inflate(buffer)
                } catch (e: DataFormatException) {
                    throw CatalogException("Catálogo corrompido (payload ilegível).", e)
                }
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private fun verifyEd25519(payload: ByteArray, signature: ByteArray, keyId: String): Boolean {
        val raw = CatalogKeys.publicKey(keyId) ?: return false
        return runCatching {
            val spki = ED25519_SPKI_PREFIX + raw
            val key = KeyFactory.getInstance("Ed25519")
                .generatePublic(X509EncodedKeySpec(spki))
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(key)
            verifier.update(payload)
            verifier.verify(signature)
        }.onFailure { Log.d(TAG, "Ed25519 indisponível: ${it.message}") }
            .getOrDefault(false)
    }

    fun ed25519Supported(): Boolean = runCatching {
        Signature.getInstance("Ed25519")
        KeyFactory.getInstance("Ed25519")
        true
    }.getOrDefault(false)

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
