package com.retrobox.emulation

import com.retrobox.data.CatalogEntry
import com.retrobox.data.GameRepository
import com.retrobox.domain.Console
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * Identificação de ROM sem depender do libretro-db completo.
 *
 * Calcula SHA-256, CRC32 e o *serial* que fica no cabeçalho de cada cartucho.
 * O catálogo guarda o SHA-256, então a identificação é exata mesmo se o
 * arquivo foi renomeado. O serial/CRC ficam disponíveis para quando o
 * `libretro-db.sqlite` for embutido (é só trocar [match] por uma consulta).
 */
object RomIdentifier {

    data class Fingerprint(
        val sha256: String,
        val crc32: Long,
        val serial: String?,
        val sizeBytes: Long
    )

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun fingerprint(file: File, console: Console?): Fingerprint {
        val crc = CRC32()
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                crc.update(buffer, 0, read)
            }
        }
        return Fingerprint(
            sha256 = sha256(file),
            crc32 = crc.value,
            serial = console?.let { readSerial(file, it) },
            sizeBytes = file.length()
        )
    }

    /** Casa o arquivo com um item do catálogo pelo SHA-256. */
    suspend fun match(repository: GameRepository, sha256: String): CatalogEntry? =
        repository.catalogEntryBySha256(sha256)

    /**
     * Serial do cartucho, quando o formato tem um. Best-effort: arquivo
     * truncado ou formato diferente devolve null (nunca lança).
     */
    fun readSerial(file: File, console: Console): String? = runCatching {
        when (console) {
            Console.NES -> readBytes(file, 0x7F, 8)
                ?.filter { it in 0x20..0x7E }
                ?.joinToString("") { it.toInt().toChar().toString() }
                ?.trim()
                ?.takeIf { it.length >= 3 }

            Console.SNES -> readBytes(file, 0x7FC0, 21)
                ?.joinToString("") { it.toInt().toChar().toString() }
                ?.trim()

            Console.GBA -> readBytes(file, 0xAC, 4)
                ?.joinToString("") { "%02X".format(it) }

            Console.N64 -> readBytes(file, 0x3B, 4)
                ?.joinToString("") { "%02X".format(it) }

            Console.MEGADRIVE -> readBytes(file, 0x183, 14)
                ?.joinToString("") { it.toInt().toChar().toString() }
                ?.trim()

            else -> null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun readBytes(file: File, offset: Long, count: Int): ByteArray? = runCatching {
        java.io.RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < offset + count) return null
            raf.seek(offset)
            val bytes = ByteArray(count)
            raf.readFully(bytes)
            bytes
        }
    }.getOrNull()
}
