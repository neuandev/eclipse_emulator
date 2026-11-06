package me.eclipse.ptbr

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger

class EmulatorActivity : ComponentActivity() {
    private val padMask = AtomicInteger(0)
    private val touchMask = AtomicInteger(0)
    private var started by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var aspect by mutableFloatStateOf(1.5f)
    private var menuOpen by mutableStateOf(false)
    private var slotMode by mutableIntStateOf(0) // 0 = menu, 1 = salvar, 2 = carregar
    private var speedIdx by mutableIntStateOf(0)
    private var resumedFlag = false
    private val speeds = floatArrayOf(1f, 2f, 3f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as EclipseApp
        val game = intent.getStringExtra("id")?.let { app.library.get(it) }
        val sys = game?.let { Systems.byKey(it.system) }
        val core = sys?.let { Systems.findCore(this, it) }
        if (game == null || sys == null || core == null) {
            Toast.makeText(this, "Jogo ou núcleo não encontrado.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let {
            it.hide(WindowInsetsCompat.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        NativeBridge.nativeSetAudio(app.settings.audio)
        NativeBridge.nativeSetSpeed(1f)

        setContent { EclipseTheme { Content(app, game, sys) } }

        lifecycleScope.launch(Dispatchers.Default) {
            val err = NativeBridge.nativeStart(
                core.path, app.library.romFile(game).path, app.library.sysDir.path,
                app.library.savesDir.path, app.library.srmFile(game.id).path,
            )
            if (err == null) app.library.markPlayed(game.id)
            withContext(Dispatchers.Main) {
                if (err != null) error = err
                else {
                    aspect = NativeBridge.nativeAspect()
                    started = true
                    applyPause()
                }
            }
        }
    }

    private fun applyPause() {
        if (started) NativeBridge.nativePause(menuOpen || !resumedFlag)
    }

    override fun onResume() { super.onResume(); resumedFlag = true; applyPause() }
    override fun onPause() { super.onPause(); resumedFlag = false; applyPause() }
    override fun onDestroy() { super.onDestroy(); NativeBridge.nativeStop() }

    // ---------- entrada ----------
    private fun setBit(mask: AtomicInteger, bit: Int, down: Boolean) {
        while (true) {
            val o = mask.get()
            val n = if (down) o or (1 shl bit) else o and (1 shl bit).inv()
            if (mask.compareAndSet(o, n)) break
        }
        NativeBridge.nativeSetButtons(padMask.get() or touchMask.get())
    }

    private fun keyBit(code: Int): Int = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> Btn.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> Btn.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> Btn.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> Btn.RIGHT
        // No RetroPad, A é o botão da direita e B o de baixo (inverso do layout Android).
        KeyEvent.KEYCODE_BUTTON_A -> Btn.B
        KeyEvent.KEYCODE_BUTTON_B -> Btn.A
        KeyEvent.KEYCODE_BUTTON_X -> Btn.Y
        KeyEvent.KEYCODE_BUTTON_Y -> Btn.X
        KeyEvent.KEYCODE_BUTTON_L1 -> Btn.L
        KeyEvent.KEYCODE_BUTTON_R1 -> Btn.R
        KeyEvent.KEYCODE_BUTTON_START -> Btn.START
        KeyEvent.KEYCODE_BUTTON_SELECT -> Btn.SELECT
        else -> -1
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (e.action == KeyEvent.ACTION_DOWN) menuOpen = true
            return true
        }
        val bit = keyBit(e.keyCode)
        if (bit >= 0) {
            if (e.action == KeyEvent.ACTION_DOWN) setBit(padMask, bit, true)
            else if (e.action == KeyEvent.ACTION_UP) setBit(padMask, bit, false)
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if ((e.source and InputDevice.SOURCE_JOYSTICK) != 0 && e.action == MotionEvent.ACTION_MOVE) {
            val x = e.getAxisValue(MotionEvent.AXIS_HAT_X).takeIf { it != 0f } ?: e.getAxisValue(MotionEvent.AXIS_X)
            val y = e.getAxisValue(MotionEvent.AXIS_HAT_Y).takeIf { it != 0f } ?: e.getAxisValue(MotionEvent.AXIS_Y)
            setBit(padMask, Btn.LEFT, x < -0.5f)
            setBit(padMask, Btn.RIGHT, x > 0.5f)
            setBit(padMask, Btn.UP, y < -0.5f)
            setBit(padMask, Btn.DOWN, y > 0.5f)
            return true
        }
        return super.onGenericMotionEvent(e)
    }

    // ---------- interface ----------
    @Composable
    private fun Content(app: EclipseApp, game: Game, sys: SystemInfo) {
        val showControls = app.settings.showControls
        val opacity = app.settings.controlsOpacity
        BackHandler { menuOpen = true }
        LaunchedEffect(menuOpen) { applyPause() }

        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            val landscape = maxWidth > maxHeight
            if (landscape) {
                Box(Modifier.fillMaxSize()) {
                    GameSurface(Modifier.align(Alignment.Center).fillMaxHeight().aspectRatio(aspect))
                    if (showControls) TouchControls(sys.layout, opacity, true, Modifier.fillMaxSize()) { b, d -> setBit(touchMask, b, d) }
                    MenuButton(Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
                }
            } else {
                Column(Modifier.fillMaxSize().displayCutoutPadding().statusBarsPadding()) {
                    GameSurface(Modifier.fillMaxWidth().aspectRatio(aspect))
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (showControls) TouchControls(sys.layout, opacity, false, Modifier.fillMaxSize()) { b, d -> setBit(touchMask, b, d) }
                        MenuButton(Modifier.align(Alignment.TopCenter).padding(top = 4.dp))
                    }
                }
            }
        }

        if (menuOpen) {
            AlertDialog(
                onDismissRequest = { menuOpen = false; slotMode = 0 },
                confirmButton = {},
                title = { Text(game.name) },
                text = {
                    Column {
                        if (slotMode == 0) {
                            MenuItem("Continuar") { menuOpen = false }
                            MenuItem("Salvar estado") { slotMode = 1 }
                            MenuItem("Carregar estado") { slotMode = 2 }
                            MenuItem("Reiniciar") { NativeBridge.nativeReset(); menuOpen = false }
                            MenuItem("Velocidade: ${speeds[speedIdx].toInt()}x") {
                                speedIdx = (speedIdx + 1) % speeds.size
                                NativeBridge.nativeSetSpeed(speeds[speedIdx])
                            }
                            MenuItem("Sair do jogo", Color(0xFFFF6B6B)) { finish() }
                        } else {
                            for (slot in 1..4) {
                                val f = app.library.stateFile(game.id, slot)
                                val label = if (f.exists()) "Slot $slot — ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(f.lastModified()))}" else "Slot $slot — vazio"
                                MenuItem(label) {
                                    if (slotMode == 1) {
                                        val ok = NativeBridge.nativeSaveState(f.path)
                                        toast(if (ok) "Estado salvo no slot $slot" else "Não foi possível salvar o estado")
                                        menuOpen = false
                                    } else if (f.exists()) {
                                        val ok = NativeBridge.nativeLoadState(f.path)
                                        toast(if (ok) "Estado carregado" else "Não foi possível carregar o estado")
                                        menuOpen = false
                                    }
                                    slotMode = 0
                                }
                            }
                            MenuItem("Voltar") { slotMode = 0 }
                        }
                    }
                },
            )
        }

        val e = error
        if (e != null) {
            AlertDialog(
                onDismissRequest = { finish() },
                title = { Text("Não foi possível abrir o jogo") },
                text = { Text(e) },
                confirmButton = { TextButton(onClick = { finish() }) { Text("OK") } },
            )
        }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    @Composable
    private fun MenuButton(modifier: Modifier) {
        Box(
            modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).clickable { menuOpen = true },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.Menu, "Menu", tint = Color.White) }
    }

    @Composable
    private fun GameSurface(modifier: Modifier) {
        AndroidView(
            modifier = modifier,
            factory = { c ->
                SurfaceView(c).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) { NativeBridge.nativeSetSurface(h.surface) }
                        override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hh: Int) {}
                        override fun surfaceDestroyed(h: SurfaceHolder) { NativeBridge.nativeSetSurface(null) }
                    })
                }
            },
        )
    }
}
