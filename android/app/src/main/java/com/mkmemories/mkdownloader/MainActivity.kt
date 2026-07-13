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
import androidx.recyclerview.widget.GridLayoutManager
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

    private lateinit var resumeRow: CarouselAdapter
    private lateinit var channelsRow: CarouselAdapter
    private lateinit var trendingRow: CarouselAdapter

    private lateinit var cinema: CinemaAdapter
    private val cinemaLangs = linkedSetOf(Cinema.Lang.FR, Cinema.Lang.EN, Cinema.Lang.AR)
    private var cinemaGenre = Cinema.Genre.ALL
    private var cinemaDecade = Cinema.DECADES.first()
    private var cinemaSort = Cinema.SortBy.POPULAR
    private var cinemaLoaded = false

    private lateinit var trailers: VideoAdapter
    private var trailersLoaded = false
    private lateinit var tmdb: TmdbAdapter
    private var tmdbCategory = Tmdb.Category.NOW
    private var tmdbChipsBuilt = false

    private var currentItem: VideoItem? = null
    private var dateFilter: DateFilter = DateFilter.ANY
    private var sourceFilter: String = "Tout"
    private var lastQuery: String = ""
    private var busy = false
    private val suggestJobs = HashMap<Int, Job>()

    // Édition « L'Analyste 2027 » : outil d'analyse focalisé.
    private val isAnalyste get() = BuildConfig.FLAVOR == "analyste"
    private val selectedUrls = LinkedHashSet<String>()
    private var selectMode = false
    private var skeletonPulse: ObjectAnimator? = null
    private var wasDownloading = false

    // Import d'un fichier cookies.txt (connexion YouTube fiable, hors WebView).
    private val pickCookies = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { importCookiesFrom(it) } }

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

        ui.versionLabel.text = runCatching {
            getString(R.string.app_name) + " v" + packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrDefault(getString(R.string.app_name))

        setupAdapters()
        buildDateChips()
        wireSearch()
        wireMusic()
        wireFavorites()
        wireHistory()
        wireCinema()
        wireMiniPlayer()

        // La carte de téléchargement ouvre la file détaillée.
        ui.downloadCard.setOnClickListener { openDownloadQueue() }

        ui.bottomNav.setOnItemSelectedListener { item ->
            hapticTick()
            showPane(item.itemId); true
        }
        ui.bottomNav.selectedItemId = R.id.nav_search

        if (isAnalyste) setupAnalyse()

        Downloads.restore(this)          // recharge une file interrompue
        requestNotifPermission()
        handleShareIntent(intent)
        if (intent?.action != Intent.ACTION_SEND) loadHomeFeed()
        checkAppUpdate()
    }

    // ---------- Mise à jour de l'application (release GitHub) ----------

    /** Vérifie discrètement au lancement (throttle 12 h) si une version plus récente existe. */
    private fun checkAppUpdate(force: Boolean = false) {
        val prefs = getSharedPreferences("mkdl_update", MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("last_check", 0L) < 12 * 3600_000L) return
        lifecycleScope.launch {
            val u = runCatching { Updater.check() }.getOrNull()
            prefs.edit().putLong("last_check", now).apply()
            if (u == null) { if (force) toast(getString(R.string.upd_none)); return@launch }
            // Ne pas reproposer la même version après un « Plus tard » (sauf demande manuelle).
            if (!force && prefs.getInt("skip_vc", 0) == u.versionCode) return@launch
            promptUpdate(u)
        }
    }

    private fun promptUpdate(u: Updater.Update) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.upd_available, u.versionName))
            .setMessage(R.string.upd_message)
            .setNegativeButton(R.string.upd_later) { _, _ ->
                getSharedPreferences("mkdl_update", MODE_PRIVATE).edit()
                    .putInt("skip_vc", u.versionCode).apply()
            }
            .setPositiveButton(R.string.upd_now) { _, _ -> downloadAndInstall(u) }
            .show()
    }

    private fun downloadAndInstall(u: Updater.Update) {
        val bar = com.google.android.material.progressindicator.LinearProgressIndicator(this).apply {
            isIndeterminate = false; max = 100
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.upd_downloading)
            .setView(android.widget.FrameLayout(this).apply { setPadding(pad, pad, pad, pad); addView(bar) })
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            val file = runCatching {
                Updater.download(this@MainActivity, u.apkUrl) { p ->
                    runOnUiThread { bar.setProgressCompat(p, true) }
                }
            }.getOrNull()
            dialog.dismiss()
            if (file == null) { toast(getString(R.string.upd_failed)); return@launch }
            runCatching { Updater.install(this@MainActivity, file) }
                .onFailure { toast(getString(R.string.upd_failed)) }
        }
    }

    /** Demande la permission de notification (Android 13+) pour la progression en arrière-plan. */
    private fun requestNotifPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 42)
            }
        }
    }

    /**
     * Accueil découverte : trois carrousels — Reprendre la lecture, Vos chaînes,
     * Tendances. Remplace l'ancien fil unique par une vraie page de découverte.
     */
    private fun loadHomeFeed() {
        ui.videoCard.isVisible = false
        ui.channelBanner.isVisible = false
        ui.results.isVisible = false
        ui.homeScroll.isVisible = true

        refreshResumeRow()

        // Vos chaînes : dernières vidéos des favoris (ou Blast par défaut).
        val favs = Favorites.channels(this)
        setBusy(true, R.string.home_loading)
        lifecycleScope.launch {
            val channelsVids = runCatching {
                if (favs.isEmpty()) {
                    runCatching { Engine.channelVideos(this@MainActivity, BLAST_URL, 20) }
                        .getOrElse { Engine.search(this@MainActivity, BLAST_QUERY, DateFilter.ANY, 20) }
                } else {
                    val lists = favs.take(6).map { ch ->
                        async {
                            runCatching { Engine.channelVideos(this@MainActivity, ch.url, 6) }
                                .getOrDefault(emptyList())
                        }
                    }.awaitAll()
                    interleave(lists)
                }
            }.getOrDefault(emptyList())
            channelsRow.submit(channelsVids.map { CarouselItem(it) })
            ui.channelsSection.isVisible = channelsVids.isNotEmpty()
            setBusy(false)

            // Tendances (chargées séparément pour ne pas retarder l'affichage).
            val trending = runCatching { Engine.trending(this@MainActivity, 25) }.getOrDefault(emptyList())
            trendingRow.submit(trending.map { CarouselItem(it) })
            ui.trendingSection.isVisible = trending.isNotEmpty()
        }
    }

    /** Rafraîchit le carrousel « Reprendre » depuis les positions de lecture mémorisées. */
    private fun refreshResumeRow() {
        val recent = Resume.recent(this)
        val items = recent.map {
            CarouselItem(
                VideoItem(url = it.url, title = it.title, uploader = null, durationSec = 0, thumbnail = it.thumbnail),
                progress = it.percent,
            )
        }
        resumeRow.submit(items)
        ui.resumeSection.isVisible = items.isNotEmpty()
    }

    private fun interleave(lists: List<List<VideoItem>>): List<VideoItem> {
        val out = ArrayList<VideoItem>()
        val max = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until max) for (l in lists) if (i < l.size) out.add(l[i])
        return out.distinctBy { it.url }
    }

    override fun onStart() {
        super.onStart()
        Downloads.onChange = { renderDownloads() }
        Downloads.onHistoryChanged = { if (ui.historyPane.isVisible) refreshHistory() }
        renderDownloads()
        Downloads.resumeIfNeeded(this)
        bindMiniController()
    }

    override fun onResume() {
        super.onResume()
        // Après une lecture, « Reprendre » se met à jour si l'accueil est affiché.
        if (ui.homeScroll.isVisible) refreshResumeRow()
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
        ui.cinemaPane.isVisible = itemId == R.id.nav_cinema
        ui.favoritesPane.isVisible = itemId == R.id.nav_favorites
        ui.historyPane.isVisible = itemId == R.id.nav_history
        when (itemId) {
            R.id.nav_cinema -> if (!cinemaLoaded && ui.watchFilms.isChecked) loadCinema()
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
            isSelected = { it.url in selectedUrls },
            onToggleSelect = { toggleSelect(it) },
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

        history = HistoryAdapter(onOpen = ::openFile, onDelete = ::confirmDeleteEntry, onMore = ::showLibraryMenu)
        ui.historyList.layoutManager = LinearLayoutManager(this)
        ui.historyList.adapter = history

        tv = TvAdapter(onPlay = ::playChannel)
        ui.tvList.layoutManager = LinearLayoutManager(this)
        ui.tvList.adapter = tv
        ui.tvFilter.addTextChangedListener { applyTvFilter() }

        cinema = CinemaAdapter(onOpen = ::openFilm)
        ui.cinemaList.layoutManager = GridLayoutManager(this, 3)
        ui.cinemaList.adapter = cinema

        trailers = VideoAdapter(
            isFav = { Favorites.isVideoFav(this, it.url) },
            onPlay = ::openPlayer,
            onToggleFav = { hapticTick(); Favorites.toggleVideo(this, it) },
            onMore = ::showVideoMenu,
        )
        ui.trailersList.layoutManager = LinearLayoutManager(this)
        ui.trailersList.adapter = trailers
        tmdb = TmdbAdapter(onOpen = ::openMovie)

        resumeRow = CarouselAdapter(onPlay = ::openPlayer)
        ui.resumeRow.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        ui.resumeRow.adapter = resumeRow

        channelsRow = CarouselAdapter(onPlay = ::openPlayer)
        ui.channelsRow.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        ui.channelsRow.adapter = channelsRow

        trendingRow = CarouselAdapter(onPlay = ::openPlayer)
        ui.trendingRow.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        ui.trendingRow.adapter = trendingRow
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

    // ---------- CINÉMA ----------

    private fun wireCinema() {
        // Bascule Films / Direct TV : le même onglet couvre les deux modes.
        ui.watchToggle.check(R.id.watchFilms)
        ui.watchToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            hapticTick()
            ui.cinemaFilmsBox.isVisible = checkedId == R.id.watchFilms
            ui.cinemaTrailersBox.isVisible = checkedId == R.id.watchTrailers
            ui.cinemaTvBox.isVisible = checkedId == R.id.watchTv
            when (checkedId) {
                R.id.watchFilms -> if (!cinemaLoaded) loadCinema()
                R.id.watchTrailers -> if (!trailersLoaded) loadTrailers()
                R.id.watchTv -> loadTv()
            }
        }
        ui.tmdbKeyBanner.setOnClickListener { promptTmdbKey() }

        // Langues : multi-sélection, au moins une reste toujours active.
        Cinema.Lang.values().forEach { lang ->
            ui.cinemaLangChips.addView(Chip(this).apply {
                text = lang.label
                isCheckable = true
                isChecked = lang in cinemaLangs
                setOnClickListener {
                    if (isChecked) {
                        cinemaLangs.add(lang)
                    } else if (cinemaLangs.size <= 1) {
                        isChecked = true; return@setOnClickListener
                    } else {
                        cinemaLangs.remove(lang)
                    }
                    if (cinemaLoaded) loadCinema()
                }
            })
        }
        // Genre.
        Cinema.Genre.values().forEach { g ->
            ui.cinemaGenreChips.addView(Chip(this).apply {
                text = g.label
                isCheckable = true
                isChecked = g == cinemaGenre
                setOnClickListener { cinemaGenre = g; loadCinema() }
            })
        }
        // Décennie.
        Cinema.DECADES.forEach { d ->
            ui.cinemaDecadeChips.addView(Chip(this).apply {
                text = d.label
                isCheckable = true
                isChecked = d == cinemaDecade
                setOnClickListener { cinemaDecade = d; loadCinema() }
            })
        }
        // Tri.
        Cinema.SortBy.values().forEach { s ->
            ui.cinemaSortChips.addView(Chip(this).apply {
                text = s.label
                isCheckable = true
                isChecked = s == cinemaSort
                setOnClickListener { cinemaSort = s; loadCinema() }
            })
        }
    }

    private fun loadCinema() {
        cinemaLoaded = true
        cinema.submit(emptyList())
        ui.cinemaStatus.isVisible = false
        ui.cinemaProgress.isVisible = true
        lifecycleScope.launch {
            val films = runCatching {
                Cinema.browse(cinemaLangs, cinemaGenre, cinemaDecade, cinemaSort)
            }
            ui.cinemaProgress.isVisible = false
            films.onSuccess { list ->
                cinema.submit(list)
                ui.cinemaList.isVisible = list.isNotEmpty()
                if (list.isEmpty()) showCinemaStatus(getString(R.string.cinema_empty))
            }.onFailure {
                showCinemaStatus(getString(R.string.cinema_error))
            }
        }
    }

    private fun showCinemaStatus(msg: String) {
        ui.cinemaStatus.text = msg
        ui.cinemaStatus.isVisible = true
        ui.cinemaList.isVisible = false
    }

    /**
     * Sorties ciné : mode enrichi TMDB (affiches + fiches + bande-annonce) si une
     * clé est configurée, sinon repli sur les bandes-annonces AlloCiné.
     */
    private fun loadTrailers() {
        trailersLoaded = true
        val hasKey = Tmdb.hasKey(this)
        // Toujours visible : activer (sans clé) ou modifier (avec clé) la clé TMDB.
        ui.tmdbKeyBanner.isVisible = true
        ui.tmdbKeyBanner.text = getString(if (hasKey) R.string.tmdb_change_key else R.string.tmdb_add_key)
        ui.tmdbChips.isVisible = hasKey
        if (hasKey) {
            if (!tmdbChipsBuilt) buildTmdbChips()
            loadTmdbMovies()
        } else {
            loadAllocineTrailers()
        }
    }

    private fun buildTmdbChips() {
        tmdbChipsBuilt = true
        Tmdb.Category.values().forEach { cat ->
            ui.tmdbChips.addView(Chip(this).apply {
                text = cat.label; isCheckable = true; isChecked = cat == tmdbCategory
                setOnClickListener { tmdbCategory = cat; loadTmdbMovies() }
            })
        }
    }

    private fun loadTmdbMovies() {
        ui.trailersList.layoutManager = GridLayoutManager(this, 3)
        ui.trailersList.adapter = tmdb
        tmdb.submit(emptyList())
        ui.trailersStatus.isVisible = false
        ui.trailersProgress.isVisible = true
        lifecycleScope.launch {
            val movies = runCatching { Tmdb.movies(this@MainActivity, tmdbCategory) }.getOrDefault(emptyList())
            ui.trailersProgress.isVisible = false
            tmdb.submit(movies)
            ui.trailersList.isVisible = movies.isNotEmpty()
            if (movies.isEmpty()) {
                ui.trailersStatus.text = getString(R.string.tmdb_error)
                ui.trailersStatus.isVisible = true
            }
        }
    }

    /** Ouvre la fiche film premium (backdrop, casting, où regarder, similaires). */
    private fun openMovie(m: Tmdb.Movie) {
        MovieDetailActivity.start(this, m.id, m.title, m.poster)
        overridePendingTransition(R.anim.slide_in_up, R.anim.hold)
    }

    private fun promptTmdbKey() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val input = AppCompatEditText(this).apply {
            hint = getString(R.string.tmdb_key_hint); setSingleLine(true)
            setText(Tmdb.key(this@MainActivity))
        }
        val note = android.widget.TextView(this).apply {
            text = getString(R.string.tmdb_key_note); textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_dim))
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0); addView(input); addView(note)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tmdb_add_key)
            .setView(box)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val k = input.text?.toString().orEmpty()
                if (k.isNotBlank()) {
                    Tmdb.setKey(this, k)
                    toast(getString(R.string.tmdb_key_saved))
                    loadTrailers()
                }
            }
            .show()
    }

    /** Bandes-annonces AlloCiné (repli sans clé TMDB). */
    private fun loadAllocineTrailers() {
        ui.trailersList.layoutManager = LinearLayoutManager(this)
        ui.trailersList.adapter = trailers
        trailers.submit(emptyList())
        ui.trailersStatus.isVisible = false
        ui.trailersProgress.isVisible = true
        lifecycleScope.launch {
            val lists = listOf(
                async {
                    runCatching {
                        Engine.channelVideos(this@MainActivity, "https://www.youtube.com/@AlloCine", 20)
                    }.getOrDefault(emptyList())
                },
                async {
                    runCatching {
                        Engine.search(this@MainActivity, "bande annonce officielle VF", DateFilter.MONTH, 15)
                    }.getOrDefault(emptyList())
                },
            ).awaitAll()
            val vids = interleave(lists)
            ui.trailersProgress.isVisible = false
            trailers.submit(vids)
            ui.trailersList.isVisible = vids.isNotEmpty()
            if (vids.isEmpty()) {
                ui.trailersStatus.text = getString(R.string.cinema_error)
                ui.trailersStatus.isVisible = true
            }
        }
    }

    /** Ouvre un film : lecture (plein écran + qualité) ou téléchargement. */
    private fun openFilm(film: Film) {
        val item = film.toVideoItem()
        MaterialAlertDialogBuilder(this)
            .setTitle(film.title)
            .setItems(
                arrayOf(getString(R.string.cinema_watch), getString(R.string.cinema_download))
            ) { _, which ->
                if (which == 0) openPlayer(item) else askQualityAndDownload(item)
            }
            .show()
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

    // ---------- Édition « L'Analyste 2027 » : outil d'analyse ----------

    private fun setupAnalyse() {
        // Nav focalisée : masque musique & cinéma.
        ui.bottomNav.menu.removeItem(R.id.nav_music)
        ui.bottomNav.menu.removeItem(R.id.nav_cinema)
        // Recherche thématique par personnalité + sélection multiple.
        ui.actorScroll.isVisible = true
        buildActorChips()
        ui.analyseTools.isVisible = true
        ui.selectToggle.setOnClickListener { toggleSelectionMode() }
        ui.trSelection.setOnClickListener { transcribeSelection() }
        ui.dlSelection.setOnClickListener { downloadSelection() }
        // Machine d'extraits : renvoyer une liste ID + plages → téléchargements.
        ui.importClips.isVisible = true
        ui.importClips.setOnClickListener { importClips() }
        // Compte YouTube global (connexion requise par certaines vidéos).
        ui.accountButton.isVisible = true
        ui.accountButton.setOnClickListener { showYoutubeAccountDialog() }
    }

    /** Réglage global : connexion YouTube par cookies (import de fichier ou WebView). */
    private fun showYoutubeAccountDialog() {
        val connected = Settings.youtubeCookies(this) != null
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        // Méthode fiable en premier : import d'un cookies.txt exporté du navigateur.
        actions += getString(R.string.yt_import_cookies) to { pickCookiesFile() }
        actions += getString(R.string.yt_login_signin) to {
            startActivity(Intent(this, YoutubeLoginActivity::class.java))
        }
        if (connected) actions += getString(R.string.yt_login_signout) to {
            Settings.clearYoutubeCookies(this)
            toast(getString(R.string.yt_login_cleared))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.yt_login_title)
            .setMessage(if (connected) R.string.yt_login_connected else R.string.yt_login_desc)
            .setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickCookiesFile() {
        toast(getString(R.string.yt_import_pick))
        runCatching { pickCookies.launch(arrayOf("text/plain", "text/*", "*/*")) }
            .onFailure { toast(getString(R.string.cannot_open)) }
    }

    /** Copie le cookies.txt choisi dans le stockage privé après validation. */
    private fun importCookiesFrom(uri: Uri) {
        runCatching {
            val text = contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
            require(text.contains("youtube.com", ignoreCase = true)) { "sans cookies YouTube" }
            Settings.saveYoutubeCookies(this, text)
        }.onSuccess {
            Logs.d("account", "cookies YouTube importés (fichier)")
            toast(getString(R.string.yt_login_ok))
        }.onFailure {
            Logs.w("account", "import cookies échoué : ${it.message}")
            toast(getString(R.string.yt_import_bad))
        }
    }

    private fun buildActorChips() {
        ui.actorChips.removeAllViews()
        ui.actorChips.addView(Chip(this).apply {
            text = getString(R.string.an_add_actor)
            setChipIconResource(android.R.drawable.ic_input_add)
            isChipIconVisible = true
            setOnClickListener { promptAddActor() }
        })
        Actors.all(this).forEach { name ->
            ui.actorChips.addView(Chip(this).apply {
                text = name
                setOnClickListener { hapticTick(); searchActor(name) }
            })
        }
    }

    private fun searchActor(name: String) {
        closeSuggest(ui.searchInput)
        ui.searchInput.setText(name)
        searchVideos(name)
    }

    private fun promptAddActor() {
        val input = AppCompatEditText(this).apply {
            hint = getString(R.string.an_add_actor_hint); setSingleLine(true)
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.an_add_actor)
            .setView(android.widget.FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) })
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val n = input.text?.toString().orEmpty()
                if (n.isNotBlank()) { Actors.add(this, n); buildActorChips(); searchActor(n) }
            }
            .show()
    }

    private fun toggleSelectionMode() {
        selectMode = !selectMode
        results.selectionMode = selectMode
        if (!selectMode) selectedUrls.clear()
        ui.trSelection.isVisible = selectMode
        ui.dlSelection.isVisible = selectMode
        ui.selectToggle.text = getString(if (selectMode) R.string.an_select_done else R.string.an_select)
        updateSelectionCount()
    }

    private fun toggleSelect(item: VideoItem) {
        if (!selectedUrls.add(item.url)) selectedUrls.remove(item.url)
        updateSelectionCount()
    }

    private fun updateSelectionCount() {
        ui.selectionCount.text = if (selectMode) getString(R.string.an_selected, selectedUrls.size) else ""
    }

    private fun selectedItems(): List<VideoItem> = results.items().filter { it.url in selectedUrls }

    private fun downloadSelection() {
        val items = selectedItems()
        if (items.isEmpty()) { toast(getString(R.string.an_no_selection)); return }
        // Vidéo entière OU seulement le passage le plus revisionné (moment fort).
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.an_dl_choice_title)
            .setItems(arrayOf(getString(R.string.an_dl_whole), getString(R.string.an_dl_highlight))) { _, which ->
                if (which == 0) askBatchQuality(items)
                else askHighlightBatchQuality(items)
            }
            .show()
    }

    private fun askHighlightBatchQuality(items: List<VideoItem>) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dl_batch_count, items.size))
            .setItems(QUALITIES.map { it.label }.toTypedArray()) { _, index ->
                downloadSelectionHighlights(items, QUALITIES[index])
            }
            .show()
    }

    /**
     * Extraction en masse des transcriptions des vidéos sélectionnées → fichiers
     * .txt, chacun estampillé d'un **identifiant unique** (aller-retour bijectif),
     * plus un index récapitulatif du lot.
     */
    private fun transcribeSelection() {
        val items = selectedItems()
        if (items.isEmpty()) { toast(getString(R.string.an_no_selection)); return }
        setBusy(true, R.string.an_transcribing)
        lifecycleScope.launch {
            var ok = 0
            val index = StringBuilder()
            index.append("L'ANALYSTE 2027 — INDEX DES TRANSCRIPTIONS\n")
            index.append("Renvoie une ligne par extrait voulu :  ID  début-fin  [libellé]\n")
            index.append("Exemple :  dQw4w9WgXcQ  12:40-14:10  Retraites\n")
            index.append("──────────────────────────────────────────\n")
            // Squelette prêt à compléter : une ligne « ID  - » par vidéo.
            val skeleton = StringBuilder()
            skeleton.append("# L'ANALYSTE 2027 — EXTRAITS À COMPLÉTER\n")
            skeleton.append("# Remplace le «  -  » par « début-fin » et ajoute un libellé.\n")
            skeleton.append("# Ex :  dQw4w9WgXcQ  12:40-14:10  Retraites\n")
            skeleton.append("# Duplique une ligne pour plusieurs extraits d'une même vidéo.\n")
            skeleton.append("# Puis colle tout dans « 📋 Télécharger des extraits (liste) ».\n")
            skeleton.append("# ─────────────────────────────────────────\n")
            for ((i, it) in items.withIndex()) {
                ui.searchStatus.isVisible = true
                ui.searchStatus.text = getString(R.string.an_highlights_finding, i + 1, items.size)
                val id = Clips.idFor(it.url)
                index.append(id).append("  ").append(it.title).append("\n")
                skeleton.append("\n# ").append(i + 1).append(". ").append(it.title).append("\n")
                skeleton.append(id).append("  -   \n")
                val segs = runCatching { Engine.transcript(this@MainActivity, it.url) }.getOrDefault(emptyList())
                if (segs.isNotEmpty()) {
                    val body = segs.joinToString("\n") { (ms, t) -> "[${fmtHms(ms)}] $t" }
                    runCatching { saveTranscriptFile(it, id, body) }.onSuccess { ok++ }
                }
            }
            runCatching { writeAnalysteFile("00_INDEX.txt", index.toString()) }
            runCatching { writeAnalysteFile("00_EXTRAITS_a_completer.txt", skeleton.toString()) }
            setBusy(false)
            toast(getString(R.string.an_transcribed, ok, items.size))
        }
    }

    private fun fmtHms(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    /** En-tête estampillé (ID unique) + corps → Téléchargements/Analyste/<ID> · <titre>.txt */
    private fun saveTranscriptFile(item: VideoItem, id: String, body: String) {
        val header = buildString {
            append("════════════════════════════════════════\n")
            append("L'ANALYSTE 2027 · TRANSCRIPTION\n")
            append("ID     : ").append(id).append("\n")
            append("TITRE  : ").append(item.title).append("\n")
            item.uploader?.let { append("CHAÎNE : ").append(it).append("\n") }
            append("URL    : ").append(item.url).append("\n")
            append("════════════════════════════════════════\n")
            append("▸ Pour télécharger un extrait, renvoie une ligne :\n")
            append("▸   ").append(id).append("  début-fin   [libellé]\n")
            append("▸   ex : ").append(id).append("  12:40-14:10   Passage clé\n")
            append("▸ Plusieurs extraits possibles pour la même vidéo.\n")
            append("════════════════════════════════════════\n\n")
        }
        val safeTitle = item.title.replace(Regex("[/\\\\:*?\"<>|]"), "_").take(100)
        writeAnalysteFile("$id · $safeTitle.txt", header + body)
    }

    /** Écrit un fichier texte dans Téléchargements/Analyste/ (repli dossier privé sous Android 9-). */
    private fun writeAnalysteFile(name: String, content: String) {
        val safe = name.replace(Regex("[/\\\\:*?\"<>|]"), "_").ifBlank { "analyste.txt" }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, safe)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(
                    android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS + "/Analyste",
                )
            }
            val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("écriture impossible")
            contentResolver.openOutputStream(uri)!!.use { it.write(content.toByteArray()) }
        } else {
            val dir = java.io.File(getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "Analyste")
            dir.mkdirs()
            java.io.File(dir, safe).writeText(content)
        }
    }

    // ---------- Machine d'extraits : import d'une liste → téléchargements ----------

    /** Ouvre le compositeur d'extraits (coller/saisir la liste ID + plages). */
    private fun importClips() {
        val input = AppCompatEditText(this).apply {
            hint = getString(R.string.an_clips_hint)
            setHorizontallyScrolling(false)
            maxLines = 12
            minLines = 6
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
            // Pré-remplissage si le presse-papiers ressemble déjà à une liste d'extraits.
            clipboardText()?.let { if (Clips.parse(it).isNotEmpty()) setText(it) }
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.an_clips_title)
            .setMessage(R.string.an_clips_help)
            .setView(android.widget.FrameLayout(this).apply {
                setPadding(pad, pad / 2, pad, 0); addView(input)
            })
            .setNeutralButton(R.string.paste) { _, _ ->
                // Colle le presse-papiers puis analyse directement (raccourci une main).
                val pasted = clipboardText().orEmpty()
                importClipsFromText((input.text?.toString().orEmpty() + "\n" + pasted).trim())
            }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.an_clips_go) { _, _ ->
                importClipsFromText(input.text?.toString().orEmpty())
            }
            .show()
    }

    private fun importClipsFromText(text: String) {
        val clips = Clips.parse(text)
        if (clips.isEmpty()) { toast(getString(R.string.an_clips_none)); return }
        val lines = text.split("\n").count { it.isNotBlank() && !it.trimStart().startsWith("#") }
        val ignored = (lines - clips.size).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.an_clips_found, clips.size))
            .setItems(QUALITIES.map { it.label }.toTypedArray()) { _, index ->
                enqueueClips(clips, QUALITIES[index])
                toast(getString(R.string.an_clips_queued, clips.size, ignored))
            }
            .show()
    }

    private fun enqueueClips(clips: List<Clips.Clip>, quality: Quality) {
        clips.forEach { c ->
            val label = c.label?.takeIf { it.isNotBlank() }
                ?: (getString(R.string.an_clip_default) + " " + Clips.idFor(c.url))
            val item = VideoItem(url = c.url, title = label, uploader = null, durationSec = 0, thumbnail = null)
            Downloads.start(this, item, quality, c.startSec, c.endSec)
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
        ui.appUpdateButton.setOnClickListener { toast(getString(R.string.upd_checking)); checkAppUpdate(force = true) }
        ui.logsButton.setOnClickListener { runCatching { Logs.share(this) }.onFailure { toast(getString(R.string.logs_empty)) } }
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
        ui.homeScroll.isVisible = false
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
        ui.homeScroll.isVisible = false
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

    /** Radio artiste : lance une station quasi infinie autour de l'artiste/du titre. */
    private fun artistRadio(item: VideoItem) {
        val seed = (item.channelName ?: item.uploader ?: item.title).trim().ifEmpty { item.title }
        toast(getString(R.string.artist_radio_starting, seed))
        lifecycleScope.launch {
            val tracks = runCatching { Engine.searchMusic(this@MainActivity, "$seed mix", 40) }
                .getOrDefault(emptyList())
            if (tracks.isEmpty()) toast(getString(R.string.no_results))
            else openMusic(tracks, 0)
        }
    }

    /** Menu ⋮ d'une vidéo : lecture, téléchargements, playlist, chaîne. */
    private fun showVideoMenu(item: VideoItem, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(R.string.menu_play).setOnMenuItemClickListener { openPlayer(item); true }
            menu.add(R.string.menu_listen).setOnMenuItemClickListener { openMusic(listOf(item), 0); true }
            menu.add(R.string.menu_artist_radio).setOnMenuItemClickListener { artistRadio(item); true }
            menu.add(R.string.menu_download_video).setOnMenuItemClickListener { askQualityAndDownload(item); true }
            menu.add(R.string.menu_highlight).setOnMenuItemClickListener { downloadHighlight(item); true }
            menu.add(R.string.menu_download_mp3).setOnMenuItemClickListener { downloadMp3(item); true }
            menu.add(R.string.menu_download_channel).setOnMenuItemClickListener { downloadChannel(item); true }
            menu.add(R.string.menu_transcript).setOnMenuItemClickListener {
                TranscriptActivity.start(this@MainActivity, item.url, item.title); true
            }
            menu.add(R.string.add_to_playlist).setOnMenuItemClickListener { choosePlaylist(item); true }
            menu.add(R.string.view_channel).setOnMenuItemClickListener { openChannelFromVideo(item); true }
            menu.add(R.string.add_channel_fav).setOnMenuItemClickListener { addChannelFav(item); true }
            show()
        }
    }

    private fun showTrackMenu(item: VideoItem, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(R.string.menu_listen).setOnMenuItemClickListener { openMusic(listOf(item), 0); true }
            menu.add(R.string.menu_artist_radio).setOnMenuItemClickListener { artistRadio(item); true }
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
        ui.homeScroll.isVisible = false
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
        ui.musicDownloads.setOnClickListener { hapticTick(); playDownloads() }
        buildRadioChips()
    }

    /** Radios françaises en direct (Radio Nova en tête). Un appui lance l'écoute. */
    private fun buildRadioChips() {
        Radio.STATIONS.forEach { station ->
            ui.radioChips.addView(Chip(this).apply {
                text = station.name
                isClickable = true
                setChipIconResource(android.R.drawable.ic_media_play)
                isChipIconVisible = true
                setOnClickListener {
                    hapticTick()
                    with(Radio) { openMusic(listOf(station.toVideoItem()), 0) }
                    toast(getString(R.string.radio_playing, station.name))
                }
            })
        }
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
        // Toujours proposer la création à la volée en tête de liste.
        val entries = (listOf(getString(R.string.playlist_new_inline)) + names).toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_to_playlist)
            .setItems(entries) { _, i ->
                if (i == 0) promptNewPlaylistWith(item)
                else {
                    val name = names[i - 1]
                    Favorites.addToPlaylist(this, name, item)
                    toast(getString(R.string.added_to, name)); refreshPlaylists()
                }
            }
            .show()
    }

    /** Crée une playlist à partir d'un titre (nom saisi), puis y ajoute le titre. */
    private fun promptNewPlaylistWith(item: VideoItem) {
        val input = AppCompatEditText(this).apply {
            hint = getString(R.string.playlist_name); setSingleLine(true)
            setText(item.title.take(40))
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.new_playlist)
            .setView(android.widget.FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) })
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.create) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    Favorites.addToPlaylist(this, name, item)
                    toast(getString(R.string.added_to, name)); refreshPlaylists()
                }
            }
            .show()
    }

    private fun openPlaylist(name: String) {
        val tracks = Favorites.tracksOf(this, name)
        if (tracks.isEmpty()) { toast(getString(R.string.playlist_empty)); return }
        val options = arrayOf(
            getString(R.string.listen_all),
            getString(R.string.playlist_test),
            getString(R.string.playlist_rename),
            getString(R.string.download_all_mp3),
            getString(R.string.delete_playlist),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(name)
            .setItems(options) { _, i ->
                when (i) {
                    0 -> openMusic(tracks, 0, name)
                    1 -> testPlaylist(name)
                    2 -> promptRenamePlaylist(name)
                    3 -> { Downloads.startBatch(this, tracks, AUDIO_QUALITY); toast(getString(R.string.dl_batch_queued, tracks.size)) }
                    4 -> confirmDeletePlaylist(name)
                }
            }
            .show()
    }

    private fun promptRenamePlaylist(name: String) {
        val input = AppCompatEditText(this).apply {
            hint = getString(R.string.playlist_name); setSingleLine(true); setText(name)
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.playlist_rename)
            .setView(android.widget.FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) })
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val newName = input.text?.toString()?.trim().orEmpty()
                if (Favorites.renamePlaylist(this, name, newName)) { refreshPlaylists(); toast(getString(R.string.added_to, newName)) }
                else toast(getString(R.string.playlist_rename_failed))
            }
            .show()
    }

    /**
     * Teste la lecture de chaque titre (résolution rapide via yt-dlp) et retire
     * ceux qui ne se lisent pas dans l'app, pour garder une playlist « propre ».
     */
    private fun testPlaylist(name: String) {
        val tracks = Favorites.tracksOf(this, name)
        if (tracks.isEmpty()) { toast(getString(R.string.playlist_empty)); return }
        Logs.d("test", "test playlist '$name' : ${tracks.size} titres")
        val progress = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.playlist_testing)
            .setMessage(getString(R.string.playlist_testing_n, 0, tracks.size, 0))
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            val ok = ArrayList<VideoItem>()
            var ko = 0
            for ((idx, t) in tracks.withIndex()) {
                progress.setMessage(getString(R.string.playlist_testing_n, idx + 1, tracks.size, ko))
                val stream = if (Playback.isDirect(t.url)) t.url
                             else runCatching { Engine.audioStreamUrl(this@MainActivity, t.url) }.getOrNull()
                if (stream != null) ok.add(t) else { ko++; Logs.w("test", "retiré (illisible) : ${t.title} — ${t.url}") }
            }
            progress.dismiss()
            if (ko == 0) { toast(getString(R.string.playlist_test_ok, tracks.size)); return@launch }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(getString(R.string.playlist_test_result, ko))
                .setMessage(getString(R.string.playlist_test_prune, ok.size, ko))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.playlist_test_apply) { _, _ ->
                    Favorites.setPlaylistTracks(this@MainActivity, name, ok)
                    refreshPlaylists()
                    toast(getString(R.string.playlist_test_done, ko))
                }
                .show()
        }
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
        Downloads.start(this, item, quality)
        toast(getString(R.string.download_queued))
    }

    // ---------- Moment fort (passage le plus revisionné) ----------

    private fun fmtClip(range: IntRange): String =
        "${fmtHms(range.first * 1000L)} → ${fmtHms(range.last * 1000L)}"

    /** Extrait uniquement le passage le plus revu d'une vidéo (sans tout télécharger). */
    private fun downloadHighlight(item: VideoItem) {
        setBusy(true, R.string.an_highlight_finding)
        lifecycleScope.launch {
            val range = runCatching { Engine.highlight(this@MainActivity, item.url) }.getOrNull()
            setBusy(false)
            if (range == null) { toast(getString(R.string.an_no_highlight)); return@launch }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(getString(R.string.an_highlight_range, fmtClip(range)))
                .setItems(QUALITIES.map { it.label }.toTypedArray()) { _, index ->
                    Downloads.start(this@MainActivity, item, QUALITIES[index], range.first, range.last)
                    toast(getString(R.string.an_highlight_queued, fmtClip(range)))
                }
                .show()
        }
    }

    /** Extraction en masse des moments forts de la sélection, à une qualité donnée. */
    private fun downloadSelectionHighlights(items: List<VideoItem>, quality: Quality) {
        setBusy(true, R.string.an_highlight_finding)
        lifecycleScope.launch {
            var queued = 0
            var missing = 0
            for ((i, it) in items.withIndex()) {
                ui.searchStatus.isVisible = true
                ui.searchStatus.text = getString(R.string.an_highlights_finding, i + 1, items.size)
                val range = runCatching { Engine.highlight(this@MainActivity, it.url) }.getOrNull()
                if (range != null) { Downloads.start(this@MainActivity, it, quality, range.first, range.last); queued++ }
                else missing++
            }
            setBusy(false)
            toast(getString(R.string.an_highlights_queued, queued, missing))
        }
    }

    /** Télécharge en lot les dernières vidéos de la chaîne d'une vidéo. */
    private fun downloadChannel(item: VideoItem) {
        val url = Engine.channelFromVideo(item)?.url ?: item.channelUrl
        if (url == null) { openChannelFromVideo(item); return }
        toast(getString(R.string.dl_fetching_channel))
        lifecycleScope.launch {
            val vids = runCatching { Engine.channelVideos(this@MainActivity, url, 30) }
                .getOrDefault(emptyList())
            if (vids.isEmpty()) { toast(getString(R.string.no_results)); return@launch }
            askBatchQuality(vids)
        }
    }

    /** Choix de qualité puis mise en file d'un lot complet. */
    private fun askBatchQuality(items: List<VideoItem>) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dl_batch_count, items.size))
            .setItems(QUALITIES.map { it.label }.toTypedArray()) { _, index ->
                Downloads.startBatch(this, items, QUALITIES[index])
                toast(getString(R.string.dl_batch_queued, items.size))
            }
            .show()
    }

    /** Reflète la file de téléchargements sur la carte partagée (+ feuille détaillée). */
    private fun renderDownloads() {
        val active = Downloads.active()
        val total = Downloads.totalCount()
        val done = Downloads.doneCount()
        val errors = Downloads.jobs().count { it.status == Downloads.Status.ERROR }
        val busy = Downloads.hasActive()
        val visible = total > 0
        ui.downloadCard.isVisible = visible
        // Rafraîchit la feuille ouverte, le cas échéant.
        dlQueueAdapter?.submit(Downloads.jobs())
        dlQueueEmptyView?.isVisible = Downloads.jobs().isEmpty()
        if (!visible) { wasDownloading = false; return }

        val remaining = total - done - errors
        ui.downloadLabel.text = active?.item?.title ?: getString(R.string.dl_all_done)
        val running = active?.status == Downloads.Status.RUNNING
        ui.downloadProgress.isVisible = running
        if (running) {
            val p = active?.percent ?: -1
            ui.downloadProgress.isIndeterminate = p < 0
            if (p >= 0) ui.downloadProgress.setProgressCompat(p, true)
        }
        ui.downloadStatus.text = when {
            busy && remaining > 1 -> getString(R.string.dl_status_summary, done + 1, total)
            running && (active?.percent ?: -1) >= 0 -> getString(R.string.downloading, active!!.percent)
            busy -> getString(R.string.processing)
            errors > 0 -> getString(R.string.dl_status_summary_errors, done, total)
            else -> getString(R.string.dl_status_all_done, done)
        }

        // Rebond de célébration à la fin de toute la file.
        if (wasDownloading && !busy && errors == 0) {
            ui.downloadCard.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
            ui.downloadCard.animate().scaleX(1.03f).scaleY(1.03f).setDuration(130)
                .withEndAction {
                    ui.downloadCard.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                }.start()
        }
        wasDownloading = busy
    }

    private var dlQueueAdapter: DownloadAdapter? = null
    private var dlQueueEmptyView: View? = null

    /** Feuille détaillée de la file : réessayer / retirer, effacer les terminés. */
    private fun openDownloadQueue() {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_downloads, null)
        sheet.setContentView(view)
        val list = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.dlQueueList)
        val empty = view.findViewById<View>(R.id.dlQueueEmpty)
        val adapter = DownloadAdapter(
            onRetry = { Downloads.retry(this, it.id) },
            onRemove = { Downloads.remove(this, it.id) },
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        adapter.submit(Downloads.jobs())
        empty.isVisible = Downloads.jobs().isEmpty()
        view.findViewById<View>(R.id.dlClearFinished).setOnClickListener {
            Downloads.clearFinished(this)
        }
        dlQueueAdapter = adapter
        dlQueueEmptyView = empty
        sheet.setOnDismissListener { dlQueueAdapter = null; dlQueueEmptyView = null }
        sheet.show()
    }

    // ---------- HISTORIQUE ----------

    private fun wireHistory() {
        ui.clearHistory.setOnClickListener { confirmClearHistory() }
        ui.libraryPlayAll.setOnClickListener { hapticTick(); playAllOffline() }
        ui.librarySearch.addTextChangedListener { if (ui.historyPane.isVisible) refreshHistory() }
    }

    /** Bibliothèque hors-ligne : filtre par type (Tout / Musique / Vidéos) + recherche. */
    /** Liste des téléchargements actuellement affichés (filtre + recherche). */
    private var libraryShown: List<HistoryEntry> = emptyList()

    private fun refreshHistory() {
        val all = History.all(this)
        val types = listOf("Tout", "Musique", "Vidéos")
        if (sourceFilter !in types) sourceFilter = "Tout"
        if (ui.sourceChips.childCount != types.size) {
            ui.sourceChips.removeAllViews()
            types.forEach { t ->
                ui.sourceChips.addView(Chip(this).apply {
                    text = t; isCheckable = true; isChecked = t == sourceFilter
                    setOnClickListener { sourceFilter = t; refreshHistory() }
                })
            }
        }
        val q = ui.librarySearch.text?.toString()?.trim()?.lowercase().orEmpty()
        val filtered = all
            .filter {
                when (sourceFilter) {
                    "Musique" -> it.audio
                    "Vidéos" -> !it.audio
                    else -> true
                }
            }
            .filter { q.isEmpty() || it.title.lowercase().contains(q) }
        libraryShown = filtered
        history.submit(filtered)
        ui.historyEmpty.isVisible = filtered.isEmpty()
        ui.historyList.isVisible = filtered.isNotEmpty()
        ui.clearHistory.isVisible = all.isNotEmpty()
        ui.libraryPlayAll.isVisible = filtered.any { it.audio }
    }

    /**
     * Lecture **hors-ligne** dans nos lecteurs. Pour l'audio, la file = uniquement
     * les morceaux **actuellement affichés** (filtre + recherche) → on ne mélange
     * plus toute la bibliothèque.
     */
    private fun openFile(entry: HistoryEntry) {
        if (entry.audio) {
            val audio = libraryShown.filter { it.audio }.ifEmpty { History.all(this).filter { it.audio } }
            val tracks = audio.map(OfflineLibrary::toVideoItem)
            val index = audio.indexOfFirst { it.id == entry.id }.coerceAtLeast(0)
            openMusic(tracks, index)
        } else {
            startActivity(Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_URL, entry.uri)
                putExtra(PlayerActivity.EXTRA_TITLE, entry.title)
                putExtra(PlayerActivity.EXTRA_DIRECT, true)
            })
            overridePendingTransition(R.anim.slide_in_up, R.anim.hold)
        }
    }

    /** Écoute tous les morceaux audio hors-ligne d'affilée. */
    private fun playAllOffline() {
        val tracks = OfflineLibrary.audioTracks(this)
        if (tracks.isEmpty()) toast(getString(R.string.no_results)) else openMusic(tracks, 0)
    }

    /**
     * Bouton « Écouter mes téléchargements » : propose de tout écouter OU de
     * choisir une playlist précise (pour ne pas tout mélanger).
     */
    private fun playDownloads() {
        val playlists = Favorites.playlistNames(this)
        if (playlists.isEmpty()) { playAllOffline(); return }
        val options = listOf(getString(R.string.play_all_downloads)) + playlists
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.music_play_downloads)
            .setItems(options.toTypedArray()) { _, i ->
                if (i == 0) playAllOffline() else playPlaylist(playlists[i - 1])
            }
            .show()
    }

    /** Menu long-press d'un téléchargement : playlist, renommer, partager, supprimer. */
    private fun showLibraryMenu(entry: HistoryEntry, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(R.string.add_to_playlist).setOnMenuItemClickListener {
                choosePlaylist(OfflineLibrary.toVideoItem(entry)); true
            }
            menu.add(R.string.lib_rename).setOnMenuItemClickListener { promptRename(entry); true }
            menu.add(R.string.lib_share).setOnMenuItemClickListener { shareEntry(entry); true }
            menu.add(R.string.delete).setOnMenuItemClickListener { confirmDeleteEntry(entry); true }
            show()
        }
    }

    private fun promptRename(entry: HistoryEntry) {
        val input = AppCompatEditText(this).apply {
            setText(entry.title); setSingleLine(true)
            setSelection(text?.length ?: 0)
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.lib_rename)
            .setView(android.widget.FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) })
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                if (History.rename(this, entry.id, input.text?.toString().orEmpty())) refreshHistory()
            }
            .show()
    }

    private fun shareEntry(entry: HistoryEntry) {
        runCatching {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = if (entry.audio) "audio/*" else "video/*"
                        putExtra(Intent.EXTRA_STREAM, Uri.parse(entry.uri))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    getString(R.string.lib_share),
                )
            )
        }.onFailure { toast(getString(R.string.cannot_open)) }
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
