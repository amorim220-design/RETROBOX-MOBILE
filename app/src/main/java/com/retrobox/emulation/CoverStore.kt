package com.retrobox.emulation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.retrobox.domain.Console
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Baixa as capas (box art) do repositorio oficial do libretro
 * (github.com/libretro-thumbnails) e guarda em cache dentro do app.
 *
 * Nenhuma ROM ou conteudo protegido e baixado aqui: apenas imagens de capa.
 */
object CoverStore {

    private const val TAG = "RetroBoxCover"
    /**
     * Repositorio oficial de thumbnails do libretro. O caminho mudou: as
     * pastas usam espaco e o repo vive em `libretro/libretro-thumbnails`
     * (a URL antiga, com sublinhado, responde 404).
     */
    private const val BASE = "https://raw.githubusercontent.com/libretro/libretro-thumbnails/master"
    private const val MAX_WIDTH = 480
    private const val TIMEOUT_MS = 8000

    /** Repositorio de thumbnails de cada sistema. */
    private val REPOSITORIES = mapOf(
        Console.NES to "Nintendo - Nintendo Entertainment System",
        Console.SNES to "Nintendo - Super Nintendo Entertainment System",
        Console.GB to "Nintendo - Game Boy",
        Console.GBC to "Nintendo - Game Boy Color",
        Console.GBA to "Nintendo - Game Boy Advance",
        Console.MEGADRIVE to "Sega - Mega Drive - Genesis",
        Console.N64 to "Nintendo - Nintendo 64",
        Console.PS1 to "Sony - PlayStation",
        Console.PSP to "Sony - PlayStation Portable",
        Console.NDS to "Nintendo - Nintendo DS"
    )

    /** Pastas do repositorio: box art (capa de caixa) e snapshot (foto da tela). */
    private val FOLDERS = listOf("Named_Boxarts", "Named_Snaps")

    /**
     * URL da box art de um jogo pelo titulo oficial (o nome do arquivo no
     * repositorio e o proprio titulo). Usado pelo catalogo: a lista completa
     * mostra a capa oficial sem precisar guardar a URL no catalogo.
     */
    fun boxArtUrl(console: Console, title: String): String? {
        val repository = REPOSITORIES[console] ?: return null
        val clean = title.trim()
        if (clean.isEmpty()) return null
        return "$BASE/$repository/Named_Boxarts/" + encode(clean) + ".png"
    }

    /**
     * Lista de URLs candidatas para a capa (titulo exato e variações). O Coil
     * tenta na ordem até uma responder: no catálogo o nome do jogo e o do
     * arquivo de arte nem sempre são idênticos.
     */
    fun boxArtUrls(console: Console, title: String): List<String> {
        val exact = boxArtUrl(console, title) ?: return emptyList()
        val urls = linkedSetOf(exact)
        GameTitle.variants(title).take(4).forEach { variant ->
            boxArtUrl(console, variant)?.let { urls += it }
        }
        return urls.toList()
    }

    /**
     * Procura a capa de um jogo. Devolve o caminho do arquivo local ou null
     * quando nao existe capa (o app usa um placeholder gerado).
     */
    suspend fun coverFor(context: Context, console: Console, rawTitle: String): String? =
        withContext(Dispatchers.IO) {
            val repository = REPOSITORIES[console] ?: return@withContext null
            val variants = GameTitle.variants(rawTitle)
            if (variants.isEmpty()) return@withContext null

            val target = cacheFile(context, console, variants.first())
            if (target.exists() && target.length() > 0L) return@withContext target.absolutePath

            for (variant in variants) {
                for (folder in FOLDERS) {
                    val url = "$BASE/$repository/$folder/" + encode(variant) + ".png"
                    if (!exists(url)) continue
                    val file = cacheFile(context, console, variant)
                    if (file.exists() && file.length() > 0L) return@withContext file.absolutePath
                    if (download(url, file)) {
                        return@withContext file.absolutePath
                    }
                }
            }
            null
        }

    /** Nome do arquivo em cache (deterministico, reaproveitado entre sessoes). */
    private fun cacheFile(context: Context, console: Console, title: String): File {
        val dir = File(com.retrobox.data.StorageLayout.covers(context), console.id).apply { mkdirs() }
        val key = Integer.toHexString("$console:$title".hashCode())
        return File(dir, "$key.jpg")
    }

    /**
     * Baixa uma capa de uma URL qualquer (ex.: `coverUrl` do catálogo) e aplica
     * o mesmo redimensionamento/cache das capas do libretro.
     */
    suspend fun coverFromUrl(
        context: Context,
        console: Console,
        url: String,
        seed: String
    ): String? = withContext(Dispatchers.IO) {
        // A URL vem de um catálogo assinado; o que barra HTTP para hosts
        // desconhecidos é o network security config do Android.
        if (!url.startsWith("https://") && !url.startsWith("http://")) return@withContext null
        val target = cacheFile(context, console, "url:$seed")
        if (target.exists() && target.length() > 0L) return@withContext target.absolutePath
        if (download(url, target)) target.absolutePath else null
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun exists(url: String): Boolean = try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", "RetroBox/1.0")
        }
        val code = connection.responseCode
        connection.disconnect()
        if (code == 200) true else false
    } catch (e: Exception) {
        Log.w(TAG, "HEAD falhou: $url (${e.message})")
        false
    }

    private fun download(url: String, target: File): Boolean = try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", "RetroBox/1.0")
        }
        if (connection.responseCode != 200) {
            connection.disconnect()
            false
        } else {
            val tmp = File(target.parentFile, target.name + ".part")
            connection.inputStream.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            connection.disconnect()
            val bitmap = BitmapFactory.decodeFile(tmp.absolutePath)
            if (bitmap == null) {
                tmp.delete()
                false
            } else {
                // reduz a imagem para o card da grade (as box arts tem ~600 KB)
                val scaled = if (bitmap.width > MAX_WIDTH) {
                    val ratio = MAX_WIDTH.toFloat() / bitmap.width
                    Bitmap.createScaledBitmap(bitmap, MAX_WIDTH, (bitmap.height * ratio).toInt(), true)
                } else {
                    bitmap
                }
                target.outputStream().use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
                }
                if (scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
                tmp.delete()
                target.length() > 0L
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "download falhou: $url (${e.message})")
        false
    }
}
