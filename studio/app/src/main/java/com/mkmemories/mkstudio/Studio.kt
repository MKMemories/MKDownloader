package com.mkmemories.mkstudio

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.random.Random

/**
 * Moteur de MK Studio : téléchargement du modèle, génération (texte→image et
 * retouche img2img), sauvegarde dans la galerie. Une seule opération à la fois,
 * sur un thread dédié (le natif est bloquant).
 */
object Studio {

    enum class Phase { IDLE, DOWNLOADING, LOADING, GENERATING }

    @Volatile var phase: Phase = Phase.IDLE; private set
    @Volatile var percent: Int = -1; private set          // -1 = indéterminé
    @Volatile var statusText: String = ""; private set
    @Volatile var opStartedAt: Long = 0L; private set     // pour le chrono « écoulé »
    @Volatile var lastError: String? = null; private set
    @Volatile var lastImage: Bitmap? = null; private set
    @Volatile var lastImageUri: Uri? = null; private set

    /** Observateurs d'interface + service de premier plan. */
    var onChange: (() -> Unit)? = null
    var notifier: (() -> Unit)? = null

    @Volatile private var cancelDownload = false

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mkstudio-worker").apply { priority = Thread.NORM_PRIORITY }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + worker)
    private val main = Handler(Looper.getMainLooper())

    val busy: Boolean get() = phase != Phase.IDLE

    private fun update(p: Phase, pct: Int = -1, text: String = statusText) {
        if (phase == Phase.IDLE && p != Phase.IDLE) opStartedAt = System.currentTimeMillis()
        phase = p; percent = pct; statusText = text
        main.post { onChange?.invoke(); notifier?.invoke() }
    }

