package com.mkmemories.mkdownloader

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Gestionnaire de téléchargements : file d'attente (plusieurs jobs), traitement
 * séquentiel en arrière-plan (via DownloadService), reprise des fichiers
 * partiels (yt-dlp -c) et persistance de la file (survit à un redémarrage).
 */
object Downloads {

    enum class Status { QUEUED, RUNNING, DONE, ERROR }

    data class Job(
        val id: String,
        val item: VideoItem,
        val quality: Quality,
        val startSec: Int? = null,
        val endSec: Int? = null,
        var status: Status = Status.QUEUED,
        var percent: Int = -1,
        var error: String? = null,
    )

    private val jobsList = CopyOnWriteArrayList<Job>()
    private val counter = AtomicLong(0)

    fun jobs(): List<Job> = jobsList.toList()
    fun active(): Job? = jobsList.firstOrNull { it.status == Status.RUNNING }
        ?: jobsList.firstOrNull { it.status == Status.QUEUED }
    fun hasActive(): Boolean = jobsList.any { it.status == Status.QUEUED || it.status == Status.RUNNING }
    fun doneCount(): Int = jobsList.count { it.status == Status.DONE }
    fun totalCount(): Int = jobsList.size

    /** Observateur d'interface (rafraîchit la carte / la file). */
    var onChange: (() -> Unit)? = null
    var onHistoryChanged: (() -> Unit)? = null
    /** Le service de fond met à jour sa notification via ce hook. */
    var notifier: (() -> Unit)? = null

    @Volatile private var working = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun notifyAll() = mainHandler.post {
        onChange?.invoke()
        notifier?.invoke()
    }

    private fun newId() = "${System.currentTimeMillis()}-${counter.incrementAndGet()}"

    // ---------- API publique ----------

    /** Ajoute un téléchargement à la file. Renvoie le job créé. */
    fun start(
        context: Context,
        item: VideoItem,
        quality: Quality,
        startSec: Int? = null,
        endSec: Int? = null,
    ): Job {
        val job = Job(newId(), item, quality, startSec, endSec)
        jobsList.add(job)
        persist(context)
        DownloadService.start(context.applicationContext)
        kick(context.applicationContext)
        notifyAll()
        return job
    }

    /** Ajoute tout un lot (playlist, chaîne…) à la file. */
    fun startBatch(context: Context, items: List<VideoItem>, quality: Quality): Boolean {
        if (items.isEmpty()) return false
        items.forEach { jobsList.add(Job(newId(), it, quality)) }
        persist(context)
        DownloadService.start(context.applicationContext)
        kick(context.applicationContext)
        notifyAll()
        return true
    }

    fun retry(context: Context, id: String) {
        jobsList.find { it.id == id && it.status == Status.ERROR }?.let {
            it.status = Status.QUEUED; it.error = null; it.percent = -1
            persist(context)
            DownloadService.start(context.applicationContext)
            kick(context.applicationContext)
            notifyAll()
        }
    }

    fun remove(context: Context, id: String) {
        val job = jobsList.find { it.id == id } ?: return
        if (job.status == Status.RUNNING) return
        jobsList.remove(job)
        workDir(context, job).deleteRecursively()
        persist(context)
        notifyAll()
    }

    fun clearFinished(context: Context) {
        jobsList.filter { it.status == Status.DONE || it.status == Status.ERROR }.forEach {
            workDir(context, it).deleteRecursively()
        }
        jobsList.removeAll { it.status == Status.DONE || it.status == Status.ERROR }
        persist(context)
        notifyAll()
    }

    // ---------- Boucle de traitement ----------

    private fun kick(app: Context) {
        if (working) return
        working = true
        scope.launch {
            try {
                while (true) {
                    val job = jobsList.firstOrNull { it.status == Status.QUEUED } ?: break
                    job.status = Status.RUNNING; job.percent = -1
                    persist(app); notifyAll()
                    try {
                        downloadOne(app, job) { p -> job.percent = p; notifyAll() }
                        job.status = Status.DONE; job.percent = 100
                    } catch (e: Exception) {
                        job.status = Status.ERROR; job.error = cleanError(e)
                    }
                    persist(app); notifyAll()
                }
            } finally {
                working = false
                // Un job a pu arriver pendant la fermeture : on relance sinon on stoppe.
                if (jobsList.any { it.status == Status.QUEUED }) kick(app)
                else mainHandler.post { DownloadService.stop(app) }
            }
        }
    }

    private fun workDir(context: Context, job: Job) =
        File(context.applicationContext.cacheDir, "dl/${job.id}")

