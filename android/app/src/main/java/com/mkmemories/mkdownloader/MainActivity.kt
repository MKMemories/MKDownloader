package com.mkmemories.mkdownloader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mkmemories.mkdownloader.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var ui: ActivityMainBinding
    private lateinit var results: ResultsAdapter
    private lateinit var history: HistoryAdapter
    private var currentItem: VideoItem? = null
    private var dateFilter: DateFilter = DateFilter.ANY
    private var sourceFilter: String = "Tout"
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityMainBinding.inflate(layoutInflater)
        setContentView(ui.root)
        ui.hero.load(R.drawable.hero)

        results = ResultsAdapter(onPlay = ::openPlayer, onDownload = ::askQualityAndDownload, onMp3 = ::downloadMp3)
        ui.results.layoutManager = LinearLayoutManager(this)
        ui.results.adapter = results

        history = HistoryAdapter(onOpen = ::openFile, onDelete = ::confirmDeleteEntry)
        ui.historyList.layoutManager = LinearLayoutManager(this)
        ui.historyList.adapter = history

        buildDateChips()

        ui.searchInput.setOnEditorActionListener { _, _, _ -> submit(); true }
        ui.goButton.setOnClickListener { submit() }
        ui.pasteButton.setOnClickListener { pasteFromClipboard() }
        ui.playCurrent.setOnClickListener { currentItem?.let(::openPlayer) }
        ui.downloadCurrent.setOnClickListener { currentItem?.let(::askQualityAndDownload) }
        ui.mp3Current.setOnClickListener { currentItem?.let(::downloadMp3) }
        ui.updateButton.setOnClickListener { updateEngine() }
        ui.clearHistory.setOnClickListener { confirmClearHistory() }

        ui.tabs.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val onSearch = checkedId == R.id.tabSearch
            ui.searchPane.isVisible = onSearch
            ui.historyPane.isVisible = !onSearch
            if (!onSearch) refreshHistory()
        }
        ui.tabs.check(R.id.tabSearch)

        handleShareIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        Downloads.onChange = ::renderDownloadState
        Downloads.onHistoryChanged = { if (ui.historyPane.isVisible) refreshHistory() }
        renderDownloadState(Downloads.state)
    }

    override fun onStop() {
        super.onStop()
        Downloads.onChange = null
        Downloads.onHistoryChanged = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    // ---------- Recherche / analyse ----------

    private fun buildDateChips() {
        DateFilter.values().forEach { f ->
            val chip = Chip(this).apply {
                text = f.label
                isCheckable = true
                isChecked = f == DateFilter.ANY
                // Le ChipGroup (singleSelection) gère l'exclusivité visuelle ;
                // on ne mémorise ici que le filtre choisi.
                setOnClickListener { dateFilter = f }
            }
            ui.dateChips.addView(chip)
        }
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = Regex("https?://\\S+").find(text)?.value ?: return
        ui.tabs.check(R.id.tabSearch)
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
                    platformOf(item.url),
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
        val status = if (dateFilter == DateFilter.ANY) R.string.searching else R.string.searching_recent
        setBusy(true, status)
        ui.videoCard.isVisible = false
        lifecycleScope.launch {
            try {
                val items = Engine.search(this@MainActivity, query, dateFilter)
                results.submit(items)
                ui.results.isVisible = items.isNotEmpty()
                if (items.isEmpty()) toast(getString(R.string.no_results))
            } catch (e: Exception) {
                toast(cleanError(e))
            } finally {
                setBusy(false)
            }
        }
    }

    // ---------- Actions vidéo ----------

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
                launchDownload(item, QUALITIES[index])
            }
            .show()
    }

    private fun downloadMp3(item: VideoItem) = launchDownload(item, AUDIO_QUALITY)

    private fun launchDownload(item: VideoItem, quality: Quality) {
        if (!Downloads.start(this, item, quality)) toast(getString(R.string.one_at_a_time))
        else toast(getString(R.string.download_started))
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

    // ---------- Historique ----------

    private fun refreshHistory() {
        val all = History.all(this)
        val sources = listOf("Tout") + all.map { it.platform }.distinct()
        if (sourceFilter !in sources) sourceFilter = "Tout"
        ui.sourceChips.removeAllViews()
        sources.forEach { src ->
            val chip = Chip(this).apply {
                text = src
                isCheckable = true
                isChecked = src == sourceFilter
                setOnClickListener {
                    sourceFilter = src
                    refreshHistory()
                }
            }
            ui.sourceChips.addView(chip)
        }
        val filtered = if (sourceFilter == "Tout") all else all.filter { it.platform == sourceFilter }
        history.submit(filtered)
        ui.historyEmpty.isVisible = filtered.isEmpty()
        ui.historyList.isVisible = filtered.isNotEmpty()
        ui.clearHistory.isVisible = all.isNotEmpty()
    }

    private fun openFile(entry: HistoryEntry) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(entry.uri), if (entry.audio) "audio/*" else "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) {
            toast(getString(R.string.cannot_open))
        }
    }

    private fun confirmDeleteEntry(entry: HistoryEntry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_one_title)
            .setMessage(getString(R.string.delete_one_message, entry.title))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                History.remove(this, entry.id)
                refreshHistory()
            }
            .show()
    }

    private fun confirmClearHistory() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_all_title)
            .setMessage(R.string.clear_all_message)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.clear_list_only) { _, _ ->
                History.clear(this, alsoFiles = false)
                refreshHistory()
            }
            .setPositiveButton(R.string.clear_and_files) { _, _ ->
                History.clear(this, alsoFiles = true)
                refreshHistory()
            }
            .show()
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

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
