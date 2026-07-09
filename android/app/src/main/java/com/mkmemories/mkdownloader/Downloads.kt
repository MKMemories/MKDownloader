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

/** Gestionnaire de téléchargement (un à la fois), indépendant des écrans. */
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
    /** Notifie l'écran Historique qu'une entrée a été ajoutée. */
    var onHistoryChanged: (() -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun update(next: State) {
        state = next
        mainHandler.post { onChange?.invoke(next) }
    }

    fun start(context: Context, item: VideoItem, quality: Quality): Boolean {
        if (state.running) return false
        val appContext = context.applicationContext
        update(State(running = true, label = item.title))
        scope.launch {
            val workDir = File(appContext.cacheDir, "dl-${System.currentTimeMillis()}")
            try {
                Engine.ensureReady(appContext)
                workDir.mkdirs()
                val request = YoutubeDLRequest(item.url).apply {
                    addOption("--no-playlist")
                    addOption("-f", quality.format)
                    if (quality.mergeMp4) addOption("--merge-output-format", "mp4")
                    if (quality.audioMp3) {
                        addOption("--extract-audio")
                        addOption("--audio-format", "mp3")
                    }
                    addOption("-o", "${workDir.absolutePath}/%(title).150B.%(ext)s")
                }
                YoutubeDL.getInstance().execute(request, item.url.hashCode().toString()) { p, _, _ ->
                    update(state.copy(percent = if (p in 0f..100f) p.toInt() else -1))
                }
                val produced = workDir.listFiles()?.maxByOrNull { it.length() }
                    ?: error("Le téléchargement n'a produit aucun fichier.")
                val (savedName, uri) = exportToDownloads(appContext, produced, quality.audioMp3)
                History.add(
                    appContext,
                    HistoryEntry(
                        id = System.currentTimeMillis().toString(),
                        title = item.title,
                        platform = platformOf(item.url),
                        fileName = produced.name,
                        uri = uri,
                        timestamp = System.currentTimeMillis(),
                        audio = quality.audioMp3,
                    ),
                )
                mainHandler.post { onHistoryChanged?.invoke() }
                update(State(running = false, label = item.title, percent = 100, message = "Enregistré dans $savedName"))
            } catch (e: Exception) {
                update(State(running = false, label = item.title, error = cleanError(e)))
            } finally {
                workDir.deleteRecursively()
            }
        }
        return true
    }

    /** Copie le fichier dans Téléchargements/MKDownloader ; renvoie (chemin lisible, uri). */
    private fun exportToDownloads(context: Context, file: File, audio: Boolean): Pair<String, String> {
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
        return "Téléchargements/$subDir/${file.name}" to uri.toString()
    }
}
