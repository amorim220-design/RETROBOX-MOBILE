package com.retrobox.catalog

import android.util.Base64
import android.util.Log
import com.retrobox.BuildConfig
import com.squareup.moshi.Moshi
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

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
    private const val MAX_PAYLOAD_BYTES = 8 * 1024 * 1024

    /** Prefixo ASN.1 de uma chave pública Ed25519 (SPKI) + os 32 bytes crus. */
    private val ED25519_SPKI_PREFIX = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
    )

    data class Verified(
        val payloadBytes: ByteArray,
        val keyId: String,
        /** true = assinatura Ed25519 válida; false = aceito só pelo hash fixado. */
        val signatureVerified: Boolean
    )

    private val moshi: Moshi = Moshi.Builder().build()
    private val envelopeAdapter = moshi.adapter(CatalogEnvelope::class.java)

    fun verify(envelopeBytes: ByteArray): Verified {
        val envelope = try {
            envelopeAdapter.fromJson(envelopeBytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw CatalogException("Catálogo ilegível (JSON inválido).", e)
        } ?: throw CatalogException("Catálogo vazio.")

        if (envelope.format != 1) {
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

        if (signature.size == 64 && verifyEd25519(payload, signature, envelope.keyId)) {
            return Verified(payload, envelope.keyId, signatureVerified = true)
        }

        val pinned = BuildConfig.CATALOG_PINNED_SHA256.trim().lowercase()
        if (pinned.length == 64 && sha256Hex(payload) == pinned) {
            Log.w(TAG, "catálogo aceito pelo hash fixado (sem Ed25519 neste aparelho)")
            return Verified(payload, envelope.keyId.ifBlank { "pinned" }, signatureVerified = false)
        }

        val reason = if (signature.isEmpty()) {
            "sem assinatura"
        } else if (CatalogKeys.publicKey(envelope.keyId) == null) {
            "chave '${envelope.keyId}' desconhecida"
        } else if (!ed25519Supported()) {
            "este Android não oferece Ed25519 e não há hash fixado para este catálogo"
        } else {
            "assinatura inválida"
        }
        throw CatalogException("Catálogo recusado: $reason.")
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
