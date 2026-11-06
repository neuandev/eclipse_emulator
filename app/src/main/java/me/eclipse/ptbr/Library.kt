package me.eclipse.ptbr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Game(
    val id: String,
    val fileName: String,
    val name: String,
    val system: String,
    val size: Long,
    val added: Long,
    val last: Long,
    val coverUrl: String?,
    val coverRev: Long,
)

data class AddResult(val added: Int, val skipped: List<String>)

class Library(private val ctx: Context) {
    private val root = ctx.filesDir
    val romsDir = File(root, "roms")
    val coversDir = File(root, "covers")
    val statesDir = File(root, "states")
    val savesDir = File(root, "saves")
    val sysDir = File(root, "system")
    private val file = File(root, "library.json")

    private val _games = MutableStateFlow<List<Game>>(emptyList())
    val games: StateFlow<List<Game>> = _games.asStateFlow()

    init {
        listOf(romsDir, coversDir, statesDir, savesDir, sysDir, File(root, "cores")).forEach { it.mkdirs() }
        load()
    }

    fun get(id: String): Game? = _games.value.firstOrNull { it.id == id }
    fun romFile(g: Game) = File(romsDir, "${g.id}.${Systems.extOf(g.fileName)}")
    fun coverFile(id: String) = File(coversDir, "$id.jpg")
    fun srmFile(id: String) = File(savesDir, "$id.srm")
    fun stateFile(id: String, slot: Int) = File(statesDir, "${id}_$slot.state")

    // ---------- persistência ----------
    @Synchronized private fun load() {
        val list = mutableListOf<Game>()
        try {
            if (file.exists()) {
                val arr = JSONArray(file.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list += Game(
                        id = o.getString("id"), fileName = o.getString("fileName"), name = o.getString("name"),
                        system = o.getString("system"), size = o.optLong("size"), added = o.optLong("added"),
                        last = o.optLong("last"), coverUrl = o.optString("coverUrl").ifEmpty { null },
                        coverRev = o.optLong("coverRev"),
                    )
                }
            }
        } catch (_: Exception) {
        }
        _games.value = list
    }

    @Synchronized private fun update(transform: (List<Game>) -> List<Game>) {
        _games.value = transform(_games.value)
        val arr = JSONArray()
        _games.value.forEach { g ->
            arr.put(JSONObject().apply {
                put("id", g.id); put("fileName", g.fileName); put("name", g.name); put("system", g.system)
                put("size", g.size); put("added", g.added); put("last", g.last)
                put("coverUrl", g.coverUrl ?: ""); put("coverRev", g.coverRev)
            })
        }
        val tmp = File(root, "library.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }

    private fun patch(id: String, f: (Game) -> Game) = update { l -> l.map { if (it.id == id) f(it) else it } }

    // ---------- jogos ----------
    suspend fun addFromUris(uris: List<Uri>): AddResult = withContext(Dispatchers.IO) {
        var added = 0
        val skipped = mutableListOf<String>()
        for (uri in uris) {
            val name = displayName(uri) ?: "jogo"
            val sys = Systems.forFile(name)
            if (sys == null) { skipped += name; continue }
            val id = "${System.currentTimeMillis()}-${(Math.random() * 1e6).toInt()}"
            val dest = File(romsDir, "$id.${Systems.extOf(name)}")
            try {
                ctx.contentResolver.openInputStream(uri)!!.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
            } catch (e: Exception) {
                dest.delete(); skipped += name; continue
            }
            val clean = name.substringBeforeLast('.').replace('_', ' ').trim().ifEmpty { name }
            val g = Game(id, name, clean, sys.key, dest.length(), System.currentTimeMillis(), 0, null, 0)
            update { it + g }
            added++
        }
        AddResult(added, skipped)
    }

    private fun displayName(uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (_: Exception) { null }

    fun rename(id: String, name: String) = patch(id) { it.copy(name = name) }
    fun markPlayed(id: String) = patch(id) { it.copy(last = System.currentTimeMillis()) }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val g = get(id) ?: return@withContext
        romFile(g).delete(); coverFile(id).delete(); srmFile(id).delete()
        statesDir.listFiles()?.filter { it.name.startsWith("${id}_") }?.forEach { it.delete() }
        update { l -> l.filter { it.id != id } }
    }

    // ---------- capas (sempre guardadas no aparelho => funcionam offline) ----------
    suspend fun setCoverFromUri(id: String, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            val bmp = decodeScaled(bytes) ?: return@withContext false
            saveCover(id, bmp)
            patch(id) { it.copy(coverUrl = null, coverRev = System.currentTimeMillis()) }
            true
        } catch (_: Exception) { false }
    }

    /** Salva o link (para tentar de novo depois) e tenta baixar agora. Retorna true se baixou. */
    suspend fun setCoverFromUrl(id: String, url: String): Boolean = withContext(Dispatchers.IO) {
        coverFile(id).delete()
        patch(id) { it.copy(coverUrl = url, coverRev = System.currentTimeMillis()) }
        downloadCover(id, url)
    }

    fun removeCover(id: String) {
        coverFile(id).delete()
        patch(id) { it.copy(coverUrl = null, coverRev = System.currentTimeMillis()) }
    }

    /** Baixa as capas que têm link mas ainda não foram guardadas (chamado ao abrir o app). */
    suspend fun retryPendingCovers() = withContext(Dispatchers.IO) {
        _games.value.filter { it.coverUrl != null && !coverFile(it.id).exists() }
            .forEach { downloadCover(it.id, it.coverUrl!!) }
    }

    private fun downloadCover(id: String, url: String): Boolean {
        if (!url.startsWith("https://", ignoreCase = true)) return false
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) EclipseNative/0.1")
            if (c.responseCode !in 200..299) return false
            val bytes = c.inputStream.use { it.readBytes() }
            if (bytes.size > 15_000_000) return false
            val bmp = decodeScaled(bytes) ?: return false
            saveCover(id, bmp)
            // Só marca como atualizada se o jogo ainda usa este mesmo link
            patch(id) { if (it.coverUrl == url) it.copy(coverRev = System.currentTimeMillis()) else it }
            true
        } catch (_: Exception) { false }
    }

    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
        if (b.outWidth <= 0) return null
        var sample = 1
        while (maxOf(b.outWidth, b.outHeight) / (sample * 2) >= 600) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val scale = minOf(1f, 480f / maxOf(bmp.width, bmp.height))
        return if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true) else bmp
    }

    private fun saveCover(id: String, bmp: Bitmap) {
        coverFile(id).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
    }

    // ---------- núcleos e armazenamento ----------
    suspend fun importCore(uri: Uri): String? = withContext(Dispatchers.IO) {
        val name = displayName(uri) ?: return@withContext null
        if (!name.endsWith(".so")) return@withContext null
        val dest = File(root, "cores/$name")
        dest.setWritable(true)
        ctx.contentResolver.openInputStream(uri)!!.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
        dest.setReadOnly() // Android 14+ exige que código carregado dinamicamente seja somente leitura
        name
    }

    suspend fun usageBytes(): Long = withContext(Dispatchers.IO) {
        listOf(romsDir, coversDir, statesDir, savesDir).sumOf { d -> d.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
    }
}
