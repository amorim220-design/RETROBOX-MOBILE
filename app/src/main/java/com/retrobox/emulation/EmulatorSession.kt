package com.retrobox.emulation

import android.content.Context
import android.net.Uri
import android.view.Surface
import com.retrobox.data.StorageLayout
import com.retrobox.domain.Console
import java.io.File
import java.util.Locale

class EmulatorException(message: String) : Exception(message)

/**
 * Owns one emulation run: picks the core, resolves the ROM to a real file inside
 * the app storage and drives [NativeEmulator].
 */
class EmulatorSession(private val context: Context) {

    private var gameKey: String = "game"

    private val systemDir = StorageLayout.system(context)
    private val saveDir = StorageLayout.saves(context)
    private val stateDir = StorageLayout.states(context)
    private val romDir = StorageLayout.roms(context)

    private var core: CoreInfo? = null
    private var romPath: String? = null
    private var sramPath: String? = null

    var console: Console? = null
        private set

    var gameTitle: String = ""
        private set

    val coreName: String get() = core?.displayName ?: "—"

    var started = false
        private set

    fun prepare(console: Console, filePath: String, title: String) {
        val resolvedCore = CoreRegistry.coreFor(console)
            ?: throw EmulatorException("Nenhum núcleo disponível para ${console.displayName}.")
        if (!CoreRegistry.isInstalled(context, resolvedCore)) {
            throw EmulatorException("O núcleo ${resolvedCore.displayName} não está neste APK.")
        }

        this.console = console
        this.gameTitle = title
        gameKey = sanitize(title) + "-" + Integer.toHexString(filePath.hashCode())
        sramPath = File(saveDir, "$gameKey.srm").absolutePath
        pendingSource = filePath
        core = resolvedCore
    }

    /**
     * Runs the blocking JNI startup. Call it from a worker thread: the ROM may
     * need to be copied into the app storage first.
     */
    fun start(surface: Surface): String {
        val activeCore = core ?: return "Sessão não preparada."
        val rom = try {
            resolveRom(pendingSource ?: return "ROM não encontrada.")
        } catch (e: Exception) {
            return e.message ?: "Não foi possível preparar a ROM."
        }

        NativeEmulator.nativeSetDirectories(systemDir.absolutePath, saveDir.absolutePath)
        NativeEmulator.nativeSetLanguage(localeLanguage())

        val coreError = NativeEmulator.nativeLoadCore(CoreRegistry.libraryPath(context, activeCore))
        if (coreError != null) return "Falha ao carregar ${activeCore.displayName}: $coreError"

        if (!NativeEmulator.nativeSetSurface(surface, PixelFormat.XRGB8888)) {
            NativeEmulator.nativeUnloadCore()
            return "Não foi possível criar a superfície OpenGL."
        }

        val startError = NativeEmulator.nativeStartGame(rom, sramPath ?: "")
        if (startError != null) {
            NativeEmulator.nativeDestroySurface()
            NativeEmulator.nativeUnloadCore()
            return startError
        }
        started = true
        romPath = rom
        return ""
    }

    /**
     * Encerra a sessão. Com [saveState] = true tenta gravar um save state antes
     * de descarregar o núcleo (o núcleo também grava a SRAM ao sair).
     */
    fun stop(saveState: Boolean = false) {
        if (started) {
            if (saveState) runCatching { saveState(0) }
            NativeEmulator.nativeStop()
            NativeEmulator.nativeUnloadCore()
            started = false
        }
        NativeEmulator.nativeDestroySurface()
    }

    fun saveState(slot: Int = 0): Boolean {
        if (!started || !NativeEmulator.nativeCanSerialize()) return false
        val data = NativeEmulator.nativeSaveState() ?: return false
        return runCatching {
            File(stateDir, "$gameKey.$slot.state").writeBytes(data)
        }.isSuccess
    }

    fun loadState(slot: Int = 0): Boolean {
        if (!started) return false
        val file = File(stateDir, "$gameKey.$slot.state")
        if (!file.exists()) return false
        return NativeEmulator.nativeLoadState(file.readBytes())
    }

    fun hasState(slot: Int = 0): Boolean = File(stateDir, "$gameKey.$slot.state").exists()

    fun sramFile(): File? = sramPath?.let { File(it) }

    data class Diagnostics(
        val console: String,
        val core: String,
        val romPath: String?,
        val romBytes: Long,
        val hasSram: Boolean
    )

    fun diagnostics(): Diagnostics = Diagnostics(
        console = console?.displayName ?: "—",
        core = coreName,
        romPath = romPath,
        romBytes = romPath?.let { File(it).length() } ?: 0L,
        hasSram = sramFile()?.exists() == true
    )

    private var pendingSource: String? = null

    /**
     * A libretro core opens the ROM by itself, so it needs a real path. Files
     * picked through SAF are copied once into the app storage (a `content://`
     * descriptor cannot be re-opened by the core).
     */
    private fun resolveRom(filePath: String): String {
        if (filePath.startsWith("demo://")) {
            throw EmulatorException(
                "Este item é só demonstração. Importe um jogo real com o botão +."
            )
        }
        pendingSource = filePath
        val uri = Uri.parse(filePath)
        return when (uri.scheme) {
            "content" -> copyToLibrary(uri)
            "file" -> uri.path ?: throw EmulatorException("Caminho inválido.")
            null -> filePath
            else -> throw EmulatorException("Origem não suportada: ${uri.scheme}")
        }
    }

    private fun copyToLibrary(uri: Uri): String {
        val original = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "rom"
        val target = File(romDir, "${Integer.toHexString(uri.toString().hashCode())}-$original")
        if (target.exists() && target.length() > 0L) return target.absolutePath

        val stream = context.contentResolver.openInputStream(uri)
            ?: throw EmulatorException("Sem permissão para ler este arquivo.")
        try {
            target.parentFile?.mkdirs()
            target.outputStream().use { out ->
                stream.use { input -> input.copyTo(out, 256 * 1024) }
            }
        } catch (e: Exception) {
            target.delete()
            throw EmulatorException("Falha ao copiar a ROM: ${e.message}")
        }
        return target.absolutePath
    }

    /**
     * Idioma no formato do libretro (o core usa para escolher a regiao do
     * emulador, por exemplo NTSC-J para japones e NTSC-U para os demais).
     */
    private fun localeLanguage(): String {
        val name = java.util.Locale.getDefault().getDisplayLanguage(java.util.Locale.ENGLISH)
        return when (name.lowercase(java.util.Locale.ROOT)) {
            "portuguese" -> "Portuguese"
            "japanese" -> "Japanese"
            "spanish" -> "Spanish"
            "french" -> "French"
            "german" -> "German"
            "italian" -> "Italian"
            "korean" -> "Korean"
            "chinese" -> "Chinese"
            else -> "English"
        }
    }

    private fun sanitize(value: String): String {
        val clean = value.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        return if (clean.isEmpty()) "game" else clean.take(48)
    }
}
