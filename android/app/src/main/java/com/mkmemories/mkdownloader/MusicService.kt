package com.mkmemories.mkdownloader

import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Service de lecture musicale : son en arrière-plan + notification/écran
 * verrouillé, ET bibliothèque parcourable premium pour **Android Auto**.
 *
 * Résolution PARESSEUSE : les titres portent une URI « ytdlp:<lien> » et le
 * flux réel n'est extrait qu'au moment où le morceau démarre (ResolvingDataSource).
 * → démarrage instantané, pas d'attente/erreur, précédent-suivant immédiats.
 */
@UnstableApi
class MusicService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private var equalizer: Equalizer? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    // Cache nœud → morceaux (permet d'étendre un titre tapé à toute sa file).
    private val browseCache = HashMap<String, List<VideoItem>>()

    // Anti-blocage : un titre premium/indisponible reste parfois « en tampon »
    // à 0:00 sans erreur. Au bout de ~15 s on saute au suivant.
    private val watchdog = Handler(Looper.getMainLooper())
    private var stallTicks = 0
    private val watchdogRun = object : Runnable {
        override fun run() {
            val p = session?.player
            if (p != null && p.playWhenReady &&
                p.playbackState == Player.STATE_BUFFERING && p.currentPosition == 0L
            ) {
                stallTicks++
                if (stallTicks >= 5 && p.hasNextMediaItem()) {
                    p.seekToNextMediaItem(); p.prepare(); p.play(); stallTicks = 0
                }
            } else {
                stallTicks = 0
            }
            watchdog.postDelayed(this, 3000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 120_000, 2_500, 5_000)
            .build()
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 14)")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(30_000)

        // Résout « ytdlp:<lien> » → flux audio réel, à la volée (thread de lecture).
        val resolver = ResolvingDataSource.Resolver { dataSpec ->
            if (dataSpec.uri.scheme == SCHEME) {
                val real = dataSpec.uri.schemeSpecificPart
                val stream = runCatching {
                    runBlocking { Engine.audioStreamUrl(this@MusicService, real) }
                }.getOrNull()
                if (stream != null) {
                    dataSpec.withUri(Uri.parse(stream))
                } else {
                    // Échec de résolution : on lève une erreur claire → le lecteur
                    // passe au titre suivant (voir onPlayerError) au lieu de bloquer.
                    Logs.w("play", "titre non résolu → suivant : $real")
                    throw java.io.IOException("Titre indisponible : $real")
                }
            } else {
                dataSpec
            }
        }
        // DefaultDataSource gère http(s) ET les fichiers locaux (content://) pour le hors-ligne.
        val baseFactory = androidx.media3.datasource.DefaultDataSource.Factory(this, httpFactory)
        val dataSourceFactory = ResolvingDataSource.Factory(baseFactory, resolver)

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        // Un titre indisponible (premium/bloqué) ne fige pas la lecture : on saute.
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Logs.e("play", "erreur lecture (${error.errorCodeName})", error)
                if (player.hasNextMediaItem()) {
                    Logs.d("play", "→ passage au titre suivant")
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                } else {
                    Logs.w("play", "aucun titre suivant — lecture arrêtée")
                }
            }

            // Mémorise chaque écoute pour « Reprendre / Récents ».
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaItem ?: return
                Logs.d("play", "lecture : ${mediaItem.mediaMetadata.title} (${mediaItem.requestMetadata.mediaUri})")
                runCatching { Recents.add(this@MusicService, videoFromMediaItem(mediaItem)) }
            }
        })
        equalizer = runCatching {
            val sid = player.audioSessionId
            if (sid != C.AUDIO_SESSION_ID_UNSET) Equalizer(0, sid).apply { enabled = false } else null
        }.getOrNull()
        session = MediaLibrarySession.Builder(this, player, LibraryCallback()).build()
        watchdog.post(watchdogRun)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        watchdog.removeCallbacks(watchdogRun)
        runCatching { equalizer?.release() }
        equalizer = null
        session?.run { player.release(); release() }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    // ---------- Bibliothèque parcourable (Android Auto) ----------

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(SessionCommand(CMD_FAV, Bundle.EMPTY))
                .add(SessionCommand(CMD_EQ_LIST, Bundle.EMPTY))
                .add(SessionCommand(CMD_EQ_SET, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setCustomLayout(ImmutableList.of(favButton()))
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_FAV -> {
                    session.player.currentMediaItem?.let {
                        Favorites.toggleVideo(this@MusicService, videoFromMediaItem(it))
                        browseCache.remove(NODE_FAVORITES)
                        this@MusicService.session?.notifyChildrenChanged(
                            NODE_FAVORITES, Favorites.videos(this@MusicService).size, null
                        )
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CMD_EQ_LIST -> {
                    val eq = equalizer
                    val n = eq?.numberOfPresets?.toInt() ?: 0
                    val names = Array(n) { eq!!.getPresetName(it.toShort()) }
                    val extras = Bundle().apply {
                        putStringArray("presets", names)
                        putInt("current", if (eq?.enabled == true) eq.currentPreset.toInt() else -1)
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, extras))
                }
                CMD_EQ_SET -> {
                    val preset = args.getInt("preset", -1)
                    runCatching {
                        equalizer?.let {
                            if (preset < 0) it.enabled = false
                            else { it.usePreset(preset.toShort()); it.enabled = true }
                        }
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(
                LibraryResult.ofItem(
                    browsable(ROOT, this@MusicService.getString(R.string.app_name)),
                    listParams(),
                )
            )

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val ctx = this@MusicService
            fun okItems(list: List<MediaItem>) =
                Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(list), listParams()))
            fun okGrid(list: List<MediaItem>) =
                Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(list), gridParams()))
            return when {
                parentId == ROOT -> { ensureCuratedLoaded(); okGrid(rootCards(ctx)) }
                parentId == CarMusic.MIX -> okItems(cacheAndBuild(parentId, mixTracks()))
                CarMusic.catById(parentId) != null -> curatedChildren(CarMusic.catById(parentId)!!)
                parentId == NODE_RADIOS -> okItems(cacheAndBuild(parentId, Radio.stations()))
                parentId == NODE_RECENT -> okItems(cacheAndBuild(parentId, Recents.list(ctx)))
                parentId == NODE_DOWNLOADS -> okItems(cacheAndBuild(parentId, OfflineLibrary.audioTracks(ctx)))
                parentId == NODE_PLAYLISTS ->
                    okGrid(Favorites.playlistNames(ctx).map { name ->
                        browsableCard(PL_PREFIX + name, name, randomArt(Favorites.tracksOf(ctx, name)))
                    })
                parentId == NODE_FAVORITES -> okItems(cacheAndBuild(parentId, Favorites.videos(ctx)))
                parentId.startsWith(PL_PREFIX) ->
                    okItems(cacheAndBuild(parentId, Favorites.tracksOf(ctx, parentId.removePrefix(PL_PREFIX))))
                else -> okItems(emptyList())
            }
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            val future = SettableFuture.create<LibraryResult<Void>>()
            scope.launch {
                val tracks = runCatching {
                    Engine.searchMusic(this@MusicService, query, 30)
                }.getOrDefault(emptyList())
                browseCache[NODE_SEARCH] = tracks
                session.notifySearchResultChanged(browser, query, tracks.size, params)
                future.set(LibraryResult.ofVoid())
            }
            return future
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val tracks = browseCache[NODE_SEARCH] ?: emptyList()
            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(tracks.map { playable(it, NODE_SEARCH) }), listParams())
            )
        }

        /**
         * Le téléphone fournit des URI http(s) déjà résolues → tel quel. La voiture
         * ne tape qu'un titre (URI « ytdlp: ») → on l'étend INSTANTANÉMENT à toute
         * sa liste sœur (aucune extraction ici : elle est paresseuse) et on démarre
         * au bon index. Résultat : pas d'attente, précédent/suivant OK.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val first = mediaItems.firstOrNull()

            // Commande vocale (« OK Google, mets X sur MKDownloader ») : l'Assistant
            // envoie une requête de recherche sans URI → on résout via la recherche.
            val voiceQuery = first?.requestMetadata?.searchQuery?.toString()?.trim()
            if (!voiceQuery.isNullOrEmpty() && first?.localConfiguration == null) {
                val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
                scope.launch {
                    val tracks = runCatching {
                        Engine.searchMusic(this@MusicService, voiceQuery, 30)
                    }.getOrDefault(emptyList())
                    if (tracks.isEmpty()) {
                        future.set(MediaSession.MediaItemsWithStartPosition(mediaItems, 0, startPositionMs))
                    } else {
                        val built = cacheAndBuild(NODE_VOICE, tracks)
                        future.set(MediaSession.MediaItemsWithStartPosition(built, 0, 0))
                    }
                }
                return future
            }

            // Items « voiture » : identifiés par leur mediaId de navigation → on
            // reconstruit la liste sœur depuis le cache (radios incluses). Les items
            // « téléphone » (déjà porteurs de l'URL réelle) passent tels quels.
            val (parent, url) = parseId(first?.mediaId ?: "")
            val list = if (parent.isNotEmpty()) browseCache[parent] else null
            if (list == null) {
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
                )
            }
            val items = list.map { playable(it, parent) }
            val start = list.indexOfFirst { it.url == url }.coerceAtLeast(0)
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(items, start, startPositionMs)
            )
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems)
    }

    private fun cacheAndBuild(parentId: String, tracks: List<VideoItem>): List<MediaItem> {
        browseCache[parentId] = tracks
        return tracks.map { playable(it, parentId) }
    }

    // ---------- Contenu curé Android Auto (cartes + hero aléatoire) ----------

    private val loadingCats = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Pochette « hero » aléatoire pour une carte (change à chaque affichage). */
    private fun randomArt(tracks: List<VideoItem>): String? =
        tracks.mapNotNull { it.thumbnail?.takeIf { u -> u.isNotEmpty() } }.randomOrNull()

    /** Le « Mix du moment » = tous les artistes curés, mélangés (jamais la même liste). */
    private fun mixTracks(): List<VideoItem> =
        CarMusic.ARTIST_CATS.flatMap { browseCache[it.id].orEmpty() }
            .distinctBy { it.url }.shuffled()

    /** Cartes de l'accueil voiture : catégories curées puis nœuds système. */
    private fun rootCards(ctx: android.content.Context): List<MediaItem> {
        val cards = CarMusic.CATS.map { cat ->
            val pool = if (cat.id == CarMusic.MIX) mixTracks() else browseCache[cat.id].orEmpty()
            browsableCard(cat.id, cat.label, randomArt(pool))
        }.toMutableList()
        cards += browsableCard(NODE_RADIOS, ctx.getString(R.string.music_radios), null)
        cards += browsableCard(NODE_RECENT, ctx.getString(R.string.home_resume), randomArt(Recents.list(ctx)))
        cards += browsableCard(NODE_DOWNLOADS, ctx.getString(R.string.tab_library), null)
        cards += browsableCard(NODE_PLAYLISTS, ctx.getString(R.string.my_playlists), null)
        cards += browsableCard(NODE_FAVORITES, ctx.getString(R.string.fav_videos), randomArt(Favorites.videos(ctx)))
        return cards
    }

    /** Précharge les catégories curées en arrière-plan puis rafraîchit les cartes. */
    private fun ensureCuratedLoaded() {
        CarMusic.ARTIST_CATS.forEach { cat ->
            if (browseCache[cat.id] == null && loadingCats.add(cat.id)) {
                scope.launch {
                    val tracks = runCatching {
                        if (cat.isPlaylist) Engine.importPlaylist(this@MusicService, cat.query, 100).second
                        else Engine.searchMusic(this@MusicService, cat.query, 30)
                    }.getOrDefault(emptyList())
                    if (tracks.isNotEmpty()) browseCache[cat.id] = tracks
                    loadingCats.remove(cat.id)
                    // Les opérations de session doivent tourner sur le thread principal.
                    watchdog.post {
                        session?.notifyChildrenChanged(ROOT, CarMusic.CATS.size + 5, null)
                        session?.notifyChildrenChanged(cat.id, tracks.size, null)
                        session?.notifyChildrenChanged(CarMusic.MIX, mixTracks().size, null)
                    }
                }
            }
        }
    }

    /** Enfants d'une catégorie curée : cache ou chargement à la volée. */
    private fun curatedChildren(cat: CarMusic.Cat): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        browseCache[cat.id]?.let {
            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(it.map { t -> playable(t, cat.id) }), listParams())
            )
        }
        val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
        scope.launch {
            val tracks = runCatching {
                if (cat.isPlaylist) Engine.importPlaylist(this@MusicService, cat.query, 100).second
                else Engine.searchMusic(this@MusicService, cat.query, 30)
            }.getOrDefault(emptyList())
            browseCache[cat.id] = tracks
            future.set(
                LibraryResult.ofItemList(ImmutableList.copyOf(tracks.map { playable(it, cat.id) }), listParams())
            )
        }
        return future
    }

    /** Carte (dossier) avec pochette → grille premium au lieu d'une ligne de texte. */
    private fun browsableCard(id: String, title: String, art: String?): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .apply { if (!art.isNullOrEmpty()) setArtworkUri(art.toUri()) }
                    .setExtras(contentStyleExtras())
                    .build()
            )
            .build()

    /** Listes de titres : affichage LISTE compacte avec pochette. */
    private fun listParams(): LibraryParams =
        LibraryParams.Builder().setExtras(contentStyleExtras()).build()

    /** Accueil : GRILLE de cartes (pochettes) au lieu d'un menu texte. */
    private fun gridParams(): LibraryParams =
        LibraryParams.Builder().setExtras(Bundle().apply {
            putBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED", true)
            putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 2) // GRILLE (cartes)
            putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1)  // liste
        }).build()

    private fun contentStyleExtras() = Bundle().apply {
        putBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED", true)
        putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 2) // GRILLE
        putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1)  // liste
    }

    private fun favButton(): CommandButton =
        CommandButton.Builder()
            .setDisplayName(getString(R.string.favorite))
            .setIconResId(android.R.drawable.btn_star_big_on)
            .setSessionCommand(SessionCommand(CMD_FAV, Bundle.EMPTY))
            .build()

    private fun browsable(id: String, title: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .setExtras(contentStyleExtras())
                    .build()
            )
            .build()

    private fun playable(t: VideoItem, parent: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(trackId(parent, t.url))
            // Radio / hors-ligne : flux direct. Sinon « ytdlp:… » résolu paresseusement.
            .setUri(if (Playback.isDirect(t.url)) Playback.directUri(t.url) else SCHEME + ":" + t.url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(t.title)
                    .setArtist(t.uploader ?: t.channelName)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .apply { t.thumbnail?.let { setArtworkUri(it.toUri()) } }
                    .build()
            )
            .build()

    private fun videoFromMediaItem(mi: MediaItem): VideoItem {
        val md = mi.mediaMetadata
        val url = parseId(mi.mediaId).second.ifEmpty { mi.mediaId }
        return VideoItem(
            url = url,
            title = md.title?.toString() ?: "Titre",
            uploader = md.artist?.toString(),
            durationSec = 0,
            thumbnail = md.artworkUri?.toString(),
            channelName = md.artist?.toString(),
        )
    }

    private fun trackId(parent: String, url: String) = "t$SEP$parent$SEP$url"

    private fun parseId(id: String): Pair<String, String> {
        if (id.startsWith("t$SEP")) {
            val parts = id.split(SEP)
            if (parts.size >= 3) return parts[1] to parts.subList(2, parts.size).joinToString(SEP)
        }
        return "" to id
    }

    companion object {
        private const val ROOT = "root"
        private const val NODE_PLAYLISTS = "playlists"
        private const val NODE_FAVORITES = "favorites"
        private const val NODE_RADIOS = "radios"
        private const val NODE_DOWNLOADS = "downloads"
        private const val NODE_RECENT = "recent"
        private const val NODE_VOICE = "voice"
        private const val NODE_SEARCH = "search"
        private const val PL_PREFIX = "pl:"
        private const val CMD_FAV = "com.mkmemories.mkdownloader.FAV"
        const val CMD_EQ_LIST = "com.mkmemories.mkdownloader.EQ_LIST"
        const val CMD_EQ_SET = "com.mkmemories.mkdownloader.EQ_SET"
        private const val SEP = "::mk::"
        private const val SCHEME = "ytdlp"
    }
}
