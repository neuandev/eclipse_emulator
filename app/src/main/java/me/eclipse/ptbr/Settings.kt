package me.eclipse.ptbr

import android.content.Context

class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 0 = nome, 1 = data de adição, 2 = último jogo */
    var sort: Int
        get() = p.getInt("sort", 0)
        set(v) = p.edit().putInt("sort", v).apply()
    var audio: Boolean
        get() = p.getBoolean("audio", true)
        set(v) = p.edit().putBoolean("audio", v).apply()
    var showControls: Boolean
        get() = p.getBoolean("showControls", true)
        set(v) = p.edit().putBoolean("showControls", v).apply()
    var controlsOpacity: Float
        get() = p.getFloat("controlsOpacity", 0.6f)
        set(v) = p.edit().putFloat("controlsOpacity", v).apply()
}
