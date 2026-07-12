package com.mkmemories.mkdownloader

import android.app.Application
import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialisation + mise à jour silencieuse de yt-dlp dès le lancement :
        // les extracteurs YouTube cassent vite quand ils sont datés.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { Engine.ensureReady(this@App) }
            runCatching {
                YoutubeDL.getInstance().updateYoutubeDL(this@App, YoutubeDL.UpdateChannel.STABLE)
            }
        }
    }
}

/** Moteur yt-dlp + ffmpeg embarqué : init, analyse, recherche, chaînes, musique, streaming. */
object Engine {
    @Volatile private var ready = false
    private val mutex = Mutex()

    // Clients d'extraction YouTube. Le client « android » est désormais bloqué
    // par YouTube (« Sign in to confirm you're not a bot »). On tente plusieurs
    // clients robustes ; yt-dlp bascule sur le premier qui répond.
    const val YT_ARGS = "youtube:player_client=default,tv,mweb"

    suspend fun ensureReady(context: Context) = withContext(Dispatchers.IO) {
        if (ready) return@withContext
        mutex.withLock {
            if (ready) return@withLock
            YoutubeDL.getInstance().init(context.applicationContext)
            FFmpeg.getInstance().init(context.applicationContext)
            ready = true
        }
    }

    suspend fun updateYtDlp(context: Context): String = withContext(Dispatchers.IO) {
        ensureReady(context)
        val status = YoutubeDL.getInstance()
            .updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel.STABLE)
        if (status == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE) "yt-dlp déjà à jour"
        else "yt-dlp mis à jour ✔"
    }

    // ---------- Helpers ----------

    private fun runJson(target: String, configure: YoutubeDLRequest.() -> Unit): JSONObject {
        val request = YoutubeDLRequest(target).apply {
            addOption("--extractor-args", YT_ARGS)
            configure()
        }
        val out = YoutubeDL.getInstance().execute(request, null, null).out
        val start = out.indexOf('{')
        require(start >= 0) { "Réponse inattendue de yt-dlp." }
        return JSONObject(out.substring(start))
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun thumbOf(entry: JSONObject, id: String?): String? {
        entry.optJSONArray("thumbnails")?.let { arr ->
            if (arr.length() > 0) arr.optJSONObject(arr.length() - 1)?.optString("url")?.let { if (it.isNotEmpty()) return it }
        }
        entry.optStringOrNull("thumbnail")?.let { return it }
        return if (!id.isNullOrEmpty()) "https://i.ytimg.com/vi/$id/hqdefault.jpg" else null
    }

    private fun videoFromEntry(e: JSONObject): VideoItem? {
        val id = e.optString("id")
        if (id.isEmpty()) return null
        return VideoItem(
            url = e.optStringOrNull("url") ?: "https://www.youtube.com/watch?v=$id",
            title = e.optStringOrNull("title") ?: "Vidéo",
            uploader = e.optStringOrNull("uploader") ?: e.optStringOrNull("channel"),
            durationSec = e.optDouble("duration", 0.0).toInt(),
            thumbnail = thumbOf(e, id),
            channelName = e.optStringOrNull("channel") ?: e.optStringOrNull("uploader"),
            channelUrl = e.optStringOrNull("channel_url") ?: e.optStringOrNull("uploader_url"),
        )
    }

    private fun entries(root: JSONObject): List<JSONObject> {
        val arr: JSONArray = root.optJSONArray("entries") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    // ---------- Analyse d'un lien unique ----------

    /** Ajoute les identifiants TF1/M6 à une requête si l'URL en relève. */
    private fun YoutubeDLRequest.applyCreds(context: Context, url: String) {
        Settings.credsForUrl(context, url)?.let {
            addOption("--username", it.user)
            addOption("--password", it.pass)
        }
    }

    suspend fun getInfo(context: Context, url: String): VideoItem = withContext(Dispatchers.IO) {
        ensureReady(context)
        val o = runJson(url) {
            addOption("--no-playlist"); addOption("--no-warnings"); addOption("--dump-single-json")
            applyCreds(context, url)
        }
        val id = o.optStringOrNull("id")
        VideoItem(
            url = o.optStringOrNull("webpage_url") ?: url,
            title = o.optStringOrNull("title") ?: "Vidéo sans titre",
            uploader = o.optStringOrNull("uploader") ?: o.optStringOrNull("channel"),
            durationSec = o.optDouble("duration", 0.0).toInt(),
            thumbnail = thumbOf(o, id),
            channelName = o.optStringOrNull("channel") ?: o.optStringOrNull("uploader"),
            channelUrl = o.optStringOrNull("channel_url") ?: o.optStringOrNull("uploader_url"),
        )
    }

    /** Détails enrichis d'une vidéo (pour le lecteur premium) : description + méta. */
    data class VideoDetails(
        val description: String?,
        val uploader: String?,
        val viewCount: Long?,
        val uploadDate: String?, // AAAAMMJJ
    )

    suspend fun details(context: Context, url: String): VideoDetails = withContext(Dispatchers.IO) {
        ensureReady(context)
        val o = runJson(url) {
            addOption("--no-playlist"); addOption("--no-warnings"); addOption("--dump-single-json")
            applyCreds(context, url)
        }
        VideoDetails(
            description = o.optStringOrNull("description"),
            uploader = o.optStringOrNull("uploader") ?: o.optStringOrNull("channel"),
            viewCount = o.optLong("view_count", -1L).takeIf { it >= 0 },
            uploadDate = o.optStringOrNull("upload_date"),
        )
    }

    /**
     * Passage le plus **revisionné** d'une vidéo (« moment fort »), calculé à
     * partir des données *most replayed* de YouTube (champ `heatmap`). Renvoie
     * l'intervalle (secondes) à extraire, ou null si la donnée n'existe pas
     * (heatmap absente = vidéo trop récente/peu vue, ou plateforme sans heatmap).
     *
     * Autour du pic, on élargit à la région « chaude » contiguë, avec une durée
     * minimale utilisable et un plafond, centrés sur le pic, plus un léger
     * rembourrage pour ne pas couper au ras.
     */
    suspend fun highlight(context: Context, url: String): IntRange? = withContext(Dispatchers.IO) {
        ensureReady(context)
        val root = runCatching {
            runJson(url) {
                addOption("--no-playlist"); addOption("--no-warnings"); addOption("--dump-single-json")
                applyCreds(context, url)
            }
        }.getOrNull() ?: return@withContext null
        val heat = root.optJSONArray("heatmap") ?: return@withContext null
        val segs = (0 until heat.length()).mapNotNull { i ->
            heat.optJSONObject(i)?.let {
                Triple(it.optDouble("start_time", -1.0), it.optDouble("end_time", -1.0), it.optDouble("value", 0.0))
            }
        }.filter { it.second > it.first && it.first >= 0.0 }
        if (segs.isEmpty()) return@withContext null

        val peakIdx = segs.indices.maxByOrNull { segs[it].third } ?: return@withContext null
        val peak = segs[peakIdx].third
        val threshold = peak * 0.80
        var lo = peakIdx; while (lo - 1 >= 0 && segs[lo - 1].third >= threshold) lo--
        var hi = peakIdx; while (hi + 1 < segs.size && segs[hi + 1].third >= threshold) hi++

        var startS = segs[lo].first
        var endS = segs[hi].second
        val duration = root.optDouble("duration", 0.0).takeIf { it > 0 } ?: endS
        val center = (segs[peakIdx].first + segs[peakIdx].second) / 2.0
        val minLen = 45.0; val maxLen = 180.0; val pad = 3.0
        if (endS - startS < minLen) { startS = center - minLen / 2; endS = center + minLen / 2 }
        if (endS - startS > maxLen) { startS = center - maxLen / 2; endS = center + maxLen / 2 }
        startS = (startS - pad).coerceAtLeast(0.0)
        endS = (endS + pad).coerceAtMost(duration)
        if (endS <= startS) return@withContext null
        startS.toInt()..endS.toInt()
    }

    // ---------- Recherche de vidéos ----------

    suspend fun search(
        context: Context,
        query: String,
        dateFilter: DateFilter = DateFilter.ANY,
        limit: Int = 30,
    ): List<VideoItem> = withContext(Dispatchers.IO) {
        ensureReady(context)
        val target = if (dateFilter.spToken == null) {
            "ytsearch$limit:$query"
        } else {
            "https://www.youtube.com/results?search_query=${enc(query)}&sp=${dateFilter.spToken}"
        }
        val root = runJson(target) {
            addOption("--dump-single-json"); addOption("--flat-playlist")
            addOption("--playlist-end", limit); addOption("--no-warnings")
        }
        entries(root).mapNotNull(::videoFromEntry)
    }

    /** Vidéos tendances du moment (page Tendances YouTube), pour l'accueil découverte. */
    suspend fun trending(context: Context, limit: Int = 25): List<VideoItem> =
        withContext(Dispatchers.IO) {
            ensureReady(context)
            val root = runCatching {
                runJson("https://www.youtube.com/feed/trending") {
                    addOption("--dump-single-json"); addOption("--flat-playlist")
                    addOption("--playlist-end", limit); addOption("--no-warnings")
                }
            }.getOrNull()
            val list = root?.let { entries(it).mapNotNull(::videoFromEntry) }.orEmpty()
            // Repli si la page Tendances ne renvoie rien (structure changeante).
            if (list.isNotEmpty()) list
            else runCatching { search(context, "tendances du moment", DateFilter.WEEK, limit) }
                .getOrDefault(emptyList())
        }

    /**
     * Récupère la transcription (sous-titres) d'une vidéo — manuels puis
     * automatiques (fr, puis en) — via yt-dlp, sans télécharger la vidéo.
     * Renvoie une liste (horodatage ms, texte). Vide si aucun sous-titre.
     */
    suspend fun transcript(context: Context, url: String): List<Pair<Long, String>> =
        withContext(Dispatchers.IO) {
            ensureReady(context)
            val dir = File(context.cacheDir, "subs-${System.currentTimeMillis()}")
            try {
                dir.mkdirs()
                val request = YoutubeDLRequest(url).apply {
                    addOption("--skip-download")
                    addOption("--write-subs")
                    addOption("--write-auto-subs")
                    addOption("--sub-langs", "fr.*,en.*")
                    addOption("--sub-format", "vtt")
                    addOption("--extractor-args", YT_ARGS)
                    addOption("--no-warnings")
                    addOption("-o", "${dir.absolutePath}/%(id)s.%(ext)s")
                }
                runCatching { YoutubeDL.getInstance().execute(request, null, null) }
                val vtt = dir.listFiles()
                    ?.filter { it.name.endsWith(".vtt") }
                    ?.minByOrNull { if (it.name.contains(".fr")) 0 else 1 }
                    ?: return@withContext emptyList()
                parseVtt(vtt.readText())
            } finally {
                dir.deleteRecursively()
            }
        }

    private fun parseVtt(vtt: String): List<Pair<Long, String>> {
        // 1) Extraction brute des cues (horodatage, texte).
        val raw = ArrayList<Pair<Long, String>>()
        val ts = Regex("(\\d{2}):(\\d{2}):(\\d{2})[.,](\\d{3})\\s*-->")
        vtt.replace("\r\n", "\n").split("\n\n").forEach { block ->
            val lines = block.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            val tsLine = lines.firstOrNull { it.contains("-->") } ?: return@forEach
            val m = ts.find(tsLine) ?: return@forEach
            val (h, mi, s, ms) = m.destructured
            val startMs = (h.toLong() * 3600 + mi.toLong() * 60 + s.toLong()) * 1000 + ms.toLong()
            var text = lines.filter {
                !it.contains("-->") && it != "WEBVTT" && !it.startsWith("Kind:") && !it.startsWith("Language:")
            }.joinToString(" ")
            text = text.replace(Regex("<[^>]*>"), "").replace("&nbsp;", " ")
                .replace(Regex("\\s+"), " ").trim()
            if (text.isNotEmpty()) raw.add(startMs to text)
        }
        return dedupeRolling(raw)
    }

    /**
     * Reconstruit un discours continu à partir des sous-titres **roulants**
     * (auto-générés) : chaque cue reprend la fin de la précédente puis ajoute
     * quelques mots. On ne garde que les mots réellement nouveaux (chevauchement
     * calculé au niveau du mot, comparaison insensible à la casse/ponctuation).
     */
    private fun dedupeRolling(raw: List<Pair<Long, String>>): List<Pair<Long, String>> {
        fun norm(w: String) = w.lowercase().trim('.', ',', '!', '?', ';', ':', '"', '\'', '»', '«', '…', '-', '(', ')', '[', ']')
        val out = ArrayList<Pair<Long, String>>()
        var prevN = emptyList<String>()          // mots normalisés du cue précédent
        for ((ms, text) in raw) {
            val words = text.split(' ').filter { it.isNotEmpty() }
            if (words.isEmpty()) continue
            val wordsN = words.map(::norm)
            val maxK = minOf(prevN.size, wordsN.size)
            var overlap = 0
            for (k in maxK downTo 1) {
                if (prevN.subList(prevN.size - k, prevN.size) == wordsN.subList(0, k)) { overlap = k; break }
            }
            val fresh = words.drop(overlap)
            prevN = wordsN
            if (fresh.isEmpty()) continue        // cue entièrement contenu dans le précédent
            out.add(ms to fresh.joinToString(" "))
        }
        return out
    }

    // ---------- Recherche de chaînes ----------

    /** Recherche YouTube filtrée « Chaînes » (jeton sp EgIQAg==). */
    suspend fun searchChannels(context: Context, query: String, limit: Int = 20): List<ChannelItem> =
        withContext(Dispatchers.IO) {
            ensureReady(context)
            val target = "https://www.youtube.com/results?search_query=${enc(query)}&sp=EgIQAg%3D%3D"
            val root = runJson(target) {
                addOption("--dump-single-json"); addOption("--flat-playlist")
                addOption("--playlist-end", limit); addOption("--no-warnings")
            }
            entries(root).mapNotNull { e ->
                val url = e.optStringOrNull("url") ?: e.optStringOrNull("channel_url") ?: return@mapNotNull null
                if (!url.contains("/channel/") && !url.contains("/@") &&
                    !url.contains("/user/") && !url.contains("/c/")
                ) return@mapNotNull null
                ChannelItem(
                    url = url,
                    name = e.optStringOrNull("channel") ?: e.optStringOrNull("title") ?: "Chaîne",
                    thumbnail = thumbOf(e, null),
                )
            }.distinctBy { it.url }
        }

    /** Résout la chaîne d'origine d'une vidéo déjà analysée (pour l'ajouter aux favoris). */
    fun channelFromVideo(item: VideoItem): ChannelItem? {
        val url = item.channelUrl ?: return null
        return ChannelItem(url = url, name = item.channelName ?: "Chaîne", thumbnail = null)
    }

    /** Vidéos récentes d'une chaîne. */
    suspend fun channelVideos(context: Context, channelUrl: String, limit: Int = 30): List<VideoItem> =
        withContext(Dispatchers.IO) {
            ensureReady(context)
            val base = channelUrl.trimEnd('/')
            val target = if (base.endsWith("/videos")) base else "$base/videos"
            val root = runJson(target) {
                addOption("--dump-single-json"); addOption("--flat-playlist")
                addOption("--playlist-end", limit); addOption("--no-warnings")
            }
            entries(root).mapNotNull(::videoFromEntry)
        }

    // ---------- Mode Musique ----------

    /**
     * Recherche audio : puise dans le catalogue YouTube (identique à YouTube Music).
     * Chaque résultat est traité comme un morceau (lecture audio / MP3).
     */
    suspend fun searchMusic(context: Context, query: String, limit: Int = 30): List<VideoItem> =
        search(context, "$query", DateFilter.ANY, limit)

    /**
     * Importe une playlist publique/partagée depuis son lien
     * (music.youtube.com/playlist?list=… ou youtube.com/playlist?list=…).
     * Renvoie (titre, morceaux). Les playlists PRIVÉES (bibliothèque perso)
     * exigent une connexion Google et ne sont pas accessibles par simple lien.
     */
    suspend fun importPlaylist(context: Context, url: String, limit: Int = 500): Pair<String?, List<VideoItem>> =
        withContext(Dispatchers.IO) {
            ensureReady(context)
            val root = runJson(url) {
                addOption("--dump-single-json"); addOption("--flat-playlist")
                addOption("--playlist-end", limit); addOption("--no-warnings")
            }
            val title = root.optStringOrNull("title")
            val items = entries(root).mapNotNull(::videoFromEntry)
            Logs.d("import", "playlist '$title' : ${items.size} morceaux (ex. ${items.firstOrNull()?.url})")
            title to items
        }

    /**
     * URL d'un flux vidéo **progressif unique** (vidéo+audio déjà muxés) pour un
     * démarrage quasi instantané et un cast direct. On privilégie le meilleur MP4
     * combiné (jusqu'à 720p côté YouTube) : pas de fusion à la volée = pas de latence.
     */
    suspend fun streamUrl(context: Context, url: String): String? =
        withContext(Dispatchers.IO) {
            ensureReady(context)
            val request = YoutubeDLRequest(url).apply {
                addOption("--no-playlist"); addOption("--no-warnings")
                addOption("-f", "b[ext=mp4][height<=1080]/b[ext=mp4]/b[height<=720]/best")
                addOption("--extractor-args", YT_ARGS)
                applyCreds(context, url)
                addOption("-g")
            }
            val t0 = android.os.SystemClock.elapsedRealtime()
            val res = YoutubeDL.getInstance().execute(request, null, null).out
                .lineSequence().map { it.trim() }.firstOrNull { it.startsWith("http") }
            Logs.d("perf", "résolution flux direct ${android.os.SystemClock.elapsedRealtime() - t0}ms — $url")
            res
        }

    /**
     * Sources de lecture vidéo pour une hauteur maximale donnée (choix de qualité).
     * Renvoie 1 URL (flux progressif muxé) ou 2 URLs (vidéo + audio à fusionner).
     */
    suspend fun playbackSources(context: Context, url: String, maxHeight: Int): List<String> =
        withContext(Dispatchers.IO) {
            ensureReady(context)
            val fmt = if (maxHeight >= 4000)
                "bv*+ba/b"
            else
                "bv*[height<=$maxHeight]+ba/b[height<=$maxHeight]/b"
            val request = YoutubeDLRequest(url).apply {
                addOption("--no-playlist"); addOption("--no-warnings")
                addOption("-f", fmt)
                addOption("--extractor-args", YT_ARGS)
                applyCreds(context, url)
                addOption("-g")
            }
            val t0 = android.os.SystemClock.elapsedRealtime()
            val res = YoutubeDL.getInstance().execute(request, null, null).out
                .lineSequence().map { it.trim() }.filter { it.startsWith("http") }.toList()
            Logs.d("perf", "résolution vidéo ${android.os.SystemClock.elapsedRealtime() - t0}ms (${res.size} flux, ${maxHeight}p) — $url")
            res
        }

    /**
     * URL du flux audio pour l'écoute musicale.
     * On force l'AAC (m4a, itag 140) : bien plus stable dans ExoPlayer que
     * l'opus/webm de « bestaudio » (qui causait les coupures au bout de
     * quelques secondes). Repli sur un flux progressif muxé si besoin.
     */
    suspend fun audioStreamUrl(context: Context, url: String): String? =
        withContext(Dispatchers.IO) {
            ensureReady(context)
            val request = YoutubeDLRequest(url).apply {
                addOption("--no-playlist"); addOption("--no-warnings")
                addOption("-f", "ba[ext=m4a]/ba[acodec^=mp4a]/b[ext=mp4]/ba/b")
                // Client "android" : URLs directement lisibles, NON limitées
                // (évite le débit en à-coups / coupures toutes les ~10 s).
                addOption("--extractor-args", YT_ARGS)
                addOption("-g")
            }
            val t0 = android.os.SystemClock.elapsedRealtime()
            try {
                val out = YoutubeDL.getInstance().execute(request, null, null).out
                val dt = android.os.SystemClock.elapsedRealtime() - t0
                val stream = out.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("http") }
                if (stream != null) Logs.d("perf", "résolution audio ${dt}ms — $url")
                else Logs.w("resolve", "aucune URL http (${dt}ms) pour $url — sortie: ${out.take(300)}")
                stream
            } catch (e: Exception) {
                val dt = android.os.SystemClock.elapsedRealtime() - t0
                Logs.e("resolve", "échec audio (${dt}ms) $url", e)
                null
            }
        }
}
