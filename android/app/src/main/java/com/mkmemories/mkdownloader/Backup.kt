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

    // ---------- 📋 Fiches : titres + tags + descriptions en clair ----------

    data class Fiche(
        val folder: String,        // relatif sous Sauvegarde/ (« Chaîne » ou « Chaîne/Playlist »)
        val name: String,          // nom du fichier vidéo (sans .info.json)
        val vid: String,           // identifiant YouTube de la vidéo
        val title: String,
        val date: String,
        val url: String,
        val tags: List<String>,
        val description: String,
    )

    /** Fiches groupées par dossier d\u0027archive, triées (ordre des playlists). */
    suspend fun fichesByFolder(context: Context): Map<String, List<Fiche>> =
        withContext(Dispatchers.IO) {
            readAllInfoJson(context.applicationContext)
                .groupBy { it.folder }
                .mapValues { (_, l) -> l.sortedBy { it.name } }
                .toSortedMap()
        }

    // ---------- Suivi de republication (vidéo par vidéo) ----------

    private const val REP_PREFS = "mkdl_republish"

    fun isRepublished(context: Context, folder: String, vid: String): Boolean =
        context.getSharedPreferences(REP_PREFS, Context.MODE_PRIVATE)
            .getStringSet("done", emptySet())!!.contains("$folder|$vid")

    fun setRepublished(context: Context, folder: String, vid: String, on: Boolean) {
        val p = context.getSharedPreferences(REP_PREFS, Context.MODE_PRIVATE)
        val set = HashSet(p.getStringSet("done", emptySet())!!)
        if (on) set.add("$folder|$vid") else set.remove("$folder|$vid")
        p.edit().putStringSet("done", set).apply()
    }

    /**
     * Compile toutes les métadonnées archivées (.info.json) en fichiers TEXTE
     * lisibles, faits pour le copier-coller lors du re-upload :
     * « _FICHES.txt » dans chaque dossier + « _FICHES - tout.txt » à la racine.
     * Tags au format champ YouTube (séparés par des virgules).
     * Renvoie (nombre de fiches, uri du fichier global).
     */
    suspend fun generateIndex(context: Context): Pair<Int, String?> =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val fiches = readAllInfoJson(app)
            if (fiches.isEmpty()) return@withContext 0 to null
            val byFolder = fiches.groupBy { it.folder }.toSortedMap()
            val global = StringBuilder()
            global.append("📋 FICHES DE L\u0027ARCHIVE — ${fiches.size} vidéos\n")
            global.append("Titre, tags (à coller tels quels dans YouTube) et description de chaque vidéo.\n")
            byFolder.forEach { (folder, list) ->
                val sorted = list.sortedBy { it.name }
                val local = StringBuilder()
                local.append("📋 FICHES — $folder (${sorted.size} vidéos)\n")
                local.append("Tags prêts à coller dans YouTube (séparés par des virgules).\n")
                sorted.forEach { local.append(ficheBlock(it)) }
                writeTextFile(app, "MKDownloader/Sauvegarde/$folder", "_FICHES.txt", local.toString())
                global.append("\n\n██████████ DOSSIER : $folder (${sorted.size} vidéos) ██████████\n")
                sorted.forEach { global.append(ficheBlock(it)) }
            }
            val uri = writeTextFile(app, "MKDownloader/Sauvegarde", "_FICHES - tout.txt", global.toString())
            fiches.size to uri
        }

    private fun ficheBlock(f: Fiche): String = buildString {
        append("\n────────────────────────────────────────\n")
        append("🎬 ").append(f.name).append("\n\n")
        append("TITRE :\n").append(f.title).append("\n\n")
        append("DATE : ").append(f.date)
        if (f.url.isNotBlank()) append("    LIEN D\u0027ORIGINE : ").append(f.url)
        append("\n\n")
        append("TAGS (copier-coller tel quel) :\n")
        append(if (f.tags.isEmpty()) "(aucun)" else f.tags.joinToString(", "))
        append("\n\n")
        append("DESCRIPTION (copier-coller) :\n")
        append(f.description.ifBlank { "(vide)" })
        append("\n")
    }

    /** Lit tous les .info.json de l\u0027archive (MediaStore sur Android 10+, fichiers sinon). */
    private fun readAllInfoJson(context: Context): List<Fiche> {
        val out = mutableListOf<Fiche>()
        fun parse(folder: String, fileName: String, text: String) {
            runCatching {
                val o = JSONObject(text)
                val tags = mutableListOf<String>()
                o.optJSONArray("tags")?.let { for (i in 0 until it.length()) tags += it.optString(i) }
                val rawDate = o.optString("upload_date")
                val date = if (rawDate.length == 8) {
                    "${rawDate.substring(0, 4)}-${rawDate.substring(4, 6)}-${rawDate.substring(6, 8)}"
                } else rawDate
                out += Fiche(
                    folder = folder,
                    name = fileName.removeSuffix(".info.json"),
                    vid = o.optString("id"),
                    title = o.optString("title"),
                    date = date,
                    url = o.optString("webpage_url")
                        .ifBlank { o.optString("id").takeIf { it.isNotBlank() }?.let { "https://youtu.be/$it" } ?: "" },
                    tags = tags.filter { it.isNotBlank() },
                    description = o.optString("description"),
                )
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(
                        MediaStore.MediaColumns._ID,
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.RELATIVE_PATH,
                    ),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                    arrayOf("%MKDownloader/Sauvegarde/%", "%.info.json"),
                    null,
                )?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val pathCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                    while (c.moveToNext()) {
                        val uri = android.content.ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(idCol),
                        )
                        val folder = (c.getString(pathCol) ?: "")
                            .substringAfter("Sauvegarde/", "").trim('/')
                        val text = runCatching {
                            context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                        }.getOrNull() ?: continue
                        parse(folder.ifBlank { "(racine)" }, c.getString(nameCol) ?: "", text)
                    }
                }
            }
        } else {
            val base = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                "MKDownloader/Sauvegarde",
            )
            base.walkTopDown().filter { it.isFile && it.name.endsWith(".info.json") }.forEach { f ->
                val folder = f.parentFile?.relativeTo(base)?.path.orEmpty().ifBlank { "(racine)" }
                runCatching { parse(folder, f.name, f.readText()) }
            }
        }
        return out
    }

    /** Écrit (en remplaçant) un fichier texte dans Téléchargements/<subDir>/. */
    internal fun writeTextFile(
        context: Context,
        subDir: String,
        name: String,
        text: String,
        mime: String = "text/plain",
    ): String? = writeBinaryFile(context, subDir, name, text.toByteArray(), mime)

    /** Écrit (en remplaçant) un fichier BINAIRE dans Téléchargements/<subDir>/. */
    internal fun writeBinaryFile(
        context: Context,
        subDir: String,
        name: String,
        bytes: ByteArray,
        mime: String,
    ): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return runCatching {
                val resolver = context.contentResolver
                // Supprime l\u0027ancienne version (sinon MediaStore crée « (1) »).
                resolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                    arrayOf("%$subDir%", name),
                    null,
                )?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    while (c.moveToNext()) {
                        runCatching {
                            resolver.delete(
                                android.content.ContentUris.withAppendedId(
                                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(idCol),
                                ),
                                null, null,
                            )
                        }
                    }
                }
                val values = android.content.ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + subDir)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return@runCatching null
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                uri.toString()
            }.getOrNull()
        } else {
            return runCatching {
                val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), subDir)
                dir.mkdirs()
                val f = File(dir, name)
                f.writeBytes(bytes)
                android.net.Uri.fromFile(f).toString()
            }.getOrNull()
        }
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
