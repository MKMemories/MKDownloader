package com.mkmemories.mkdownloader

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 🗄 Sauvegarde COMPLÈTE d'une chaîne YouTube (la sienne) avant fermeture :
 * pour chaque vidéo → fichier vidéo + vignette + description + tags/métadonnées
 * (.info.json), nommés « date - titre [id].* », dans
 * Téléchargements/MKDownloader/Sauvegarde/<chaîne>/.
 *
 * Fonctionne PAR LOTS (3 vidéos en file à la fois), avec garde-fou de stockage
 * (pause automatique sous 3 Go libres) et reprise intelligente : les vidéos
 * déjà archivées sont sautées — on peut faire la sauvegarde en plusieurs fois.
 */
object Backup {

    /**
     * Une vidéo à archiver. [dir] : sous-dossier RELATIF sous Sauvegarde/ (rempli
     * au lancement) ; [prefix] : préfixe de nom pour garder l'ordre d'une playlist
     * (« 001 - »), null hors playlist.
     */
    data class Entry(
        val id: String,
        val title: String,
        val url: String,
        val dir: String = "",
        val prefix: String? = null,
    )

    /** Une playlist de la chaîne (pour l'archivage thématique). */
    data class PlaylistRef(val id: String, val title: String, val url: String)

    private const val PREFS = "mkdl_backup"
    private const val KEY = "state"
    private const val MIN_FREE_BYTES = 3L * 1024 * 1024 * 1024   // seuil de pause
    private const val WAVE = 3                                    // taille d'un lot

    @Volatile var active = false; private set
    @Volatile var dirName = ""; private set
    @Volatile var pausedForSpace = false; private set
    @Volatile private var qualityId = "mp4"
    @Volatile private var total = 0
    private val pending = mutableListOf<Entry>()
    @Volatile private var restored = false

    val totalCount: Int get() = total
    val doneCount: Int
        get() = (total - synchronized(pending) { pending.size } - queuedCount()).coerceAtLeast(0)

    private fun queuedCount(): Int = Downloads.jobs().count {
        it.backupDir != null && it.backupDir.startsWith(dirName) &&
            (it.status == Downloads.Status.QUEUED || it.status == Downloads.Status.RUNNING)
    }

    /** Libellé pour le menu ⚙ (null si aucune sauvegarde en cours). */
    fun statusLabel(context: Context): String? = when {
        !active -> null
        pausedForSpace -> context.getString(R.string.bk_menu_paused, doneCount, total)
        else -> context.getString(R.string.bk_menu_running, doneCount, total)
    }

    // ---------- Analyse de la chaîne ----------

    /** Normalise l'entrée (@pseudo, lien…) vers l'onglet Vidéos de la chaîne. */
    fun normalizeChannelUrl(input: String): String {
        var u = input.trim()
        if (u.isEmpty()) return u
        if (u.startsWith("@")) u = "https://www.youtube.com/$u"
        else if (!u.startsWith("http")) u = "https://www.youtube.com/@$u"
        val hasTab = listOf("/videos", "/shorts", "/streams", "/playlist", "watch?")
            .any { u.contains(it) }
        return if (hasTab) u else u.trimEnd('/') + "/videos"
    }

