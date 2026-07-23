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
import kotlinx.coroutines.coroutineScope
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
        val recordSeconds: Int? = null,   // enregistrement d'un direct : durée d'une tranche
        var status: Status = Status.QUEUED,
        var percent: Int = -1,
        var error: String? = null,
    ) {
        /** Vrai si l'utilisateur a annulé : on ne la repasse pas en ERROR. */
        @Volatile var canceled: Boolean = false
    }

    private val jobsList = CopyOnWriteArrayList<Job>()
    private val counter = AtomicLong(0)

    // En-têtes HTTP capturés par le navigateur intégré (Referer / Cookie / UA),
    // par URL de flux. Nécessaires pour télécharger certains flux protégés par
    // referer/session. En mémoire (pas besoin de survivre à un redémarrage).
    private val extraHeaders = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()

    /** Enregistre les en-têtes à utiliser pour télécharger un flux capturé. */
    fun registerHeaders(url: String, headers: Map<String, String>) {
        if (headers.isNotEmpty()) extraHeaders[url] = headers
    }

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

    /**
     * Enregistre un flux DIRECT/live découpé en tranches de 5 min. On empile N jobs
     * séquentiels : chacun enregistre 300 s du direct à partir de son démarrage → des
     * tranches consécutives (léger trou de reconnexion entre deux). Renvoie N.
     */
    /**
     * @param totalMinutes durée totale à enregistrer.
     * @param chunkMinutes taille d'une tranche ; 0 ou ≥ total ⇒ UN SEUL fichier
     *        continu (sans découpe), pour les longs formats.
     * Renvoie le nombre de fichiers créés.
     */
    fun recordLive(
        context: Context,
        item: VideoItem,
        headers: Map<String, String>,
        totalMinutes: Int,
        chunkMinutes: Int,
    ): Int {
        registerHeaders(item.url, headers)
        val total = totalMinutes.coerceAtLeast(1)
        val chunk = if (chunkMinutes <= 0) total else minOf(chunkMinutes, total)
        val n = ((total + chunk - 1) / chunk).coerceAtLeast(1)
        val secs = (chunk * 60).coerceAtLeast(1)
        val q = Quality("record", "Direct", "best/bv*+ba/b", mergeMp4 = true)
        for (i in 1..n) {
            val label = if (n > 1) "${item.title} — partie $i" else item.title
            jobsList.add(Job(newId(), item.copy(title = label), q, recordSeconds = secs))
        }
        persist(context)
        DownloadService.start(context.applicationContext)
        kick(context.applicationContext)
        notifyAll()
        return n
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

    /**
     * Annule une tâche, EN COURS ou en attente : le process yt-dlp est tué, la
     * tâche est retirée de la file et ses fichiers partiels effacés.
     */
    fun cancel(context: Context, id: String) {
        val job = jobsList.find { it.id == id } ?: return
        job.canceled = true
        if (job.status == Status.RUNNING) {
            runCatching { YoutubeDL.getInstance().destroyProcessById(job.id) }
        }
        jobsList.remove(job)
        workDir(context, job).deleteRecursively()
        persist(context)
        notifyAll()
    }

    /** Annule toutes les tâches non terminées. */
    fun cancelAll(context: Context) {
        jobsList.filter { it.status == Status.RUNNING || it.status == Status.QUEUED }
            .forEach { cancel(context, it.id) }
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

    /** Réclame atomiquement la prochaine tâche en attente et la passe EN COURS. */
    private fun claimNext(app: Context): Job? {
        val job = synchronized(jobsList) {
            jobsList.firstOrNull { it.status == Status.QUEUED }?.also {
                it.status = Status.RUNNING; it.percent = -1
            }
        } ?: return null
        persist(app); notifyAll()
        return job
    }

    private fun kick(app: Context) {
        if (working) return
        working = true
        scope.launch {
            try {
                // Pool de N workers → téléchargements PARALLÈLES.
                coroutineScope {
                    repeat(MAX_PARALLEL) {
                        launch {
                            while (true) {
                                val job = claimNext(app) ?: break
                                try {
                                    downloadOne(app, job) { p -> job.percent = p; notifyAll() }
                                    if (!job.canceled) { job.status = Status.DONE; job.percent = 100 }
                                } catch (e: Exception) {
                                    if (!job.canceled) { job.status = Status.ERROR; job.error = cleanError(e) }
                                }
                                if (!job.canceled) { persist(app); notifyAll() }
                            }
                        }
                    }
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
            val recording = (job.recordSeconds ?: 0) > 0
            val sectioned = !recording && job.startSec != null && job.endSec != null && job.endSec > job.startSec
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
                // En-têtes capturés par le navigateur intégré (Referer / Cookie / UA).
                extraHeaders[item.url]?.forEach { (k, v) -> addOption("--add-header", "$k: $v") }
                addOption("-f", quality.format)
                addOption("--continue")        // reprend un fichier partiel

                // VITESSE : aria2c en téléchargeur externe (16 connexions parallèles)
                // pour les téléchargements complets. Les extraits (sections) restent
                // sur le téléchargeur natif ; l'enregistrement d'un direct passe par
                // ffmpeg avec une durée limite (une tranche).
                when {
                    recording -> {
                        addOption("--downloader", "ffmpeg")
                        addOption("--downloader-args", "ffmpeg_o:-t ${job.recordSeconds}")
                    }
                    sectioned -> addOption("--concurrent-fragments", "8")
                    else -> {
                        addOption("--downloader", "libaria2c.so")
                        addOption("--downloader", "m3u8:native")  // HLS : plus fiable en natif
                        addOption("--downloader-args", "aria2c:-x 16 -s 16 -k 1M")
                        addOption("--concurrent-fragments", "8")  // segments restés natifs
                    }
                }

                if (quality.mergeMp4) addOption("--merge-output-format", "mp4")
                if (quality.audioMp3) {
                    addOption("--extract-audio")
                    addOption("--audio-format", quality.audioFormat)
                    addOption("--audio-quality", "0")   // meilleure qualité (VBR max)
                }

                // FICHIERS « premium » : pochette, métadonnées et chapitres incrustés.
                // Ignoré pour l'enregistrement d'un direct (post-traitement fragile
                // sur un flux live coupé). Pochette : conteneurs sûrs uniquement.
                if (!recording) {
                    if (quality.mergeMp4 || quality.audioMp3) addOption("--embed-thumbnail")
                    addOption("--embed-metadata")
                    if (!quality.audioMp3) addOption("--embed-chapters")
                }

                if (sectioned) {
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
    private const val MAX_PARALLEL = 2   // téléchargements simultanés

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
