package com.mkmemories.mkdownloader

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mkmemories.mkdownloader.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var ui: ActivityMainBinding
    private lateinit var adapter: ResultsAdapter
    private var currentItem: VideoItem? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityMainBinding.inflate(layoutInflater)
        setContentView(ui.root)

        adapter = ResultsAdapter(onPlay = ::openPlayer, onDownload = ::askQualityAndDownload)
        ui.results.layoutManager = LinearLayoutManager(this)
        ui.results.adapter = adapter

        ui.searchInput.setOnEditorActionListener { _, _, _ -> submit(); true }
        ui.goButton.setOnClickListener { submit() }
        ui.pasteButton.setOnClickListener { pasteFromClipboard() }
        ui.playCurrent.setOnClickListener { currentItem?.let(::openPlayer) }
        ui.downloadCurrent.setOnClickListener { currentItem?.let(::askQualityAndDownload) }
        ui.updateButton.setOnClickListener { updateEngine() }

        handleShareIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        Downloads.onChange = ::renderDownloadState
        renderDownloadState(Downloads.state)
    }

    override fun onStop() {
        super.onStop()
        Downloads.onChange = null
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
        ui.searchInput.setText(url)
        analyzeUrl(url)
    }

    private fun pasteFromClipboard() {
        val clip = (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
            .primaryClip?.getItemAt(0)?.text?.toString()?.trim()
        if (!clip.isNullOrEmpty()) {
            ui.searchInput.setText(clip)
            submit()
        }
    }

    /** URL → analyse du lien ; texte libre → recherche YouTube. */
    private fun submit() {
        val input = ui.searchInput.text?.toString().orEmpty().trim()
        if (input.isEmpty() || busy) return
        if (input.startsWith("http://") || input.startsWith("https://")) analyzeUrl(input)
        else search(input)
    }

    private fun setBusy(value: Boolean, statusRes: Int? = null) {
        busy = value
        ui.goButton.isEnabled = !value
        ui.searchProgress.isVisible = value
        ui.searchStatus.isVisible = value && statusRes != null
        statusRes?.let { ui.searchStatus.text = getString(it) }
    }

    private fun analyzeUrl(url: String) {
        setBusy(true, R.string.analyzing)
        ui.results.isVisible = false
        ui.videoCard.isVisible = false
        lifecycleScope.launch {
            try {
                val item = Engine.getInfo(this@MainActivity, url)
                currentItem = item
                ui.videoTitle.text = item.title
                ui.videoMeta.text = listOfNotNull(
                    item.uploader?.takeIf { it.isNotEmpty() },
                    formatDuration(item.durationSec).takeIf { it.isNotEmpty() },
                ).joinToString(" · ")
                if (!item.thumbnail.isNullOrEmpty()) ui.thumbnail.load(item.thumbnail)
                ui.videoCard.isVisible = true
            } catch (e: Exception) {
                toast(cleanError(e))
            } finally {
                setBusy(false)
            }
        }
    }

    private fun search(query: String) {
        setBusy(true, R.string.searching)
        ui.videoCard.isVisible = false
        lifecycleScope.launch {
            try {
                val items = Engine.search(this@MainActivity, query)
                adapter.submit(items)
                ui.results.isVisible = items.isNotEmpty()
                if (items.isEmpty()) toast(getString(R.string.no_results))
            } catch (e: Exception) {
                toast(cleanError(e))
            } finally {
                setBusy(false)
            }
        }
    }

    private fun openPlayer(item: VideoItem) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_URL, item.url)
            putExtra(PlayerActivity.EXTRA_TITLE, item.title)
        })
    }

    private fun askQualityAndDownload(item: VideoItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_quality)
            .setItems(QUALITIES.map { it.label }.toTypedArray()) { _, index ->
                if (!Downloads.start(this, item, QUALITIES[index])) {
                    toast(getString(R.string.one_at_a_time))
                }
            }
            .show()
    }

    private fun renderDownloadState(state: Downloads.State) {
        val visible = state.running || state.message != null || state.error != null
        ui.downloadCard.isVisible = visible
        if (!visible) return
        ui.downloadLabel.text = state.label
        ui.downloadProgress.isVisible = state.running
        if (state.running) {
            ui.downloadProgress.isIndeterminate = state.percent < 0
            if (state.percent >= 0) ui.downloadProgress.setProgressCompat(state.percent, true)
            ui.downloadStatus.text =
                if (state.percent >= 0) getString(R.string.downloading, state.percent)
                else getString(R.string.processing)
        } else {
            ui.downloadStatus.text = state.message ?: state.error
        }
    }

    private fun updateEngine() {
        if (busy) return
        setBusy(true, R.string.updating)
        lifecycleScope.launch {
            try {
                toast(Engine.updateYtDlp(this@MainActivity))
            } catch (e: Exception) {
                toast(cleanError(e))
            } finally {
                setBusy(false)
            }
        }
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
