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
import org.json.JSONObject

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

/** Moteur yt-dlp + ffmpeg embarqué : initialisation, analyse, recherche, streaming. */
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

    /** Analyse d'un lien unique (Facebook, YouTube, Instagram…). */
    suspend fun getInfo(context: Context, url: String): VideoItem = withContext(Dispatchers.IO) {
        ensureReady(context)
        val info = YoutubeDL.getInstance().getInfo(YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
        })
        VideoItem(
            url = url,
            title = info.title ?: "Vidéo sans titre",
            uploader = info.uploader,
            durationSec = info.duration,
            thumbnail = info.thumbnail,
        )
    }

    /** Recherche YouTube native via yt-dlp (aucune clé API nécessaire). */
    suspend fun search(context: Context, query: String, limit: Int = 20): List<VideoItem> =
        withContext(Dispatchers.IO) {
            ensureReady(context)
            val request = YoutubeDLRequest("ytsearch$limit:$query").apply {
                addOption("--dump-single-json")
                addOption("--flat-playlist")
                addOption("--no-warnings")
            }
            val out = YoutubeDL.getInstance().execute(request, null, null).out
            val start = out.indexOf('{')
            if (start < 0) return@withContext emptyList()
            val entries = JSONObject(out.substring(start)).optJSONArray("entries")
                ?: return@withContext emptyList()
            (0 until entries.length()).mapNotNull { i ->
                val e = entries.optJSONObject(i) ?: return@mapNotNull null
                val id = e.optString("id")
                if (id.isEmpty()) return@mapNotNull null
                VideoItem(
                    url = e.optString("url").ifEmpty { "https://www.youtube.com/watch?v=$id" },
                    title = e.optString("title").ifEmpty { "Vidéo" },
                    uploader = e.optString("uploader").ifEmpty { e.optString("channel") },
                    durationSec = e.optDouble("duration", 0.0).toInt(),
                    thumbnail = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                )
            }
        }

    /**
     * URL(s) de flux direct pour la lecture intégrée (une URL combinée, ou
     * deux URLs vidéo+audio que le lecteur fusionne à la volée).
     */
    suspend fun streamUrls(context: Context, url: String): List<String> =
        withContext(Dispatchers.IO) {
            ensureReady(context)
            val request = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                addOption("--no-warnings")
                addOption("-f", "bv*[height<=1080][ext=mp4]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b")
                addOption("-g")
            }
            YoutubeDL.getInstance().execute(request, null, null).out
                .lineSequence().map { it.trim() }.filter { it.startsWith("http") }.toList()
        }
}