    /** « 1 min 05 » / « 42 s » */
    fun fmtDuration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 60) "%d min %02d".format(s / 60, s % 60) else "$s s"
    }

    // ---------- Téléchargement du modèle ----------

    fun downloadModel(context: Context, model: SdModel) {
        if (busy) return
        val app = context.applicationContext
        cancelDownload = false
        lastError = null
        StudioService.start(app)
        update(Phase.DOWNLOADING, 0, app.getString(R.string.st_downloading, model.label))
        scope.launch {
            val dest = Models.fileOf(app, model)
            val part = File(dest.absolutePath + ".part")
            var ok = false
            var error: String? = null
            // Sources : URLs fixes + fichiers résolus via l'API HF, ordonnés par
            // préférence de quantification (tous dépôts confondus).
            val resolved = model.repos.mapNotNull { resolveGgufUrl(it) }
                .sortedWith(compareBy({ it.pref }, { it.sizeMb }))
                .map { it.url }
            val candidates = model.urls + resolved
            if (candidates.isEmpty()) error = "aucune source trouvée"
            for (url in candidates) {
                try {
                    fetchResumable(url, part) { done, total ->
                        val pct = if (total > 0) ((done * 100) / total).toInt() else -1
                        update(Phase.DOWNLOADING, pct, app.getString(R.string.st_downloading, model.label))
                        !cancelDownload
                    }
                    if (!cancelDownload && part.length() > model.minBytes) {
                        part.renameTo(dest)
                        ok = true
                        break
                    }
                    if (cancelDownload) break
                } catch (e: Exception) {
                    error = e.message
                    // essaie le miroir suivant, en repartant de zéro
                    part.delete()
                }
            }
            if (cancelDownload) part.delete()
            lastError = when {
                ok || cancelDownload -> null
                else -> app.getString(R.string.st_dl_failed, error ?: "?")
            }
            Logs.add(
                when {
                    ok -> "téléchargement ${model.id} OK (${dest.length() / (1024 * 1024)} Mo)"
                    cancelDownload -> "téléchargement ${model.id} annulé"
                    else -> "téléchargement ${model.id} ÉCHEC: $error"
                },
            )
            update(Phase.IDLE)
        }
    }

    fun cancelCurrent() {
        cancelDownload = true
        NativeSD.cancel()
    }

    /** URL résolue + rang de préférence (plus petit = meilleur pour nous). */
    private data class ResolvedGguf(val url: String, val pref: Int, val sizeMb: Long)

    // Ordre de préférence CPU : q4_0 (accéléré par REPACK) puis k-quants, etc.
    private val QUANT_PREFS = listOf("q4_0", "q4_k", "q5_k", "q5_0", "q5_1", "q8_0", "f16")

    // Bannies : quantifications 2-3 bits et i-quants → qualité massacrée et
    // décodage lent sur CPU (cause des « résultats trop moches » v1.4 : iq2_xs).
    private val QUANT_BAD = Regex("(?i)iq[0-9]|q2_|q3_|[_-]q[23][._-]")

    /**
     * Trouve le meilleur fichier .gguf d'un dépôt HuggingFace (API /tree), par
     * ordre de préférence de quantification — jamais par « le plus petit ».
     */
    private fun resolveGgufUrl(repo: String): ResolvedGguf? = runCatching {
        val conn = URL("https://huggingface.co/api/models/$repo/tree/main")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        val body = conn.inputStream.use { it.readBytes().decodeToString() }
        val arr = org.json.JSONArray(body)
        val ggufs = mutableListOf<Pair<String, Long>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val path = o.optString("path")
            if (path.endsWith(".gguf", true) && !QUANT_BAD.containsMatchIn(path)) {
                ggufs += path to o.optLong("size", Long.MAX_VALUE)
            }
        }
        var best: ResolvedGguf? = null
        for ((rank, q) in QUANT_PREFS.withIndex()) {
            val m = ggufs.filter { it.first.contains(q, ignoreCase = true) }
                .minByOrNull { it.second }
            if (m != null) {
                best = ResolvedGguf(
                    "https://huggingface.co/$repo/resolve/main/${m.first}", rank, m.second / (1024 * 1024),
                )
                Logs.add("résolu $repo → ${m.first} (${best.sizeMb} Mo, préf. $q)")
                break
            }
        }
        if (best == null) Logs.add("résolution $repo: aucun fichier de qualité acceptable")
        best
    }.onFailure { Logs.add("résolution $repo impossible: ${it.message}") }.getOrNull()

    private fun fetchResumable(url: String, part: File, progress: (Long, Long) -> Boolean) {
        var offset = if (part.exists()) part.length() else 0L
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        conn.instanceFollowRedirects = true
        if (offset > 0) conn.setRequestProperty("Range", "bytes=$offset-")
        conn.connect()
        val code = conn.responseCode
        if (code == 200) offset = 0L    // le serveur ignore Range : on repart de zéro
        else if (code != 206) throw IllegalStateException("HTTP $code")
        val total = offset + conn.contentLengthLong.coerceAtLeast(0)
        conn.inputStream.use { input ->
            java.io.RandomAccessFile(part, "rw").use { out ->
                out.seek(offset)
                val buf = ByteArray(256 * 1024)
                var done = offset
                var lastNotify = 0L
                while (true) {
                    val r = input.read(buf)
                    if (r < 0) break
                    out.write(buf, 0, r)
                    done += r
                    if (done - lastNotify > 4L * 1024 * 1024) {
                        lastNotify = done
                        if (!progress(done, total)) return
                    }
                }
                progress(done, total)
            }
        }
    }

    // ---------- Génération ----------

    /**
     * Lance une génération. [init] non nul = retouche de cette photo.
     * [turbo] : utilise l'accélérateur LCM (4-6 étapes, ~4× plus rapide).
     * Le résultat est sauvegardé automatiquement dans la galerie (Pictures/MK Studio).
     */
    fun generate(
        context: Context,
        prompt: String,
        negative: String,
        width: Int,
        height: Int,
        steps: Int,
        init: Bitmap? = null,
        strength: Float = 0.55f,
        turbo: Boolean = false,
    ) {
        if (busy) return
        val app = context.applicationContext
        val model = Models.installed(app) ?: return
        // Modèle « LCM intégré » : Turbo natif sans LoRA — toujours en
        // échantillonnage LCM (peu d'étapes, guidance basse), sinon résultats dégradés.
        val builtIn = model.lcmBuiltIn
        val useTurbo = builtIn || (turbo && Models.turboReady(app))
        val runSteps = if (builtIn) steps.coerceAtMost(12) else steps
        lastError = null
        StudioService.start(app)
        update(Phase.LOADING, -1, app.getString(R.string.st_loading_model))
        scope.launch {
            try {
                runCatching {
                    val am = app.getSystemService(android.app.ActivityManager::class.java)
                    val mi = android.app.ActivityManager.MemoryInfo()
                    am.getMemoryInfo(mi)
                    Logs.add("mémoire libre ${mi.availMem / 1048576} Mo / ${mi.totalMem / 1048576} Mo")
                }
                // big.LITTLE : sur 8 cœurs (1 gros + 4 moyens + 3 petits), un thread
                // de trop tombe sur un petit cœur qui freine tout le monde → 5.
                val cores = Runtime.getRuntime().availableProcessors()
                val threads = if (cores >= 8) 5 else (cores - 2).coerceIn(3, 6)
                val taesd = Models.taesdPath(app)
                val tLoad = System.currentTimeMillis()
                if (!NativeSD.loadModel(Models.fileOf(app, model).absolutePath, threads, taesd)) {
                    Logs.drainNative()
                    throw IllegalStateException(app.getString(R.string.st_model_load_failed))
                }
                val loadMs = System.currentTimeMillis() - tLoad
                if (loadMs > 500) Logs.add("modèle ${model.id} chargé en ${fmtDuration(loadMs)} (threads=$threads, taesd=${taesd != null})")

                // Suivi : seules les étapes dont le total == steps sont
                // l'échantillonnage ; le reste (préparation/décodage interne)
                // est étiqueté « Finalisation » — fini la boîte noire.
                var lastStepTs = 0L
                var avgStepMs = 0.0
                var lastLoggedStep = 0
                NativeSD.progressListener = { step, total ->
                    if (total == runSteps) {
                        val now = System.currentTimeMillis()
                        if (lastStepTs > 0 && step > lastLoggedStep) {
                            val d = (now - lastStepTs).toDouble() / (step - lastLoggedStep)
                            avgStepMs = if (avgStepMs == 0.0) d else 0.6 * avgStepMs + 0.4 * d
                            Logs.add("étape $step/$total en ${fmtDuration(d.toLong())}")
                        }
                        lastStepTs = now
                        lastLoggedStep = step
                        val pct = (step * 100 / total).coerceIn(0, 100)
                        val eta = if (avgStepMs > 0 && step < total) {
                            app.getString(R.string.st_eta, fmtDuration(((total - step) * avgStepMs).toLong()))
                        } else ""
                        update(Phase.GENERATING, pct, app.getString(R.string.st_generating, step, total) + eta)
                    } else {
                        // Phase interne (VAE, préparation…) : affichée pour info.
                        val pct = if (total > 0) (step * 100 / total).coerceIn(0, 100) else -1
                        update(Phase.GENERATING, pct, app.getString(R.string.st_finalizing))
                    }
                }
                update(Phase.GENERATING, 0, app.getString(R.string.st_generating, 0, runSteps))
                val initBytes = init?.let { toRgbBytes(it, width, height) }
                val seed = abs(Random.nextLong() % 2_000_000_000L)
                // LCM : peu d'étapes et guidance très basse (1.5), sinon CFG 7.
                val cfg = if (useTurbo) 1.5f else 7.0f
                // LCM intégré : pas de LoRA à charger (c'était le gros frein).
                val loraPath = if (useTurbo && !builtIn) {
                    Models.fileOf(app, Models.LCM_LORA).absolutePath
                } else null
                val tGen = System.currentTimeMillis()
                val rgb = NativeSD.generate(
                    prompt, negative, width, height, runSteps, cfg, seed, initBytes, strength,
                    loraPath, 1.0f, useTurbo,
                )
                val genMs = System.currentTimeMillis() - tGen
                Logs.add(
                    "génération ${width}x$height ${runSteps} étapes turbo=$useTurbo lcmIntégré=$builtIn " +
                        "modèle=${model.id} taesd=${taesd != null} → " +
                        (if (rgb != null) "OK en ${fmtDuration(genMs)}" else "échec/annulée après ${fmtDuration(genMs)}"),
                )
                if (rgb != null) {
                    val bmp = fromRgbBytes(rgb, width, height)
                    lastImage = bmp
                    lastImageUri = saveToGallery(app, bmp)
                } else {
                    lastError = app.getString(R.string.st_gen_failed)
                }
            } catch (e: Exception) {
                lastError = e.message ?: app.getString(R.string.st_gen_failed)
                Logs.add("ERREUR: ${e.message}")
            } finally {
                Logs.drainNative()
                NativeSD.progressListener = null
                update(Phase.IDLE)
            }
        }
    }

    /** Redimensionne (recadrage centré) puis extrait les pixels RGB. */
    private fun toRgbBytes(src: Bitmap, w: Int, h: Int): ByteArray {
        val scale = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val sw = (src.width * scale).toInt().coerceAtLeast(w)
        val sh = (src.height * scale).toInt().coerceAtLeast(h)
        val scaled = Bitmap.createScaledBitmap(src, sw, sh, true)
        val x = (sw - w) / 2
        val y = (sh - h) / 2
        val crop = Bitmap.createBitmap(scaled, x, y, w, h)
        val pixels = IntArray(w * h)
        crop.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = ByteArray(w * h * 3)
        for (i in pixels.indices) {
            val c = pixels[i]
            out[i * 3] = Color.red(c).toByte()
            out[i * 3 + 1] = Color.green(c).toByte()
            out[i * 3 + 2] = Color.blue(c).toByte()
        }
        return out
    }

    private fun fromRgbBytes(rgb: ByteArray, w: Int, h: Int): Bitmap {
        val pixels = IntArray(w * h)
        for (i in pixels.indices) {
            val r = rgb[i * 3].toInt() and 0xFF
            val g = rgb[i * 3 + 1].toInt() and 0xFF
            val b = rgb[i * 3 + 2].toInt() and 0xFF
            pixels[i] = Color.rgb(r, g, b)
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun saveToGallery(context: Context, bmp: Bitmap): Uri? {
        val name = "mkstudio-${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/MK Studio")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    /** Les créations enregistrées (les plus récentes d'abord). */
    fun galleryImages(context: Context): List<Uri> {
        val out = mutableListOf<Uri>()
        val projection = arrayOf(MediaStore.Images.Media._ID)
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("Pictures/MK Studio%"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (c.moveToNext()) {
                out += Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(idCol).toString())
            }
        }
        return out
    }
}
