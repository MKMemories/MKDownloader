package com.mkmemories.mkdownloader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatEditText
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
    private lateinit var results: VideoAdapter
    private lateinit var musicResults: VideoAdapter
    private lateinit var favVideos: VideoAdapter
    private lateinit var channels: ChannelAdapter
    private lateinit var playlists: PlaylistAdapter
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

        setupAdapters()
        buildDateChips()
        wireSearch()
        wireMusic()
        wireFavorites()
        wireHistory()

        ui.bottomNav.setOnItemSelectedListener { item ->
            showPane(item.itemId); true
        }
        ui.bottomNav.selectedItemId = R.id.nav_search

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

    private fun showPane(itemId: Int) {
        ui.searchPane.isVisible = itemId == R.id.nav_search
        ui.musicPane.isVisible = itemId == R.id.nav_music
        ui.favoritesPane.isVisible = itemId == R.id.nav_favorites
        ui.historyPane.isVisible = itemId == R.id.nav_history
        when (itemId) {
            R.id.nav_music -> refreshPlaylists()
            R.id.nav_favorites -> refreshFavorites()
            R.id.nav_history -> refreshHistory()
        }
    }

    // ---------- Adapters ----------

    private fun setupAdapters() {
        results = VideoAdapter(
            isFav = { Favorites.isVideoFav(this, it.url) },
            onPlay = ::openPlayer,
            onMp3 = { downloadMp3(it) },
            onToggleFav = { Favorites.toggleVideo(this, it) },
            onDownload = ::askQualityAndDownload,
            onMore = ::showVideoMenu,
        )
        ui.results.layoutManager = LinearLayoutManager(this)
        ui.results.adapter = results

        musicResults = VideoAdapter(
            isFav = { Favorites.isVideoFav(this, it.url) },
            onPlay = { openMusic(listOf(it), 0) },
            onMp3 = { downloadMp3(it) },
            onToggleFav = { Favorites.toggleVideo(this, it) },
            onMore = ::showTrackMenu,
            playLabel = getString(R.string.listen),
        )
        ui.musicResults.layoutManager = LinearLayoutManager(this)
        ui.musicResults.adapter = musicResults

        favVideos = VideoAdapter(
            isFav = { Favorites.isVideoFav(this, it.url) },
            onPlay = ::openPlayer,
            onMp3 = { downloadMp3(it) },
            onToggleFav = { Favorites.toggleVideo(this, it); refreshFavorites() },
            onDownload = ::askQualityAndDownload,
            onMore = ::showVideoMenu,
        )
        ui.favVideosList.layoutManager = LinearLayoutManager(this)
        ui.favVideosList.adapter = favVideos

        channels = ChannelAdapter(
            isFav = { Favorites.isChannelFav(this, it.url) },
            onOpen = ::openChannel,
            onToggleFav = { Favorites.toggleChannel(this, it); refreshFavorites() },
        )
        ui.channelList.layoutManager = LinearLayoutManager(this)
        ui.channelList.adapter = channels

        playlists = PlaylistAdapter(
            onOpen = ::openPlaylist,
            onPlay = ::playPlaylist,
            onDelete = ::confirmDeletePlaylist,
        )
        ui.playlists.layoutManager = LinearLayoutManager(this)
        ui.playlists.adapter = playlists

        history = HistoryAdapter(onOpen = ::openFile, onDelete = ::confirmDeleteEntry)
        ui.historyList.layoutManager = LinearLayoutManager(this)
        ui.historyList.adapter = history
    }

    // ---------- RECHERCHE ----------

    private fun buildDateChips() {
        DateFilter.values().forEach { f ->
            val chip = Chip(this).apply {
                text = f.label
                isCheckable = true
                isChecked = f == DateFilter.ANY
                setOnClickListener { dateFilter = f }
            }
            ui.dateChips.addView(chip)
        }
    }

    private fun wireSearch() {
        ui.searchInput.setOnEditorActionListener { _, _, _ -> submit(); true }
        ui.goButton.setOnClickListener { submit() }
        ui.pasteButton.setOnClickListener { pasteFromClipboard() }
        ui.playCurrent.setOnClickListener { currentItem?.let(::openPlayer) }
        ui.downloadCurrent.setOnClickListener { currentItem?.let(::askQualityAndDownload) }
        ui.mp3Current.setOnClickListener { currentItem?.let { downloadMp3(it) } }
        ui.favCurrent.setOnClickListener {
            currentItem?.let { Favorites.toggleVideo(this, it); updateFavCurrentLabel() }
        }
        ui.channelCurrent.setOnClickListener { currentItem?.let { openChannelFromVideo(it) } }
        ui.updateButton.setOnClickListener { updateEngine() }
        ui.channelBannerClose.setOnClickListener {
            ui.channelBanner.isVisible = false
            results.submit(emptyList()); ui.results.isVisible = false
        }
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = Regex("https?://\\S+").find(text)?.value ?: return
        ui.bottomNav.selectedItemId = R.id.nav_search
        ui.searchInput.setText(url)
        analyzeUrl(url)
    }

    private fun pasteFromClipboard() {
        val clip = (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
            .primaryClip?.getItemAt(0)?.text?.toString()?.trim()
        if (!clip.isNullOrEmpty()) { ui.searchInput.setText(clip); submit() }
    }

    private fun submit() {
        val input = ui.searchInput.text?.toString().orEmpty().trim()
        if (input.isEmpty() || busy) return
        if (input.startsWith("http://") || input.startsWith("https://")) analyzeUrl(input)
        else searchVideos(input)
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
        ui.channelBanner.isVisible = false
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
                updateFavCurrentLabel()
                ui.channelCurrent.isVisible = item.channelUrl != null || item.channelName != null
                ui.videoCard.isVisible = true
            } catch (e: Exception) {
                toast(cleanError(e))
            } finally {
                setBusy(false)
            }
        }
    }

    private fun updateFavCurrentLabel() {
        val fav = currentItem?.let { Favorites.isVideoFav(this, it.url) } ?: false
        ui.favCurrent.setText(if (fav) R.string.remove_favorite else R.string.add_favorite)
    }

    private fun searchVideos(query: String) {
        val status = if (dateFilter == DateFilter.ANY) R.string.searching else R.string.searching_recent
        setBusy(true, status)
        ui.videoCard.isVisible = false
        ui.channelBanner.isVisible = false
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

    /** Menu ⋮ d'une vidéo : chaîne + playlist. */
    private fun showVideoMenu(item: VideoItem, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(R.string.view_channel).setOnMenuItemClickListener { openChannelFromVideo(item); true }
            menu.add(R.string.add_channel_fav).setOnMenuItemClickListener { addChannelFav(item); true }
            menu.add(R.string.add_to_playlist).setOnMenuItemClickListener { choosePlaylist(item); true }
            show()
        }
    }

    private fun showTrackMenu(item: VideoItem, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(R.string.add_to_playlist).setOnMenuItemClickListener { choosePlaylist(item); true }
            menu.add(R.string.view_channel).setOnMenuItemClickListener { openChannelFromVideo(item); true }
            show()
        }
    }

    // ---------- Chaînes ----------

    private fun openChannelFromVideo(item: VideoItem) {
        val direct = Engine.channelFromVideo(item)
        if (direct != null) { openChannel(direct); return }
        // La chaîne n'est pas connue depuis la liste : on ré-analyse la vidéo.
        setBusy(true, R.string.analyzing)
        lifecycleScope.launch {
            try {
                val full = Engine.getInfo(this@MainActivity, item.url)
                val ch = Engine.channelFromVideo(full)
                if (ch != null) openChannel(ch) else toast(getString(R.string.no_channel))
            } catch (e: Exception) {
                toast(cleanError(e))
            } finally { setBusy(false) }
        }
    }

    private fun addChannelFav(item: VideoItem) {
        val ch = Engine.channelFromVideo(item)
        if (ch == null) { openChannelFromVideo(item); return }
        val added = Favorites.toggleChannel(this, ch)
        toast(getString(if (added) R.string.channel_added else R.string.channel_removed))
    }

    private fun openChannel(channel: ChannelItem) {
        ui.bottomNav.selectedItemId = R.id.nav_search
        ui.videoCard.isVisible = false
        ui.channelBanner.isVisible = true
        ui.channelBannerText.text = getString(R.string.channel_header, channel.name)
        setBusy(true, R.string.loading_channel)
        lifecycleScope.launch {
            try {
                val vids = Engine.channelVideos(this@MainActivity, channel.url)
                results.submit(vids)
                ui.results.isVisible = vids.isNotEmpty()
                if (vids.isEmpty()) toast(getString(R.string.no_results))
            } catch (e: Exception) {
                toast(cleanError(e))
            } finally { setBusy(false) }
        }
    }

    // ---------- MUSIQUE ----------

    private fun wireMusic() {
        ui.musicInput.setOnEditorActionListener { _, _, _ -> musicSearch(); true }
        ui.musicGo.setOnClickListener { musicSearch() }
        ui.newPlaylist.setOnClickListener { promptNewPlaylist() }
    }

    private fun musicSearch() {
        val q = ui.musicInput.text?.toString().orEmpty().trim()
        if (q.isEmpty() || busy) return
        busy = true
        ui.musicProgress.isVisible = true
        lifecycleScope.launch {
            try {
                val items = Engine.searchMusic(this@MainActivity, q)
                musicResults.submit(items)
                if (items.isEmpty()) toast(getString(R.string.no_results))
            } catch (e: Exception) {
                toast(cleanError(e))
            } finally {
                busy = false; ui.musicProgress.isVisible = false
            }
        }
    }

    private fun refreshPlaylists() {
        val rows = Favorites.playlists(this).map { PlaylistRow(it.key, it.value.size) }
        playlists.submit(rows)
    }

    private fun promptNewPlaylist() {
        val input = AppCompatEditText(this).apply { hint = getString(R.string.playlist_name) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.new_playlist)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.create) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) { Favorites.createPlaylist(this, name); refreshPlaylists() }
            }
            .show()
    }

    private fun choosePlaylist(item: VideoItem) {
        val names = Favorites.playlistNames(this)
        if (names.isEmpty()) {
            // Aucune playlist : on en crée une puis on ajoute.
            val input = AppCompatEditText(this).apply { hint = getString(R.string.playlist_name) }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.new_playlist)
                .setView(input)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.create) { _, _ ->
                    val name = input.text?.toString()?.trim().orEmpty()
                    if (name.isNotEmpty()) {
                        Favorites.addToPlaylist(this, name, item)
                        toast(getString(R.string.added_to, name)); refreshPlaylists()
                    }
                }
                .show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_to_playlist)
            .setItems(names.toTypedArray()) { _, i ->
                Favorites.addToPlaylist(this, names[i], item)
                toast(getString(R.string.added_to, names[i])); refreshPlaylists()
            }
            .show()
    }

    private fun openPlaylist(name: String) {
        val tracks = Favorites.tracksOf(this, name)
        if (tracks.isEmpty()) { toast(getString(R.string.playlist_empty)); return }
        val options = arrayOf(
            getString(R.string.listen_all),
            getString(R.string.download_all_mp3),
            getString(R.string.delete_playlist),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(name)
            .setItems(options) { _, i ->
                when (i) {
                    0 -> openMusic(tracks, 0)
                    1 -> { if (!Downloads.startBatch(this, tracks, AUDIO_QUALITY)) toast(getString(R.string.one_at_a_time)) else toast(getString(R.string.batch_started)) }
                    2 -> confirmDeletePlaylist(name)
                }
            }
            .show()
    }

    private fun playPlaylist(name: String) {
        val tracks = Favorites.tracksOf(this, name)
        if (tracks.isEmpty()) toast(getString(R.string.playlist_empty)) else openMusic(tracks, 0)
    }

    private fun confirmDeletePlaylist(name: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_playlist)
            .setMessage(getString(R.string.delete_playlist_msg, name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> Favorites.deletePlaylist(this, name); refreshPlaylists() }
            .show()
    }

    private fun openMusic(tracks: List<VideoItem>, startIndex: Int) {
        MusicQueue.tracks = tracks
        MusicQueue.startIndex = startIndex
        startActivity(Intent(this, MusicPlayerActivity::class.java))
    }

    // ---------- FAVORIS ----------

    private fun wireFavorites() {
        ui.channelInput.setOnEditorActionListener { _, _, _ -> channelSearch(); true }
        ui.channelGo.setOnClickListener { channelSearch() }
        ui.favTabs.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val onChannels = checkedId == R.id.favChannelsTab
            ui.favChannelsBox.isVisible = onChannels
            ui.favVideosBox.isVisible = !onChannels
            refreshFavorites()
        }
        ui.favTabs.check(R.id.favChannelsTab)
    }

    private fun refreshFavorites() {
        if (ui.favChannelsBox.isVisible) {
            // Par défaut, on affiche les chaînes favorites (avant toute recherche).
            if (ui.channelInput.text.isNullOrBlank()) {
                val favs = Favorites.channels(this)
                channels.submit(favs)
                ui.channelListLabel.text = getString(
                    if (favs.isEmpty()) R.string.no_fav_channels else R.string.your_fav_channels
                )
            }
        } else {
            val vids = Favorites.videos(this)
            favVideos.submit(vids)
            ui.favVideosEmpty.isVisible = vids.isEmpty()
            ui.favVideosList.isVisible = vids.isNotEmpty()
        }
    }

    private fun channelSearch() {
        val q = ui.channelInput.text?.toString().orEmpty().trim()
        if (q.isEmpty() || busy) { if (q.isEmpty()) refreshFavorites(); return }
        busy = true
        ui.channelProgress.isVisible = true
        ui.channelListLabel.text = getString(R.string.searching_channels)
        lifecycleScope.launch {
            try {
                val found = Engine.searchChannels(this@MainActivity, q)
                channels.submit(found)
                ui.channelListLabel.text = getString(
                    if (found.isEmpty()) R.string.no_results else R.string.channel_results
                )
            } catch (e: Exception) {
                toast(cleanError(e))
            } finally {
                busy = false; ui.channelProgress.isVisible = false
            }
        }
    }

    // ---------- Téléchargements ----------

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

    // ---------- HISTORIQUE ----------

    private fun wireHistory() {
        ui.clearHistory.setOnClickListener { confirmClearHistory() }
    }

    private fun refreshHistory() {
        val all = History.all(this)
        val sources = listOf("Tout") + all.map { it.platform }.distinct()
        if (sourceFilter !in sources) sourceFilter = "Tout"
        ui.sourceChips.removeAllViews()
        sources.forEach { src ->
            val chip = Chip(this).apply {
                text = src; isCheckable = true; isChecked = src == sourceFilter
                setOnClickListener { sourceFilter = src; refreshHistory() }
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
            .setPositiveButton(R.string.delete) { _, _ -> History.remove(this, entry.id); refreshHistory() }
            .show()
    }

    private fun confirmClearHistory() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_all_title)
            .setMessage(R.string.clear_all_message)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.clear_list_only) { _, _ -> History.clear(this, false); refreshHistory() }
            .setPositiveButton(R.string.clear_and_files) { _, _ -> History.clear(this, true); refreshHistory() }
            .show()
    }

    private fun updateEngine() {
        if (busy) return
        setBusy(true, R.string.updating)
        lifecycleScope.launch {
            try { toast(Engine.updateYtDlp(this@MainActivity)) }
            catch (e: Exception) { toast(cleanError(e)) }
            finally { setBusy(false) }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