    private suspend fun downloadOne(app: Context, job: Job, onProgress: (Int) -> Unit) {
        Engine.ensureReady(app)
        val item = job.item
        val quality = job.quality
        val workDir = workDir(app, job)
        var success = false
        try {
            workDir.mkdirs()
            val request = YoutubeDLRequest(item.url).apply {
                addOption("--no-playlist")
                addOption("--extractor-args", Engine.YT_ARGS)
                // Comptes : TF1/M6 par login, YouTube/Instagram/TikTok par cookies.
                // Évite les échecs « connexion requise » au téléchargement.
                Settings.credsForUrl(app, item.url)?.let {
                    addOption("--username", it.user)
                    addOption("--password", it.pass)
                }
                Settings.cookiesForUrl(app, item.url)?.let { addOption("--cookies", it.absolutePath) }
                addOption("-f", quality.format)
                addOption("--continue")        // reprend un fichier partiel
                if (quality.mergeMp4) addOption("--merge-output-format", "mp4")
                if (quality.audioMp3) {
                    addOption("--extract-audio")
                    addOption("--audio-format", "mp3")
                }
                if (job.startSec != null && job.endSec != null && job.endSec > job.startSec) {
                    addOption("--download-sections", "*${job.startSec}-${job.endSec}")
                    addOption("--force-keyframes-at-cuts")
                }
                addOption("-o", "${workDir.absolutePath}/%(title).150B.%(ext)s")
            }
            YoutubeDL.getInstance().execute(request, job.id) { p, _, _ ->
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
            success = true
        } finally {
            // On garde le dossier en cas d'échec (pour reprendre au réessai).
            if (success) workDir.deleteRecursively()
        }
    }

    /** Copie le fichier dans Téléchargements/MKDownloader[/Audio] ; renvoie l'uri. */
    private fun exportToDownloads(context: Context, file: File, audio: Boolean): String {
        val subDir = if (audio) "MKDownloader/Audio" else "MKDownloader"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) exportViaMediaStore(context, file, subDir)
        else exportLegacy(context, file, subDir)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun exportViaMediaStore(context: Context, file: File, subDir: String): String {
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

    /** Android 9 et antérieurs (ex. box Mi Box) : dossier privé de l'app, sans permission. */
    private fun exportLegacy(context: Context, file: File, subDir: String): String {
        val base = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), subDir)
        base.mkdirs()
        val dest = File(base, file.name)
        file.inputStream().use { inp -> dest.outputStream().use { inp.copyTo(it) } }
        return Uri.fromFile(dest).toString()
    }

    // ---------- Persistance de la file ----------

    private const val PREFS = "mkdl_dlqueue"
    private const val KEY = "jobs"

    private fun persist(context: Context) {
        val arr = JSONArray()
        jobsList.forEach { j ->
            if (j.status == Status.DONE) return@forEach   // les terminés vivent dans l'Historique
            arr.put(
                JSONObject().apply {
                    put("id", j.id)
                    put("item", j.item.toJson())
                    put("q", j.quality.id)
                    put("start", j.startSec ?: JSONObject.NULL)
                    put("end", j.endSec ?: JSONObject.NULL)
                    put("status", j.status.name)
                    put("error", j.error ?: JSONObject.NULL)
                }
            )
        }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    /**
     * Recharge la file après un redémarrage. Les jobs qui tournaient repassent
     * « en attente » pour reprendre. Renvoie true s'il reste du travail.
     */
    fun restore(context: Context): Boolean {
        if (jobsList.isNotEmpty()) return hasActive()
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return false
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val quality = QUALITIES.find { it.id == o.optString("q") } ?: continue
                var status = runCatching { Status.valueOf(o.optString("status")) }.getOrDefault(Status.QUEUED)
                if (status == Status.RUNNING) status = Status.QUEUED
                jobsList.add(
                    Job(
                        id = o.optString("id").ifEmpty { newId() },
                        item = VideoItem.fromJson(o.getJSONObject("item")),
                        quality = quality,
                        startSec = if (o.isNull("start")) null else o.optInt("start"),
                        endSec = if (o.isNull("end")) null else o.optInt("end"),
                        status = status,
                        error = if (o.isNull("error")) null else o.optStringOrNull("error"),
                    )
                )
            }
        }
        return hasActive()
    }

    /** Relance le traitement de la file restaurée (appelé quand l'app est au premier plan). */
    fun resumeIfNeeded(context: Context) {
        if (hasActive()) {
            DownloadService.start(context.applicationContext)
            kick(context.applicationContext)
            notifyAll()
        }
    }
}
