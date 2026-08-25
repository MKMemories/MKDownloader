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
        phase = p; percent = pct; statusText = text
        main.post { onChange?.invoke(); notifier?.invoke() }
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
            for (url in model.urls) {
                try {
                    fetchResumable(url, part) { done, total ->
                        val pct = if (total > 0) ((done * 100) / total).toInt() else -1
                        update(Phase.DOWNLOADING, pct, app.getString(R.string.st_downloading, model.label))
                        !cancelDownload
                    }
                    if (!cancelDownload && part.length() > 200L * 1024 * 1024) {
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
            update(Phase.IDLE)
        }
    }

    fun cancelCurrent() {
        cancelDownload = true
        NativeSD.cancel()
    }

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
    ) {
        if (busy) return
        val app = context.applicationContext
        val model = Models.installed(app) ?: return
        lastError = null
        StudioService.start(app)
        update(Phase.LOADING, -1, app.getString(R.string.st_loading_model))
        scope.launch {
            try {
                val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
                if (!NativeSD.loadModel(Models.fileOf(app, model).absolutePath, threads)) {
                    throw IllegalStateException(app.getString(R.string.st_model_load_failed))
                }
                NativeSD.progressListener = { step, total ->
                    val pct = if (total > 0) (step * 100 / total) else -1
                    update(Phase.GENERATING, pct, app.getString(R.string.st_generating, step, total))
                }
                update(Phase.GENERATING, 0, app.getString(R.string.st_generating, 0, steps))
                val initBytes = init?.let { toRgbBytes(it, width, height) }
                val seed = abs(Random.nextLong() % 2_000_000_000L)
                val rgb = NativeSD.generate(
                    prompt, negative, width, height, steps, 7.0f, seed, initBytes, strength,
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
            } finally {
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
