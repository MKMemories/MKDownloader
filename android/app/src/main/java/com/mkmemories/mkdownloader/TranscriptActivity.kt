package com.mkmemories.mkdownloader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mkmemories.mkdownloader.databinding.ActivityTranscriptBinding
import kotlinx.coroutines.launch

/**
 * Transcription (sous-titres YouTube) d'une vidéo : affichage horodaté,
 * copie et export .txt (titre + URL + description + transcription).
 */
class TranscriptActivity : AppCompatActivity() {

    private lateinit var ui: ActivityTranscriptBinding
    private var transcript: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityTranscriptBinding.inflate(layoutInflater)
        setContentView(ui.root)

        ui.trTitle.text = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        ui.trBack.setOnClickListener { finish() }
        ui.trCopy.setOnClickListener { copy() }
        ui.trExport.setOnClickListener { exportTxt() }

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (url.isEmpty()) { finish(); return }
        loadTranscript(url)
    }

    private fun loadTranscript(url: String) {
        ui.trProgress.isVisible = true
        ui.trText.text = getString(R.string.tr_loading)
        lifecycleScope.launch {
            val segments = runCatching { Engine.transcript(this@TranscriptActivity, url) }
                .getOrDefault(emptyList())
            ui.trProgress.isVisible = false
            if (segments.isEmpty()) {
                ui.trText.text = getString(R.string.tr_none)
                ui.trExport.isEnabled = false
                return@launch
            }
            transcript = segments.joinToString("\n") { (ms, t) -> "[${fmt(ms)}] $t" }
            ui.trText.text = transcript
        }
    }

    /**
     * 📄 Export .txt : en-tête (titre, URL, description) puis transcription
     * complète, écrit dans Téléchargements/MKDownloader/Extraits/.
     */
    private fun exportTxt() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.tr_export_comments)
            .setPositiveButton(R.string.cmt_with) { _, _ -> doExport(true) }
            .setNegativeButton(R.string.cmt_without) { _, _ -> doExport(false) }
            .show()
    }

    private fun doExport(withComments: Boolean) {
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (url.isEmpty()) return
        ui.trProgress.isVisible = true
        ui.trExport.isEnabled = false
        lifecycleScope.launch {
            val result = runCatching {
                val t = Extracts.fetch(this@TranscriptActivity, url, withComments)
                Extracts.saveFiles(this@TranscriptActivity, t, null)
                    ?: throw IllegalStateException(getString(R.string.file_write_failed))
            }
            ui.trProgress.isVisible = false
            ui.trExport.isEnabled = true
            result.onSuccess { uri ->
                MaterialAlertDialogBuilder(this@TranscriptActivity)
                    .setMessage(getString(R.string.tr_exported))
                    .setNegativeButton(R.string.close, null)
                    .setPositiveButton(R.string.ext_share_txt) { _, _ ->
                        runCatching {
                            startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_STREAM, android.net.Uri.parse(uri))
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    },
                                    getString(R.string.ext_share_txt),
                                ),
                            )
                        }
                    }
                    .show()
            }.onFailure { toast(it.message ?: getString(R.string.tr_none)) }
        }
    }

    private fun copy() {
        val text = ui.trText.text?.toString().orEmpty()
        if (text.isBlank()) return
        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
            .setPrimaryClip(android.content.ClipData.newPlainText("transcript", text))
        toast(getString(R.string.tr_copied))
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    private fun toast(m: String) =
        android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"

        fun start(ctx: Context, url: String, title: String) {
            ctx.startActivity(Intent(ctx, TranscriptActivity::class.java).apply {
                putExtra(EXTRA_URL, url); putExtra(EXTRA_TITLE, title)
            })
        }
    }
}
