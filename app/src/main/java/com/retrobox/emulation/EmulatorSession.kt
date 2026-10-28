package com.retrobox.emulation

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.Surface
import com.retrobox.domain.Console
import java.io.File
import java.util.Locale

class EmulatorException(message: String) : Exception(message)

/**
 * Owns one emulation run: picks the core, resolves the ROM to a real path (or an
 * /proc/self/fd/N descriptor for SAF content) and drives [NativeEmulator].
 */
class EmulatorSession(private val context: Context) {

    private var romPfd: ParcelFileDescriptor? = null
    private var gameKey: String = "game"

    private val systemDir = File(context.filesDir, "system").apply { mkdirs() }
    private val saveDir = File(context.filesDir, "saves").apply { mkdirs() }
    private val stateDir = File(context.filesDir, "states").apply { mkdirs() }

    private var core: CoreInfo? = null
    private var romPath: String? = null
    private var sramPath: String? = null

    var started = false
        private set

    fun prepare(console: Console, filePath: String, title: String) {
        val resolvedCore = CoreRegistry.coreFor(console)
            ?: throw EmulatorException("Nenhum núcleo disponível para ${console.displayName}.")
        if (!CoreRegistry.isInstalled(context, resolvedCore)) {
            throw EmulatorException("O núcleo ${resolvedCore.displayName} não está neste APK.")
        }

        gameKey = sanitize(title) + "-" + Integer.toHexString(filePath.hashCode())
        romPath = resolveRom(filePath)
        sramPath = File(saveDir, "$gameKey.srm").absolutePath
        core = resolvedCore
    }

    /** Runs the blocking JNI startup. Call it from a worker thread. */
    fun start(surface: Surface): String {
        val activeCore = core ?: return "Sessão não preparada."
        val rom = romPath ?: return "ROM não encontrada."

        NativeEmulator.nativeSetDirectories(systemDir.absolutePath, saveDir.absolutePath)

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
        return ""
    }

    fun stop() {
        if (started) {
            NativeEmulator.nativeStop()
            NativeEmulator.nativeUnloadCore()
            started = false
        }
        NativeEmulator.nativeDestroySurface()
        romPfd?.close()
        romPfd = null
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

    private fun resolveRom(filePath: String): String {
        if (filePath.startsWith("demo://")) {
            throw EmulatorException(
                "Este item é só demonstração. Importe um jogo real com o botão +."
            )
        }
        val uri = Uri.parse(filePath)
        return when (uri.scheme) {
            "content" -> {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw EmulatorException("Sem permissão para abrir este arquivo.")
                romPfd?.close()
                romPfd = pfd
                // The core opens the file itself; keeping the fd alive keeps the path valid.
                "/proc/self/fd/${pfd.fd}"
            }
            "file" -> uri.path ?: throw EmulatorException("Caminho inválido.")
            null -> filePath
            else -> throw EmulatorException("Origem não suportada: ${uri.scheme}")
        }
    }

    private fun sanitize(value: String): String {
        val clean = value.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        return if (clean.isEmpty()) "game" else clean.take(48)
    }
}
