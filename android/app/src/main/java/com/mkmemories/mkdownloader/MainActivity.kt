package com.mkmemories.mkdownloader

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AutoCompleteTextView
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.common.util.concurrent.ListenableFuture
import com.mkmemories.mkdownloader.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val BLAST_URL = "https://www.youtube.com/@Blast_Info"
private const val BLAST_QUERY = "Blast Le souffle de l'info"

@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var ui: ActivityMainBinding
    private lateinit var results: VideoAdapter
    private lateinit var musicResults: VideoAdapter
    private lateinit var favVideos: VideoAdapter
    private lateinit var channels: ChannelAdapter
    private lateinit var playlists: PlaylistAdapter
    private lateinit var history: HistoryAdapter
    private lateinit var tv: TvAdapter
    private var tvAll: List<TvChannel> = emptyList()

    private var currentItem: VideoItem? = null
    private var dateFilter: DateFilter = DateFilter.ANY
    private var sourceFilter: String = "Tout"
    private var lastQuery: String = ""
    private var busy = false
    private val suggestJobs = HashMap<Int, Job>()
    private var skeletonPulse: ObjectAnimator? = null
    private var wasDownloading = false

    // Mini-lecteur : contrôleur média branché sur le service musical.
    private var miniControllerFuture: ListenableFuture<MediaController>? = null
    private var miniController: MediaController? = null
    private var miniArtUri: String? = null
    private val miniListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refreshMini()
    }

    /** Autocomplétion YouTube en direct, habillage premium. */
    private fun attachSuggestions(field: AutoCompleteTextView, onPick: () -> Unit) {
        val adapter = SuggestionsAdapter(this)
        field.setAdapter(adapter)
        field.threshold = 1
        field.setDropDownBackgroundResource(R.drawable.dropdown_bg)
        field.dropDownVerticalOffset = (8 * resources.displayMetrics.density).toInt()
        // Ignore le prochain changement de texte déclenché par une sélection
        // (sinon la liste se rouvre aussitôt).
        var suppress = false
        field.setOnItemClickListener { _, _, _, _ ->
            suppress = true
            field.dismissDropDown()
            hideKeyboard(field)
            onPick()
        }
        field.addTextChangedListener { editable ->
            if (suppress) { suppress = false; return@addTextChangedListener }
            val q = editable?.toString()?.trim().orEmpty()
            suggestJobs[field.id]?.cancel()
            if (q.length < 2 || q.startsWith("http")) { field.dismissDropDown(); return@addTextChangedListener }
            suggestJobs[field.id] = lifecycleScope.launch {
                delay(180)
                val list = runCatching { Suggest.fetch(q) }.getOrDefault(emptyList())
                if (list.isNotEmpty() && field.hasFocus()) {
                    adapter.replace(list)
                    field.showDropDown()
                }
            }
        }
    }

    private fun hideKeyboard(view: View) {
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(view.windowToken, 0)
        view.clearFocus()
    }

    /** Léger retour haptique sur les actions clés (feel premium). */
    private fun hapticTick() {
        ui.root.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
    }

    /** Ferme l'autocomplétion + le clavier au lancement d'une recherche. */
    private fun closeSuggest(field: AutoCompleteTextView) {
        suggestJobs[field.id]?.cancel()
        field.dismissDropDown()
        hideKeyboard(field)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
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
        wireMiniPlayer()

        ui.bottomNav.setOnItemSelectedListener { item ->
            hapticTick()
            showPane(item.itemId); true
        }
        ui.bottomNav.selectedItemId = R.id.nav_search

        handleShareIntent(intent)
        if (intent?.action != Intent.ACTION_SEND) loadHomeFeed()
    }

    /** Fil d'accueil : dernières vidéos des chaînes favorites (ou Blast par défaut). */
    private fun loadHomeFeed() {
        val favs = Favorites.channels(this)
        ui.videoCard.isVisible = false
        ui.channelBanner.isVisible = true
        ui.channelBannerText.text = getString(
            if (favs.isEmpty()) R.string.home_blast else R.string.home_favorites
        )
        setBusy(true, R.string.home_loading)
        lifecycleScope.launch {
            try {
                val vids = if (favs.isEmpty()) {
                    runCatching { Engine.channelVideos(this@MainActivity, BLAST_URL, 25) }
                        .getOrElse { runCatching { Engine.search(this@MainActivity, BLAST_QUERY) }.getOrDefault(emptyList()) }
                } else {
                    val lists = favs.take(5).map { ch ->
                        async {
                            runCatching { Engine.channelVideos(this@MainActivity, ch.url, 8) }
                                .getOrDefault(emptyList())
                        }
                    }.awaitAll()
                    interleave(lists)
                }
                results.submit(vids)
                ui.results.isVisible = vids.isNotEmpty()
                if (vids.isEmpty()) ui.channelBanner.isVisible = false
            } catch (e: Exception) {
                ui.channelBanner.isVisible = false
            } finally {
                setBusy(false)
            }
        }
    }

    private fun interleave(lists: List<List<VideoItem>>): List<VideoItem> {
        val out = ArrayList<VideoItem>()
        val max = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until max) for (l in lists) if (i < l.size) out.add(l[i])
        return out.distinctBy { it.url }
    }

    override fun onStart() {
        super.onStart()
        Downloads.onChange = ::renderDownloadState
        Downloads.onHistoryChanged = { if (ui.historyPane.isVisible) refreshHistory() }
        renderDownloadState(Downloads.state)
        bindMiniController()
    }

    override fun onStop() {
        super.onStop()
        Downloads.onChange = null
        Downloads.onHistoryChanged = null
        releaseMiniController()
    }

    // ---------- MINI-LECTEUR ----------

    private fun wireMiniPlayer() {
        ui.miniPlayer.setOnClickListener {
            if (MusicQueue.tracks.isEmpty()) return@setOnClickListener
            MusicQueue.resume = true
            startActivity(Intent(this, MusicPlayerActivity::class.java))
        }
        ui.miniPlayPause.setOnClickListener {
            hapticTick()
            miniController?.let { if (it.isPlaying) it.pause() else it.play() }
        }
        ui.miniClose.setOnClickListener {
            miniController?.apply { stop(); clearMediaItems() }
            ui.miniPlayer.isVisible = false
        }
    }

    private fun bindMiniController() {
        val token = SessionToken(this, ComponentName(this, MusicService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        miniControllerFuture = future
        future.addListener({
            miniController = runCatching { future.get() }.getOrNull()
            miniController?.addListener(miniListener)
            refreshMini()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun releaseMiniController() {
        miniController?.removeListener(miniListener)
        miniControllerFuture?.let { MediaController.releaseFuture(it) }
        miniController = null
        miniControllerFuture = null
    }

    private fun refreshMini() {
        val c = miniController
        val md = c?.currentMediaItem?.mediaMetadata
        val hasTrack = c != null && c.mediaItemCount > 0 && md != null
        ui.miniPlayer.isVisible = hasTrack
        if (!hasTrack) return
        ui.miniTitle.text = md?.title ?: ""
        ui.miniArtist.text = md?.artist ?: ""
        val art = md?.artworkUri?.toString()
        if (art != null && art != miniArtUri) { miniArtUri = art; loadMiniArt(art) }
        ui.miniPlayPause.setIconResource(
            if (c!!.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
    }

    /** Couleur dynamique : le mini-lecteur se pare de la couleur de la pochette. */
    private fun loadMiniArt(url: String) {
        val fallback = ContextCompat.getColor(this, R.color.accent2)
        val request = coil.request.ImageRequest.Builder(this)
            .data(url)
            .allowHardware(false)
            .target { drawable ->
                ui.miniArt.setImageDrawable(drawable)
                (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.let { bmp ->
                    androidx.palette.graphics.Palette.from(bmp).generate { p ->
                        val col = p?.getVibrantColor(p.getDominantColor(fallback)) ?: fallback
                        ui.miniPlayer.strokeColor = col
                        // Fond vivant : dégradé sombre teinté de la couleur du morceau.
                        val dim = android.graphics.Color.rgb(
                            android.graphics.Color.red(col) * 28 / 100,
                            android.graphics.Color.green(col) * 28 / 100,
                            android.graphics.Color.blue(col) * 28 / 100,
                        )
                        ui.livingBg.background = android.graphics.drawable.GradientDrawable(
                            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                            intArrayOf(dim, android.graphics.Color.BLACK),
                        )
                    }
                }
            }
            .build()
        coil.ImageLoader(this).enqueue(request)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun showPane(itemId: Int) {
        ui.searchPane.isVisible = itemId == R.id.nav_search
        ui.musicPane.isVisible = itemId == R.id.nav_music
        ui.tvPane.isVisible = itemId == R.id.nav_tv
        ui.favoritesPane.isVisible = itemId == R.id.nav_favorites
        ui.historyPane.isVisible = itemId == R.id.nav_history
        when (itemId) {
            R.id.nav_tv -> loadTv()
            R.id.nav_music -> {
                refreshPlaylists()
                // Report de la recherche : bascule sans retaper la même requête.
                if (ui.musicInput.text.isNullOrBlank() && lastQuery.isNotBlank()) {
                    ui.musicInput.setText(lastQuery)
                    musicSearch()
                }
            }
            R.id.nav_favorites -> refreshFavorites()
            R.id.nav_history -> refreshHistory()
        }
    }

    // ---------- Adapters ----------

    private fun setupAdapters() {
        results = VideoAdapter(
            isFav = { Favorites.isVideoFav(this, it.url) },
            onPlay = ::openPlayer,
            onToggleFav = { hapticTick(); Favorites.toggleVideo(this, it) },
            onMore = ::showVideoMenu,
        )
        ui.results.layoutManager = LinearLayoutManager(this)
        ui.results.adapter = results

        musicResults = VideoAdapter(
            isFav = { Favorites.isVideoFav(this, it.url) },
            onPlay = { openMusic(listOf(it), 0) },
            onToggleFav = { Favorites.toggleVideo(this, it) },
            onMore = ::showTrackMenu,
        )
        ui.musicResults.layoutManager = LinearLayoutManager(this)
        ui.musicResults.adapter = musicResults

        favVideos = VideoAdapter(
            isFav = { Favorites.isVideoFav(this, it.url) },
            onPlay = ::openPlayer,
            onToggleFav = { Favorites.toggleVideo(this, it); refreshFavorites() },
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

        tv = TvAdapter(onPlay = ::playChannel)
        ui.tvList.layoutManager = LinearLayoutManager(this)
        ui.tvList.adapter = tv
        ui.tvFilter.addTextChangedListener { applyTvFilter() }
        // Plus d'IPTV ni de comptes DRM : la liste est 100 % directs YouTube.
        ui.tvAccounts.isVisible = false
    }

    private fun showAccountsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_accounts, null)
        val tf1U = view.findViewById<android.widget.EditText>(R.id.tf1User)
        val tf1P = view.findViewById<android.widget.EditText>(R.id.tf1Pass)
        val m6U = view.findViewById<android.widget.EditText>(R.id.m6User)
        val m6P = view.findViewById<android.widget.EditText>(R.id.m6Pass)
        Settings.creds(this, "tf1")?.let { tf1U.setText(it.user); tf1P.setText(it.pass) }
        Settings.creds(this, "m6")?.let { m6U.setText(it.user); m6P.setText(it.pass) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tv_accounts)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                Settings.setCreds(this, "tf1", tf1U.text.toString().trim(), tf1P.text.toString())
                Settings.setCreds(this, "m6", m6U.text.toString().trim(), m6P.text.toString())
                toast(getString(R.string.accounts_saved))
            }
            .show()
    }

    private fun applyTvFilter() {
        val q = ui.tvFilter.text?.toString().orEmpty().trim().lowercase()
        val filtered = if (q.isEmpty()) tvAll
        else tvAll.filter { it.name.lowercase().contains(q) || (it.group?.lowercase()?.contains(q) == true) }
        tv.submit(filtered)
    }

    private fun loadTv() {
        tvAll = Tv.CHANNELS
        ui.tvProgress.isVisible = false
        ui.tvStatus.isVisible = false
        applyTvFilter()
    }

    private fun showTvStatus(msg: String) {
        ui.tvStatus.text = msg
        ui.tvStatus.isVisible = true
    }

    private fun playChannel(c: TvChannel) {
        c.note?.let { toast(it) }
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_URL, c.url)
            putExtra(PlayerActivity.EXTRA_TITLE, c.name)
            // resolve=true → passe par yt-dlp (YouTube live) ; direct=true → HLS
            putExtra(PlayerActivity.EXTRA_DIRECT, !c.resolve)
            putExtra(PlayerActivity.EXTRA_LIVE, true)
        })
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
        attachSuggestions(ui.searchInput) { submit() }
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
        closeSuggest(ui.searchInput)
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
        setSkeleton(value)
    }

    /** Squelette pulsant : occupe l'écran pendant que la recherche charge. */
    private fun setSkeleton(show: Boolean) {
        ui.searchSkeleton.isVisible = show
        if (show) {
            if (skeletonPulse == null) {
                skeletonPulse = ObjectAnimator.ofFloat(ui.searchSkeleton, "alpha", 1f, 0.4f).apply {
                    duration = 750
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                }
            }
            skeletonPulse?.takeIf { !it.isStarted }?.start()
        } else {
            skeletonPulse?.cancel()
            ui.searchSkeleton.alpha = 1f
        }
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
        lastQuery = query
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

    /** Menu ⋮ d'une vidéo : lecture, téléchargements, playlist, chaîne. */
    private fun showVideoMenu(item: VideoItem, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(R.string.menu_play).setOnMenuItemClickListener { openPlayer(item); true }
            menu.add(R.string.menu_listen).setOnMenuItemClickListener { openMusic(listOf(item), 0); true }
            menu.add(R.string.menu_download_video).setOnMenuItemClickListener { askQualityAndDownload(item); true }
            menu.add(R.string.menu_download_mp3).setOnMenuItemClickListener { downloadMp3(item); true }
            menu.add(R.string.add_to_playlist).setOnMenuItemClickListener { choosePlaylist(item); true }
            menu.add(R.string.view_channel).setOnMenuItemClickListener { openChannelFromVideo(item); true }
            menu.add(R.string.add_channel_fav).setOnMenuItemClickListener { addChannelFav(item); true }
            show()
        }
    }

    private fun showTrackMenu(item: VideoItem, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(R.string.menu_listen).setOnMenuItemClickListener { openMusic(listOf(item), 0); true }
            menu.add(R.string.menu_play).setOnMenuItemClickListener { openPlayer(item); true }
            menu.add(R.string.menu_download_mp3).setOnMenuItemClickListener { downloadMp3(item); true }
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
        attachSuggestions(ui.musicInput) { musicSearch() }
        ui.musicInput.setOnEditorActionListener { _, _, _ -> musicSearch(); true }
        ui.musicGo.setOnClickListener { musicSearch() }
        ui.newPlaylist.setOnClickListener { promptNewPlaylist() }
        ui.musicImport.setOnClickListener { showImportDialog() }
    }

    /** Import d'une playlist YouTube / YouTube Music à partir de son lien. */
    private fun showImportDialog() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val input = AppCompatEditText(this).apply {
            hint = getString(R.string.import_playlist_hint)
            setSingleLine(true)
            clipboardText()?.let { if (it.contains("list=") || it.contains("playlist")) setText(it) }
        }
        val note = android.widget.TextView(this).apply {
            text = getString(R.string.import_playlist_note)
            textSize = 12f
            setTextColor(androidx.core.content.ContextCompat.getColor(this@MainActivity, R.color.text_dim))
            setPadding(0, pad / 2, 0, 0)
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(input); addView(note)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_playlist_title)
            .setView(box)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.import_listen) { _, _ ->
                input.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { importPlaylistFlow(it, save = false) }
            }
            .setPositiveButton(R.string.import_save) { _, _ ->
                input.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { importPlaylistFlow(it, save = true) }
            }
            .show()
    }

    private fun clipboardText(): String? =
        (getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager)
            ?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()

    private fun importPlaylistFlow(url: String, save: Boolean) {
        if (busy) { toast(getString(R.string.one_at_a_time)); return }
        busy = true
        ui.musicProgress.isVisible = true
        toast(getString(R.string.importing))
        lifecycleScope.launch {
            try {
                val (title, tracks) = Engine.importPlaylist(this@MainActivity, url)
                if (tracks.isEmpty()) { toast(getString(R.string.import_empty)); return@launch }
                val name = title?.takeIf { it.isNotBlank() } ?: getString(R.string.import_playlist_title)
                if (save) {
                    Favorites.createPlaylist(this@MainActivity, name)
                    val added = Favorites.addAllToPlaylist(this@MainActivity, name, tracks)
                    refreshPlaylists()
                    toast(getString(R.string.imported, name, added))
                } else {
                    toast(getString(R.string.import_played, name, tracks.size))
                    openMusic(tracks, 0)
                }
            } catch (e: Exception) {
                toast(cleanError(e))
            } finally {
                busy = false; ui.musicProgress.isVisible = false
            }
        }
    }

    private fun musicSearch() {
        closeSuggest(ui.musicInput)
        val q = ui.musicInput.text?.toString().orEmpty().trim()
        if (q.isEmpty() || busy) return
        lastQuery = q
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
                    0 -> openMusic(tracks, 0, name)
                    1 -> { if (!Downloads.startBatch(this, tracks, AUDIO_QUALITY)) toast(getString(R.string.one_at_a_time)) else toast(getString(R.string.batch_started)) }
                    2 -> confirmDeletePlaylist(name)
                }
            }
            .show()
    }

    private fun playPlaylist(name: String) {
        val tracks = Favorites.tracksOf(this, name)
        if (tracks.isEmpty()) toast(getString(R.string.playlist_empty)) else openMusic(tracks, 0, name)
    }

    private fun confirmDeletePlaylist(name: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_playlist)
            .setMessage(getString(R.string.delete_playlist_msg, name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> Favorites.deletePlaylist(this, name); refreshPlaylists() }
            .show()
    }

    private fun openMusic(tracks: List<VideoItem>, startIndex: Int, playlistName: String? = null) {
        MusicQueue.tracks = tracks
        MusicQueue.startIndex = startIndex
        MusicQueue.playlistName = playlistName
        startActivity(Intent(this, MusicPlayerActivity::class.java))
        overridePendingTransition(R.anim.slide_in_up, R.anim.hold)
    }

    // ---------- FAVORIS ----------

    private fun wireFavorites() {
        attachSuggestions(ui.channelInput) { channelSearch() }
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
        closeSuggest(ui.channelInput)
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
        overridePendingTransition(R.anim.slide_in_up, R.anim.hold)
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
        // Rebond de célébration quand un téléchargement vient de se terminer.
        if (wasDownloading && !state.running && state.error == null) {
            ui.downloadCard.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
            ui.downloadCard.animate().scaleX(1.03f).scaleY(1.03f).setDuration(130)
                .withEndAction {
                    ui.downloadCard.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                }.start()
        }
        wasDownloading = state.running
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
