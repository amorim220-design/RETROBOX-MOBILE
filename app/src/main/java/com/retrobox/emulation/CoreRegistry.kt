package com.retrobox.emulation

import android.content.Context
import com.retrobox.domain.Console
import java.io.File

/**
 * A libretro core shipped inside the APK (`app/src/main/jniLibs/arm64-v8a`).
 * Each core is a self-contained `.so` loaded at runtime with dlopen().
 */
data class CoreInfo(
    val id: String,
    val displayName: String,
    val libraryName: String,
    val systems: List<Console>,
    val requiresBios: Boolean = false
)

object CoreRegistry {

    val cores: List<CoreInfo> = listOf(
        CoreInfo("fceumm", "FCEUmm", "libfceumm_libretro.so", listOf(Console.NES)),
        CoreInfo("snes9x2005_plus", "Snes9x 2005 Plus", "libsnes9x2005_plus_libretro.so", listOf(Console.SNES)),
        CoreInfo("gambatte", "Gambatte", "libgambatte_libretro.so", listOf(Console.GB, Console.GBC)),
        CoreInfo("mgba", "mGBA", "libmgba_libretro.so", listOf(Console.GBA)),
        CoreInfo("genesis_plus_gx", "Genesis Plus GX", "libgenesis_plus_gx_libretro.so", listOf(Console.MEGADRIVE)),
        CoreInfo("pcsx_rearmed", "PCSX-ReARMed", "libpcsx_rearmed_libretro.so", listOf(Console.PS1)),
        CoreInfo("mupen64plus_next", "Mupen64Plus-Next (GLES3)", "libmupen64plus_next_gles3_libretro.so", listOf(Console.N64)),
        CoreInfo("melonds", "melonDS", "libmelonds_libretro.so", listOf(Console.NDS), requiresBios = true),
        CoreInfo("ppsspp", "PPSSPP", "libppsspp_libretro.so", listOf(Console.PSP))
    )

    fun coreFor(console: Console): CoreInfo? = cores.firstOrNull { console in it.systems }

    fun libraryPath(context: Context, core: CoreInfo): String =
        File(context.applicationInfo.nativeLibraryDir, core.libraryName).absolutePath

    fun isInstalled(context: Context, core: CoreInfo): Boolean = File(libraryPath(context, core)).exists()

    fun installedCores(context: Context): List<CoreInfo> = cores.filter { isInstalled(context, it) }
}
