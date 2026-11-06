package me.eclipse.ptbr

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { EclipseTheme { AppRoot() } }
    }
}

@Composable
fun EclipseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF4C8DFF),
            background = Color(0xFF0E1117),
            surface = Color(0xFF161B22),
            surfaceVariant = Color(0xFF1F2630),
        ),
        content = content,
    )
}

@Composable
fun AppRoot() {
    val app = LocalContext.current.applicationContext as EclipseApp
    var screen by remember { mutableStateOf("library") }
    LaunchedEffect(Unit) { app.library.retryPendingCovers() }
    BackHandler(enabled = screen != "library") { screen = "library" }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        if (screen == "settings") SettingsScreen(app) { screen = "library" }
        else LibraryScreen(app) { screen = "settings" }
    }
}

@Composable
fun MenuItem(text: String, color: Color = Color.Unspecified, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(text, color = color, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
    }
}

fun fmtBytes(b: Long): String = when {
    b >= 1L shl 30 -> String.format("%.2f GB", b / (1 shl 30).toDouble())
    b >= 1L shl 20 -> String.format("%.1f MB", b / (1 shl 20).toDouble())
    else -> "${b / 1024} KB"
}

// ---------------------------------------------------------------- Biblioteca

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(app: EclipseApp, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val games by app.library.games.collectAsState()
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var sort by remember { mutableIntStateOf(app.settings.sort) }
    var plusMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var menuGame by remember { mutableStateOf<Game?>(null) }
    var renameGame by remember { mutableStateOf<Game?>(null) }
    var urlGame by remember { mutableStateOf<Game?>(null) }
    var deleteGame by remember { mutableStateOf<Game?>(null) }
    var coverPickGame by remember { mutableStateOf<Game?>(null) }

    fun toast(t: String) = Toast.makeText(ctx, t, Toast.LENGTH_LONG).show()

    val addLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val r = app.library.addFromUris(uris)
            if (r.skipped.isNotEmpty()) toast("Formato não suportado (descompacte .zip antes): ${r.skipped.joinToString()}")
            else toast(if (r.added == 1) "Jogo adicionado" else "${r.added} jogos adicionados")
        }
    }
    val coverLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val g = coverPickGame
        coverPickGame = null
        if (uri != null && g != null) scope.launch {
            toast(if (app.library.setCoverFromUri(g.id, uri)) "Capa definida" else "Não consegui ler a imagem")
        }
    }

    fun play(g: Game) {
        val sys = Systems.byKey(g.system) ?: return
        if (Systems.findCore(ctx, sys) == null) {
            toast("Falta o núcleo de ${sys.name}. Importe em Ajustes > Núcleos.")
            return
        }
        ctx.startActivity(Intent(ctx, EmulatorActivity::class.java).putExtra("id", g.id))
    }

    val shown = remember(games, query, sort) {
        val f = games.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
        when (sort) {
            1 -> f.sortedByDescending { it.added }
            2 -> f.sortedByDescending { it.last }
            else -> f.sortedBy { it.name.lowercase() }
        }
    }
    val recent = remember(games) { games.filter { it.last > 0 }.sortedByDescending { it.last }.take(10) }
    val bg = MaterialTheme.colorScheme.background

    Scaffold(
        containerColor = bg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bg),
                title = {
                    if (searching) TextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text("Pesquisar") }, modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                    ) else Text("Biblioteca")
                },
                navigationIcon = { IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Ajustes") } },
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                        Icon(if (searching) Icons.Default.Close else Icons.Default.Search, "Pesquisar")
                    }
                    Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.Default.List, "Ordenar") }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            listOf("Nome", "Data de adição", "Último jogo").forEachIndexed { i, t ->
                                DropdownMenuItem(
                                    text = { Text(if (sort == i) "✓ $t" else t) },
                                    onClick = { sort = i; app.settings.sort = i; sortMenu = false },
                                )
                            }
                        }
                    }
                    // O atalho de adicionar jogo só aparece ao tocar no +
                    Box {
                        IconButton(onClick = { plusMenu = true }) { Icon(Icons.Default.Add, "Adicionar") }
                        DropdownMenu(expanded = plusMenu, onDismissRequest = { plusMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Adicionar jogo (arquivo)") },
                                onClick = { plusMenu = false; addLauncher.launch(arrayOf("*/*")) },
                            )
                        }
                    }
                },
            )
        },
    ) { pad ->
        if (games.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(pad).padding(32.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Biblioteca vazia", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Você não tem jogos na biblioteca. Toque em + para adicionar um jogo.", textAlign = TextAlign.Center, color = Color.Gray)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = pad.calculateTopPadding() + 4.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (recent.isNotEmpty() && query.isBlank()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { Text("Continuar jogando", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(recent, key = { it.id }) { g ->
                                Box(Modifier.width(112.dp)) { GameTile(app, g, { play(g) }, { menuGame = g }) }
                            }
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) { Text("Todos os jogos", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("Sem resultados", color = Color.Gray)
                }
                items(shown, key = { it.id }) { g -> GameTile(app, g, { play(g) }, { menuGame = g }) }
            }
        }
    }

    // ---- diálogos ----
    menuGame?.let { g ->
        AlertDialog(
            onDismissRequest = { menuGame = null }, confirmButton = {}, title = { Text(g.name) },
            text = {
                Column {
                    MenuItem("Jogar") { menuGame = null; play(g) }
                    MenuItem("Definir capa (galeria)") { menuGame = null; coverPickGame = g; coverLauncher.launch("image/*") }
                    MenuItem("Definir capa (link da internet)") { menuGame = null; urlGame = g }
                    if (g.coverUrl != null || app.library.coverFile(g.id).exists())
                        MenuItem("Remover capa") { menuGame = null; app.library.removeCover(g.id) }
                    MenuItem("Renomear") { menuGame = null; renameGame = g }
                    MenuItem("Excluir", Color(0xFFFF6B6B)) { menuGame = null; deleteGame = g }
                }
            },
        )
    }
    renameGame?.let { g ->
        var text by remember(g.id) { mutableStateOf(g.name) }
        AlertDialog(
            onDismissRequest = { renameGame = null }, title = { Text("Renomear") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { if (text.isNotBlank()) app.library.rename(g.id, text.trim()); renameGame = null }) { Text("Salvar") } },
            dismissButton = { TextButton(onClick = { renameGame = null }) { Text("Cancelar") } },
        )
    }
    urlGame?.let { g ->
        var text by remember(g.id) { mutableStateOf(g.coverUrl ?: "https://") }
        AlertDialog(
            onDismissRequest = { urlGame = null }, title = { Text("Capa por link") },
            text = {
                Column {
                    OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("https://...") })
                    Spacer(Modifier.height(6.dp))
                    Text("A imagem é baixada e guardada no aparelho, então a capa aparece mesmo sem internet.", fontSize = 12.sp, color = Color.Gray)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val u = text.trim()
                    urlGame = null
                    if (!u.startsWith("https://", ignoreCase = true)) { toast("Use um link que comece com https://"); return@TextButton }
                    scope.launch {
                        toast("Baixando capa...")
                        val ok = app.library.setCoverFromUrl(g.id, u)
                        toast(if (ok) "Capa salva no aparelho (funciona offline)" else "Link salvo, mas não consegui baixar agora. Tentarei de novo quando houver internet.")
                    }
                }) { Text("Salvar") }
            },
            dismissButton = { TextButton(onClick = { urlGame = null }) { Text("Cancelar") } },
        )
    }
    deleteGame?.let { g ->
        AlertDialog(
            onDismissRequest = { deleteGame = null }, title = { Text("Excluir jogo") },
            text = { Text("Tem certeza de que deseja excluir ${g.name}? Os saves deste jogo também serão apagados.") },
            confirmButton = { TextButton(onClick = { deleteGame = null; scope.launch { app.library.delete(g.id) } }) { Text("Excluir", color = Color(0xFFFF6B6B)) } },
            dismissButton = { TextButton(onClick = { deleteGame = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
fun GameTile(app: EclipseApp, g: Game, onPlay: () -> Unit, onMenu: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = onPlay)) {
            CoverImage(app, g, Modifier.fillMaxSize())
            Text(
                g.system.uppercase(), color = Color.White, fontSize = 10.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 2.dp),
            )
            IconButton(
                onClick = onMenu,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(30.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(15.dp)),
            ) { Icon(Icons.Default.MoreVert, "Opções", tint = Color.White, modifier = Modifier.size(18.dp)) }
        }
        Text(
            g.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

@Composable
fun CoverImage(app: EclipseApp, g: Game, modifier: Modifier) {
    val bmp by produceState<ImageBitmap?>(null, g.id, g.coverRev) {
        value = withContext(Dispatchers.IO) {
            val f = app.library.coverFile(g.id)
            if (f.exists()) BitmapFactory.decodeFile(f.path)?.asImageBitmap() else null
        }
    }
    val b = bmp
    if (b != null) {
        Image(bitmap = b, contentDescription = g.name, contentScale = ContentScale.Crop, modifier = modifier)
    } else {
        val c = when (g.system) {
            "gba" -> Color(0xFF5B4A9E); "gbc" -> Color(0xFF2E8B57); "gb" -> Color(0xFF6B8E23)
            "nes" -> Color(0xFFB22222); "snes" -> Color(0xFF4B5FA8); "sms", "gg" -> Color(0xFF1E6FB5)
            else -> Color(0xFF444444)
        }
        Box(modifier.background(Brush.linearGradient(listOf(c, Color(0xFF222222)))), contentAlignment = Alignment.BottomStart) {
            Text(g.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 3, modifier = Modifier.padding(8.dp))
        }
    }
}

// ---------------------------------------------------------------- Ajustes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: EclipseApp, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var audio by remember { mutableStateOf(app.settings.audio) }
    var showControls by remember { mutableStateOf(app.settings.showControls) }
    var opacity by remember { mutableFloatStateOf(app.settings.controlsOpacity) }
    var usage by remember { mutableLongStateOf(0L) }
    var coresRev by remember { mutableIntStateOf(0) }
    LaunchedEffect(coresRev) { usage = app.library.usageBytes() }

    val coreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch {
            var n = 0
            uris.forEach { if (app.library.importCore(it) != null) n++ }
            coresRev++
            Toast.makeText(ctx, "$n núcleo(s) importado(s)", Toast.LENGTH_SHORT).show()
        }
    }
    val bg = MaterialTheme.colorScheme.background

    Scaffold(
        containerColor = bg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bg),
                title = { Text("Ajustes") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") } },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SectionTitle("Áudio") }
            item { SwitchRow("Áudio ativado", audio) { audio = it; app.settings.audio = it } }
            item { SectionTitle("Controles") }
            item { SwitchRow("Mostrar controles na tela", showControls) { showControls = it; app.settings.showControls = it } }
            item {
                Text("Opacidade dos controles")
                Slider(value = opacity, onValueChange = { opacity = it; app.settings.controlsOpacity = it }, valueRange = 0.2f..1f)
            }
            item { SectionTitle("Núcleos") }
            items(Systems.all) { sys ->
                val core = remember(coresRev) { Systems.findCore(ctx, sys) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(sys.name, modifier = Modifier.weight(1f))
                    Text(
                        core?.name?.removePrefix("lib")?.removeSuffix("_libretro_android.so") ?: "ausente",
                        color = if (core != null) Color(0xFF6BD68A) else Color(0xFFFF6B6B), fontSize = 13.sp,
                    )
                }
            }
            item {
                Button(onClick = { coreLauncher.launch(arrayOf("*/*")) }) { Text("Importar núcleo (.so)") }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Use núcleos libretro para Android arm64-v8a (ex.: mgba_libretro_android.so, snes9x_libretro_android.so, " +
                        "fceumm_libretro_android.so, genesis_plus_gx_libretro_android.so).",
                    fontSize = 12.sp, color = Color.Gray,
                )
            }
            item { SectionTitle("Armazenamento") }
            item { Text("Uso pelo app: ${fmtBytes(usage)}") }
            item { SectionTitle("Sobre") }
            item {
                Text(
                    "Eclipse Nativo PT-BR: frontend libretro para Android, com interface inspirada no Eclipse (eclipseemu.me). " +
                        "Uso pessoal.",
                    fontSize = 13.sp, color = Color.Gray,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(t: String) {
    Text(t, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
