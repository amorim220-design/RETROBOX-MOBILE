package com.retrobox.emulation

import android.view.Surface

/**
 * Thin JNI bridge over [retrobox] (`app/src/main/cpp`), a minimal libretro
 * frontend: GLES surface output, AAudio output and digital gamepad input.
 */
object NativeEmulator {

    init {
        System.loadLibrary("retrobox")
    }

    external fun nativeSetDirectories(systemDir: String, saveDir: String): Boolean
    external fun nativeSetLanguage(language: String)
    external fun nativeLoadCore(corePath: String): String?
    external fun nativeUnloadCore()

    external fun nativeSetSurface(surface: Surface, pixelFormat: Int): Boolean
    external fun nativeDestroySurface()

    external fun nativeStartGame(romPath: String, sramPath: String): String?
    external fun nativeStop()

    external fun nativeSetButton(port: Int, id: Int, pressed: Boolean)
    external fun nativeSetFastForward(enabled: Boolean)
    external fun nativeSetLinearFilter(linear: Boolean)
    external fun nativeSetAspectMode(mode: Int)

    external fun nativeReset()
    external fun nativeCanSerialize(): Boolean
    external fun nativeSaveState(): ByteArray?
    external fun nativeLoadState(data: ByteArray): Boolean

    external fun nativeGetFps(): Double
    external fun nativeGeometryInfo(): String
    external fun nativeSystemInfo(): String
}

/** libretro joypad ids (RETRO_DEVICE_ID_JOYPAD_*). */
object RetroButton {
    const val B = 0
    const val Y = 1
    const val SELECT = 2
    const val START = 3
    const val UP = 4
    const val DOWN = 5
    const val LEFT = 6
    const val RIGHT = 7
    const val A = 8
    const val X = 9
    const val L = 10
    const val R = 11
    const val L2 = 12
    const val R2 = 13
    const val L3 = 14
    const val R3 = 15
}

/** libretro pixel formats (RETRO_PIXEL_FORMAT_*). */
object PixelFormat {
    const val RGB1555 = 0
    const val XRGB8888 = 1
    const val RGB565 = 2
}
