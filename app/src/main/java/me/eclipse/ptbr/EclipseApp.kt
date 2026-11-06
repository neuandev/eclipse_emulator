package me.eclipse.ptbr

import android.app.Application

class EclipseApp : Application() {
    val library by lazy { Library(this) }
    val settings by lazy { Settings(this) }
}
