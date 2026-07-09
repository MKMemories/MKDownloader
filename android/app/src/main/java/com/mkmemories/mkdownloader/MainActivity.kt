package com.mkmemories.mkdownloader

import android.content.ContentValues
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import coil.load
import com.google.android.material.chip.Chip
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.mkmemories.mkdownloader.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class Quality(val id: String, val label: String, val format: String, val mergeMp4: Boolean, val audioMp3: Boolean = false)

private val QUALITIES = listOf(
    Quality("max", "Qualité maximale", "bestvideo*+bestaudio/best", mergeMp4 = false),
    Quality("mp4", "Meilleur MP4", "bestvideo*[vcodec^=avc1]+bestaudio[acodec^=mp4a]/bestvideo*[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/bestvideo*+bestaudio/best", mergeMp4 = true),
    Quality("1080p", "Full HD 1080p", "bestvideo*[height<=1080]+bestaudio/best[height<=1080]/best", mergeMp4 = true),
    Quality("720p", "HD 720p", "bestvideo*[height<=720]+bestaudio/best[height<=720]/best", mergeMp4 = true),
    Quality("audio", "Audio MP3", "bestaudio/best", mergeMp4 = false, audioMp3 = true),
)

class MainActivity : AppCompatActivity() {

    private lateinit var ui: ActivityMainBinding
    private var currentUrl: String? = null
    private var selected: Quality = QUALITIES[0]
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityMainBinding.inflate(layoutInflater)
        setContentView(ui.root)

        QUALITIES.forEach { q ->
            val chip = Chip(this).apply {
                text = q.label
                isCheckable = true
                isChecked = q.id == selected.id
                setOnClickListener { selected = q }
            }
            ui.qualityGroup.addView(chip)
        }

        ui.pasteButton.setOnClickListener { pasteFromClipboard() }
        ui.analyzeButton.setOnClickListener { analyze(ui.urlInput.text?.toString().orEmpty().trim()) }
        ui.downloadButton.setOnClickListener { download() }
        ui.updateButton.setOnClickListener { updateEngine() }

        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    /** Lien reçu via « Partager → MKDownloader » depuis Facebook/YouTube/Instagram. */
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = Regex("https?://\\S+").find(text)?.value ?: return
        ui.urlInput.setText(url)
        analyze(url)
    }

    private fun pasteFromClipboard() {
        val clip = (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
            .primaryClip?.getItemAt(0)?.text?.toString()?.trim()
        if (!clip.isNullOrEmpty()) {
            ui.urlInput.setText(clip)
            analyze(clip)
        }
    }

    private fun setStatus(text: String?, indeterminate: Boolean = false, percent: Int = -1) {
        ui.statusText.text = text.orEmpty()
        ui.statusText.isVisible = !text.isNullOrEmpty()
        ui.progress.isVisible = indeterminate || percent >= 0
        ui.progress.isIndeterminate = indeterminate
        if (percent >= 0) ui.progress.setProgressCompat(percent, true)
    }

    private fun analyze(url: String) {
        if (busy) return
        if (!url.startsWith("http")) {
            toast(getString(R.string.invalid_url)); return
        }
        busy = true
        currentUrl = url
        ui.videoCard.isVisible = false
        ui.saveHint.isVisible = false
        ui.analyzeButton.isEnabled = false
        setStatus(getString(R.string.analyzing), indeterminate = true)

        lifecycleScope.launch {
            try {
                Engine.ensureReady(this@MainActivity)
                val info = withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().getInfo(YoutubeDLRequest(url).apply {
                        addOption("--no-playlist")
                    })
                }
                ui.videoTitle.text = info.title ?: getString(R.string.untitled)
                ui.videoMeta.text = listOfNotNull(
                    info.uploader,
                    info.duration.takeIf { it > 0 }?.let { formatDuration(it) },
                ).joinToString(" · ")
                if (!info.thumbnail.isNullOrEmpty()) ui.thumbnail.load(info.thumbnail)
                ui.videoCard.isVisible = true
                setStatus(null)
            } catch (e: Exception) {
                setStatus(null)
                toast(cleanError(e))
            } finally {
                busy = false
                ui.analyzeButton.isEnabled = true
            }
        }
    }

    private fun download() {
        val url = currentUrl ?: return
        if (busy) return
        busy = true
        ui.downloadButton.isEnabled = false
        setStatus(getString(R.string.starting), indeterminate = true)

        val workDir = File(cacheDir, "dl-${System.currentTimeMillis()}").apply { mkdirs() }
        val quality = selected

        lifecycleScope.launch {
            try {
                Engine.ensureReady(this@MainActivity)
                withContext(Dispatchers.IO) {
                    val request = YoutubeDLRequest(url).apply {
                        addOption("--no-playlist")
                        addOption("-f", quality.format)
                        if (quality.mergeMp4) addOption("--merge-output-format", "mp4")
                        if (quality.audioMp3) {
                            addOption("--extract-audio")
                            addOption("--audio-format", "mp3")
                        }
                        addOption("-o", "${workDir.absolutePath}/%(title).150B.%(ext)s")
                    }
                    YoutubeDL.getInstance().execute(request, url.hashCode().toString()) { progress, _, _ ->
                        runOnUiThread {
                            if (progress in 0f..100f) {
                                setStatus(getString(R.string.downloading, progress.toInt()), percent = progress.toInt())
                            } else {
                                setStatus(getString(R.string.processing), indeterminate = true)
                            }
                        }
                    }
                }
                val produced = workDir.listFiles()?.maxByOrNull { it.length() }
                    ?: throw IllegalStateException(getString(R.string.no_file))
                setStatus(getString(R.string.saving), indeterminate = true)
                val savedName = withContext(Dispatchers.IO) { exportToDownloads(produced) }
                setStatus(getString(R.string.done), percent = 100)
                ui.saveHint.text = getString(R.string.saved_to, savedName)
                ui.saveHint.isVisible = true
            } catch (e: Exception) {
                setStatus(null)
                toast(cleanError(e))
            } finally {
                workDir.deleteRecursively()
                busy = false
                ui.downloadButton.isEnabled = true
            }
        }
    }

    /** Copie le fichier produit dans le dossier Téléchargements visible de l'utilisateur. */
    private fun exportToDownloads(file: File): String {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MKDownloader")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore insert a échoué")
        contentResolver.openOutputStream(uri)!!.use { out ->
            file.inputStream().use { it.copyTo(out) }
        }
        return "Téléchargements/MKDownloader/${file.name}"
    }

    private fun updateEngine() {
        if (busy) return
        busy = true
        setStatus(getString(R.string.updating), indeterminate = true)
        lifecycleScope.launch {
            try {
                val message = Engine.updateYtDlp(this@MainActivity)
                setStatus(null)
                toast(message)
            } catch (e: Exception) {
                setStatus(null)
                toast(cleanError(e))
            } finally {
                busy = false
            }
        }
    }

    private fun formatDuration(seconds: Int): String {
        val h = seconds / 3600; val m = (seconds % 3600) / 60; val s = seconds % 60
        return if (h > 0) "%d h %02d min".format(h, m) else "%d min %02d".format(m, s)
    }

    private fun cleanError(e: Exception): String {
        val raw = e.message ?: return getString(R.string.generic_error)
        val line = raw.lineSequence().firstOrNull { it.contains("ERROR", ignoreCase = true) } ?: raw.lineSequence().first()
        return line.removePrefix("ERROR:").trim().take(300)
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