    /** Énumère TOUTES les vidéos (nom de la chaîne + liste complète, sans limite). */
    suspend fun analyze(context: Context, channelUrl: String): Pair<String, List<Entry>> =
        withContext(Dispatchers.IO) {
            Engine.ensureReady(context)
            val request = YoutubeDLRequest(channelUrl).apply {
                addOption("--dump-single-json")
                addOption("--flat-playlist")
                addOption("--no-warnings")
                addOption("--extractor-args", Engine.YT_ARGS)
                // Cookies du compte : indispensable pour les vidéos non répertoriées.
                Settings.cookiesForUrl(context, channelUrl)?.let {
                    addOption("--cookies", it.absolutePath)
                }
            }
            val out = YoutubeDL.getInstance().execute(request, null, null).out
            val start = out.indexOf('{')
            require(start >= 0) { "Chaîne introuvable." }
            val root = JSONObject(out.substring(start))
            val name = root.optString("channel").ifBlank { root.optString("title") }
                .removeSuffix(" - Videos").ifBlank { "Chaine" }
            val list = mutableListOf<Entry>()
            fun collect(arr: JSONArray?) {
                arr ?: return
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    if (e.has("entries")) { collect(e.optJSONArray("entries")); continue }
                    val id = e.optString("id")
                    if (id.isBlank()) continue
                    val url = e.optString("url").ifBlank { "https://www.youtube.com/watch?v=$id" }
                    list += Entry(id, e.optString("title").ifBlank { id }, url)
                }
            }
            collect(root.optJSONArray("entries"))
            name to list
        }

    /** Liste les playlists publiques de la chaîne (onglet Playlists). */
    suspend fun analyzePlaylists(context: Context, channelInput: String): List<PlaylistRef> =
        withContext(Dispatchers.IO) {
            Engine.ensureReady(context)
            val base = normalizeChannelUrl(channelInput)
                .removeSuffix("/videos").trimEnd('/') + "/playlists"
            val request = YoutubeDLRequest(base).apply {
                addOption("--dump-single-json")
                addOption("--flat-playlist")
                addOption("--no-warnings")
                addOption("--extractor-args", Engine.YT_ARGS)
                Settings.cookiesForUrl(context, base)?.let { addOption("--cookies", it.absolutePath) }
            }
            val out = YoutubeDL.getInstance().execute(request, null, null).out
            val start = out.indexOf('{')
            require(start >= 0) { "Playlists introuvables." }
            val root = JSONObject(out.substring(start))
            val list = mutableListOf<PlaylistRef>()
            fun collect(arr: JSONArray?) {
                arr ?: return
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    if (e.has("entries")) { collect(e.optJSONArray("entries")); continue }
                    val id = e.optString("id")
                    if (id.isBlank()) continue
                    val url = e.optString("url")
                        .ifBlank { "https://www.youtube.com/playlist?list=$id" }
                    list += PlaylistRef(id, e.optString("title").ifBlank { id }, url)
                }
            }
            collect(root.optJSONArray("entries"))
            list
        }

    // ---------- Lancement / pompe par lots ----------

    /**
     * Lance (ou relance) la sauvegarde. Chaque entrée peut viser un sous-dossier
     * (playlist) ; le doublon est vérifié PAR DOSSIER — une même vidéo présente
     * dans deux playlists est archivée dans chacune (dossiers autonomes).
     * Renvoie le nombre de vidéos restant réellement à archiver.
     */
    fun start(context: Context, rootName: String, entries: List<Entry>, quality: Quality): Int {
        val app = context.applicationContext
        dirName = sanitize(rootName)
        qualityId = quality.id
        // Dossier COMPLET par entrée : <chaîne> ou <chaîne>/<playlist>.
        val resolved = entries.map {
            it.copy(dir = if (it.dir.isBlank()) dirName else dirName + "/" + sanitize(it.dir))
        }
        val existingByDir = resolved.map { it.dir }.distinct()
            .associateWith { existingIdsIn(app, it) }
        val todo = resolved.filter { it.id !in existingByDir[it.dir].orEmpty() }
        total = resolved.size
        synchronized(pending) { pending.clear(); pending.addAll(todo) }
        active = true
        pausedForSpace = false
        persist(app)
        pump(app)
        return todo.size
    }

    fun cancel(context: Context) {
        active = false
        synchronized(pending) { pending.clear() }
        Downloads.jobs().filter { it.backupDir != null }.forEach { Downloads.cancel(context, it.id) }
        persist(context.applicationContext)
    }

    /**
     * Remplit la file par vagues de [WAVE], en respectant l'espace disque.
     * Appelée après chaque vidéo terminée → la sauvegarde avance toute seule.
     */
    @Synchronized
    fun pump(context: Context) {
        if (!active) return
        val app = context.applicationContext
        if (freeBytes(app) < MIN_FREE_BYTES) {
            if (!pausedForSpace) {
                pausedForSpace = true
                persist(app)
            }
            return
        }
        pausedForSpace = false
        val quality = QUALITIES.find { it.id == qualityId } ?: QUALITIES.first()
        var slots = WAVE - queuedCount()
        while (slots > 0) {
            val next = synchronized(pending) { if (pending.isEmpty()) null else pending.removeAt(0) }
                ?: break
            val item = VideoItem(
                url = next.url, title = next.title, uploader = null,
                durationSec = 0, thumbnail = null,
            )
            Downloads.startBackup(app, item, quality, next.dir.ifBlank { dirName }, next.prefix)
            slots--
        }
        if (synchronized(pending) { pending.isEmpty() } && queuedCount() == 0) {
            active = false   // sauvegarde terminée
        }
        persist(app)
    }

    // ---------- Stockage ----------

    fun freeBytes(context: Context): Long = runCatching {
        val dir = context.getExternalFilesDir(null) ?: return 0L
        StatFs(dir.absolutePath).availableBytes
    }.getOrDefault(0L)

    /** Estimation GROSSIÈRE de la taille totale selon la qualité choisie. */
    fun estimateBytes(count: Int, quality: Quality): Long {
        val perMb = when (quality.id) {
            "720p" -> 90L
            "1080p" -> 170L
            else -> 230L
        }
        return count * perMb * 1024 * 1024
    }

    fun fmtSize(bytes: Long): String {
        val go = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (go >= 1) String.format("%.1f Go", go)
        else String.format("%d Mo", (bytes / (1024 * 1024)).coerceAtLeast(1))
    }

    /** IDs déjà archivés : fichiers vidéo « … [id].ext » présents dans [dir]. */
    private fun existingIdsIn(context: Context, dir: String): Set<String> {
        val rx = Regex("\\[([A-Za-z0-9_-]{6,})\\]\\.(mp4|webm|mkv|m4v|mov)$")
        val found = mutableSetOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                    arrayOf("%MKDownloader/Sauvegarde/$dir%"),
                    null,
                )?.use { c ->
                    val col = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    while (c.moveToNext()) {
                        rx.find(c.getString(col) ?: "")?.let { found += it.groupValues[1] }
                    }
                }
            }
        } else {
            val base = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                "MKDownloader/Sauvegarde/$dir",
            )
            base.listFiles()?.forEach { f -> rx.find(f.name)?.let { found += it.groupValues[1] } }
        }
        return found
    }

    /** Version publique (pour préparer les sous-dossiers de playlists). */
    fun sanitizeName(name: String) = sanitize(name)

    private fun sanitize(name: String) =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(60).ifBlank { "Chaine" }

    // ---------- Persistance (la sauvegarde survit à un redémarrage) ----------

    private fun persist(context: Context) {
        val o = JSONObject().apply {
            put("active", active)
            put("dir", dirName)
            put("q", qualityId)
            put("total", total)
            val arr = JSONArray()
            synchronized(pending) {
                pending.forEach {
                    arr.put(
                        JSONObject().put("id", it.id).put("t", it.title).put("u", it.url)
                            .put("d", it.dir).put("p", it.prefix ?: JSONObject.NULL),
                    )
                }
            }
            put("pending", arr)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, o.toString()).apply()
    }

    fun restore(context: Context) {
        if (restored) return
        restored = true
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return
        runCatching {
            val o = JSONObject(raw)
            active = o.optBoolean("active", false)
            dirName = o.optString("dir")
            qualityId = o.optString("q", "mp4")
            total = o.optInt("total", 0)
            synchronized(pending) {
                pending.clear()
                val arr = o.optJSONArray("pending") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    pending += Entry(
                        e.optString("id"), e.optString("t"), e.optString("u"),
                        dir = e.optString("d"),
                        prefix = if (e.isNull("p")) null else e.optString("p"),
                    )
                }
            }
        }
    }
}
