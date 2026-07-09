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
        val request = YoutubeDLRequest(target).apply(configure)
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

    suspend fun getInfo(context: Context, url: String): VideoItem = withContext(Dispatchers.IO) {
        ensureReady(context)
        val o = runJson(url) {
            addOption("--no-playlist"); addOption("--no-warnings"); addOption("--dump-single-json")
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
                addOption("--extractor-args", "youtube:player_client=android")
                addOption("-g")
            }
            YoutubeDL.getInstance().execute(request, null, null).out
                .lineSequence().map { it.trim() }.firstOrNull { it.startsWith("http") }
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
                addOption("--extractor-args", "youtube:player_client=android")
                addOption("-g")
            }
            YoutubeDL.getInstance().execute(request, null, null).out
                .lineSequence().map { it.trim() }.firstOrNull { it.startsWith("http") }
        }
}
