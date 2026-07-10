package com.mkmemories.mkdownloader

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** Gestionnaire de téléchargement (un job à la fois : vidéo unique ou lot de morceaux). */
object Downloads {

    data class State(
        val running: Boolean = false,
        val label: String = "",
        val percent: Int = -1,
        val message: String? = null,
        val error: String? = null,
    )

    @Volatile var state: State = State()
        private set
    var onChange: ((State) -> Unit)? = null
    var onHistoryChanged: (() -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun update(next: State) {
        state = next
        mainHandler.post { onChange?.invoke(next) }
    }

    fun start(
        context: Context,
        item: VideoItem,
        quality: Quality,
        startSec: Int? = null,
        endSec: Int? = null,
    ): Boolean {
        if (state.running) return false
        val app = context.applicationContext
        val label = if (startSec != null) "✂ ${item.title}" else item.title
        update(State(running = true, label = label))
        scope.launch {
            try {
                downloadOne(app, item, quality, startSec, endSec) { p -> update(state.copy(percent = p)) }
                update(State(running = false, label = label, percent = 100, message = "Terminé ✔"))
            } catch (e: Exception) {
                update(State(running = false, label = label, error = cleanError(e)))
            }
        }
        return true
    }

    /** Télécharge en lot (ex. tous les morceaux d'une playlist), l'un après l'autre. */
    fun startBatch(context: Context, items: List<VideoItem>, quality: Quality): Boolean {
        if (state.running || items.isEmpty()) return false
        val app = context.applicationContext
        update(State(running = true, label = "0/${items.size}"))
        scope.launch {
            var ok = 0
            items.forEachIndexed { index, item ->
                update(state.copy(label = "${index + 1}/${items.size} · ${item.title}", percent = -1))
                runCatching {
                    downloadOne(app, item, quality) { p ->
                        update(state.copy(percent = p))
                    }
                }.onSuccess { ok++ }
            }
            update(State(running = false, label = "Playlist", percent = 100, message = "$ok/${items.size} morceaux enregistrés"))
        }
        return true
    }

    private suspend fun downloadOne(
        app: Context,
        item: VideoItem,
        quality: Quality,
        startSec: Int? = null,
        endSec: Int? = null,
        onProgress: (Int) -> Unit,
    ) {
        Engine.ensureReady(app)
        val workDir = File(app.cacheDir, "dl-${System.currentTimeMillis()}")
        try {
            workDir.mkdirs()
            val request = YoutubeDLRequest(item.url).apply {
                addOption("--no-playlist")
                addOption("--extractor-args", Engine.YT_ARGS)
                addOption("-f", quality.format)
                if (quality.mergeMp4) addOption("--merge-output-format", "mp4")
                if (quality.audioMp3) {
                    addOption("--extract-audio")
                    addOption("--audio-format", "mp3")
                }
                // Téléchargement d'un extrait (découpe aux points demandés).
                if (startSec != null && endSec != null && endSec > startSec) {
                    addOption("--download-sections", "*$startSec-$endSec")
                    addOption("--force-keyframes-at-cuts")
                }
                addOption("-o", "${workDir.absolutePath}/%(title).150B.%(ext)s")
            }
            YoutubeDL.getInstance().execute(request, item.url.hashCode().toString()) { p, _, _ ->
                onProgress(if (p in 0f..100f) p.toInt() else -1)
            }
            val produced = workDir.listFiles()?.maxByOrNull { it.length() }
                ?: error("Le téléchargement n'a produit aucun fichier.")
            val uri = exportToDownloads(app, produced, quality.audioMp3)
            History.add(
                app,
                HistoryEntry(
                    id = System.currentTimeMillis().toString() + "-" + item.url.hashCode(),
                    title = item.title,
                    platform = platformOf(item.url),
                    fileName = produced.name,
                    uri = uri,
                    timestamp = System.currentTimeMillis(),
                    audio = quality.audioMp3,
                ),
            )
            mainHandler.post { onHistoryChanged?.invoke() }
        } finally {
            workDir.deleteRecursively()
        }
    }

    /** Copie le fichier dans Téléchargements/MKDownloader[/Audio] ; renvoie l'uri. */
    private fun exportToDownloads(context: Context, file: File, audio: Boolean): String {
        val subDir = if (audio) "MKDownloader/Audio" else "MKDownloader"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + subDir)
        }
        val uri: Uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Impossible d'écrire dans Téléchargements")
        context.contentResolver.openOutputStream(uri)!!.use { out ->
            file.inputStream().use { it.copyTo(out) }
        }
        return uri.toString()
    }
}
