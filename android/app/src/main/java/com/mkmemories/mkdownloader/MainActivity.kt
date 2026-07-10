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

        ui.versionLabel.text = runCatching {
            "MKDownloader v" + packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrDefault("MKDownloader")

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

        Downloads.restore(this)          // recharge une file interrompue
        requestNotifPermission()
        handleShareIntent(intent)
        if (intent?.action != Intent.ACTION_SEND) loadHomeFeed()
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

    /** Fiche film : synopsis + note, avec bande-annonce et recherche. */
    private fun openMovie(m: Tmdb.Movie) {
        val note = if (m.rating > 0) "★ %.1f".format(m.rating) else null
        val header = listOfNotNull(m.year, note).joinToString(" · ")
        val msg = (if (header.isNotEmpty()) "$header\n\n" else "") +
            m.overview.ifBlank { getString(R.string.tmdb_no_synopsis) }
        MaterialAlertDialogBuilder(this)
            .setTitle(m.title)
            .setMessage(msg)
            .setPositiveButton(R.string.tmdb_trailer) { _, _ -> playTrailer(m) }
            .setNeutralButton(R.string.tmdb_search) { _, _ -> searchFilm(m.title) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun playTrailer(m: Tmdb.Movie) {
        toast(getString(R.string.tmdb_loading_trailer))
        lifecycleScope.launch {
            val key = runCatching { Tmdb.trailerYoutubeKey(this@MainActivity, m.id) }.getOrNull()
            if (key != null) {
                openPlayer(VideoItem("https://www.youtube.com/watch?v=$key", "${m.title} — bande-annonce", null, 0, null))
            } else {
                searchFilm("${m.title} bande annonce VF")
            }
        }
    }

    /** Bascule vers la recherche vidéo et lance une requête. */
    private fun searchFilm(query: String) {
        ui.bottomNav.selectedItemId = R.id.nav_search
        ui.searchInput.setText(query)
        searchVideos(query)
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

    /** Menu ⋮ d'une vidéo : lecture, téléchargements, playlist, chaîne. */
    private fun showVideoMenu(item: VideoItem, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(R.string.menu_play).setOnMenuItemClickListener { openPlayer(item); true }
            menu.add(R.string.menu_listen).setOnMenuItemClickListener { openMusic(listOf(item), 0); true }
            menu.add(R.string.menu_download_video).setOnMenuItemClickListener { askQualityAndDownload(item); true }
            menu.add(R.string.menu_download_mp3).setOnMenuItemClickListener { downloadMp3(item); true }
            menu.add(R.string.menu_download_channel).setOnMenuItemClickListener { downloadChannel(item); true }
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
                    1 -> { Downloads.startBatch(this, tracks, AUDIO_QUALITY); toast(getString(R.string.dl_batch_queued, tracks.size)) }
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
        Downloads.start(this, item, quality)
        toast(getString(R.string.download_queued))
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
        history.submit(filtered)
        ui.historyEmpty.isVisible = filtered.isEmpty()
        ui.historyList.isVisible = filtered.isNotEmpty()
        ui.clearHistory.isVisible = all.isNotEmpty()
        ui.libraryPlayAll.isVisible = filtered.any { it.audio }
    }

    /** Lecture **hors-ligne** dans nos lecteurs (audio → service musical, vidéo → lecteur direct). */
    private fun openFile(entry: HistoryEntry) {
        if (entry.audio) {
            val audio = History.all(this).filter { it.audio }
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

    /** Menu long-press d'un élément : renommer / partager. */
    private fun showLibraryMenu(entry: HistoryEntry, anchor: View) {
        PopupMenu(this, anchor).apply {
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
