package me.eclipse.ptbr

import android.content.Context
import java.io.File

enum class PadLayout { GB, GBA, NES, SNES, SMS, GENESIS }

data class SystemInfo(
    val key: String,
    val name: String,
    val extensions: List<String>,
    val cores: List<String>,
    val layout: PadLayout,
)

object Systems {
    val all = listOf(
        SystemInfo("gb", "Game Boy", listOf("gb"), listOf("mgba_libretro_android.so", "gambatte_libretro_android.so"), PadLayout.GB),
        SystemInfo("gbc", "Game Boy Color", listOf("gbc"), listOf("mgba_libretro_android.so", "gambatte_libretro_android.so"), PadLayout.GB),
        SystemInfo("gba", "Game Boy Advance", listOf("gba"), listOf("mgba_libretro_android.so"), PadLayout.GBA),
        SystemInfo("nes", "Nintendo Entertainment System", listOf("nes"), listOf("fceumm_libretro_android.so", "nestopia_libretro_android.so"), PadLayout.NES),
        SystemInfo("snes", "Super Nintendo", listOf("sfc", "smc", "snes"), listOf("snes9x_libretro_android.so"), PadLayout.SNES),
        SystemInfo("sms", "Sega Master System", listOf("sms"), listOf("genesis_plus_gx_libretro_android.so"), PadLayout.SMS),
        SystemInfo("gg", "Game Gear", listOf("gg"), listOf("genesis_plus_gx_libretro_android.so"), PadLayout.SMS),
        SystemInfo("md", "Mega Drive / Genesis", listOf("md", "gen", "smd"), listOf("genesis_plus_gx_libretro_android.so"), PadLayout.GENESIS),
    )

    fun byKey(key: String) = all.firstOrNull { it.key == key }

    fun extOf(name: String) = name.substringAfterLast('.', "").lowercase()

    fun forFile(name: String): SystemInfo? {
        val ext = extOf(name)
        return all.firstOrNull { ext in it.extensions }
    }

    fun coreDirs(ctx: Context): List<File> =
        listOf(File(ctx.applicationInfo.nativeLibraryDir), File(ctx.filesDir, "cores"))

    /** Procura o núcleo em jniLibs (lib<nome>.so) ou importado em filesDir/cores. */
    fun findCore(ctx: Context, sys: SystemInfo): File? {
        for (name in sys.cores) for (dir in coreDirs(ctx)) {
            for (cand in listOf(name, "lib$name")) {
                val f = File(dir, cand)
                if (f.isFile) return f
            }
        }
        return null
    }
}
