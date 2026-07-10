package com.mkmemories.mkdownloader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mkmemories.mkdownloader.databinding.ActivityTranscriptBinding
import kotlinx.coroutines.launch

/**
 * Transcription (sous-titres YouTube) + synthèse factuelle et neutre via Mistral.
 * Outil de dérushage pour créateur — reste strictement factuel.
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
        ui.trSynth.setOnClickListener { synthesize() }

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
                ui.trSynth.isEnabled = false
                return@launch
            }
            transcript = segments.joinToString("\n") { (ms, t) -> "[${fmt(ms)}] $t" }
            ui.trText.text = transcript
        }
    }

    private fun synthesize() {
        if (transcript.isBlank()) return
        if (!Mistral.hasKey(this)) { promptKey(); return }
        ui.trProgress.isVisible = true
        ui.trSynth.isEnabled = false
        lifecycleScope.launch {
            val result = runCatching { Mistral.synthesize(this@TranscriptActivity, transcript) }
            ui.trProgress.isVisible = false
            ui.trSynth.isEnabled = true
            result.onSuccess { synthesis ->
                ui.trText.text = getString(R.string.tr_synth_header) + "\n\n" + synthesis +
                    "\n\n———\n" + getString(R.string.tr_transcript_header) + "\n\n" + transcript
            }.onFailure { toast(it.message ?: getString(R.string.tmdb_error)) }
        }
    }

    private fun promptKey() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val input = AppCompatEditText(this).apply {
            hint = getString(R.string.tr_key_hint); setSingleLine(true)
            setText(Mistral.key(this@TranscriptActivity))
        }
        val note = android.widget.TextView(this).apply {
            text = getString(R.string.tr_key_note); textSize = 12f
            setTextColor(androidx.core.content.ContextCompat.getColor(this@TranscriptActivity, R.color.text_dim))
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0); addView(input); addView(note)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tr_key_title)
            .setView(box)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val k = input.text?.toString().orEmpty()
                if (k.isNotBlank()) { Mistral.setKey(this, k); synthesize() }
            }
            .show()
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
