package me.eclipse.ptbr

import android.view.Surface

/** Ponte JNI para o frontend libretro (frontend.cpp). */
object NativeBridge {
    init { System.loadLibrary("eclipsenative") }

    /** Retorna null se deu certo, ou a mensagem de erro. */
    @JvmStatic external fun nativeStart(core: String, rom: String, sysDir: String, saveDir: String, srm: String): String?
    @JvmStatic external fun nativeStop()
    @JvmStatic external fun nativeSetSurface(surface: Surface?)
    @JvmStatic external fun nativeSetButtons(mask: Int)
    @JvmStatic external fun nativePause(paused: Boolean)
    @JvmStatic external fun nativeSetSpeed(speed: Float)
    @JvmStatic external fun nativeSetAudio(on: Boolean)
    @JvmStatic external fun nativeReset()
    @JvmStatic external fun nativeSaveState(path: String): Boolean
    @JvmStatic external fun nativeLoadState(path: String): Boolean
    @JvmStatic external fun nativeAspect(): Float
}

/** Índices dos botões do RetroPad (libretro). */
object Btn {
    const val B = 0; const val Y = 1; const val SELECT = 2; const val START = 3
    const val UP = 4; const val DOWN = 5; const val LEFT = 6; const val RIGHT = 7
    const val A = 8; const val X = 9; const val L = 10; const val R = 11
}
