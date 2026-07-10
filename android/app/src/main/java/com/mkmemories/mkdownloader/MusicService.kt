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
                if (stream != null) dataSpec.withUri(Uri.parse(stream)) else dataSpec
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
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                }
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
            fun ok(list: List<MediaItem>) =
                Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(list), listParams()))
            return when {
                parentId == ROOT -> ok(
                    listOf(
                        browsable(NODE_DOWNLOADS, ctx.getString(R.string.tab_library)),
                        browsable(NODE_RADIOS, ctx.getString(R.string.music_radios)),
                        browsable(NODE_TREND_FR, "Populaire · France"),
                        browsable(NODE_TREND_US, "Populaire · US"),
                        browsable(NODE_PLAYLISTS, ctx.getString(R.string.my_playlists)),
                        browsable(NODE_FAVORITES, ctx.getString(R.string.fav_videos)),
                    )
                )
                parentId == NODE_RADIOS -> ok(cacheAndBuild(parentId, Radio.stations()))
                parentId == NODE_DOWNLOADS -> ok(cacheAndBuild(parentId, OfflineLibrary.audioTracks(ctx)))
                parentId == NODE_PLAYLISTS ->
                    ok(Favorites.playlistNames(ctx).map { browsable(PL_PREFIX + it, it) })
                parentId == NODE_FAVORITES -> ok(cacheAndBuild(parentId, Favorites.videos(ctx)))
                parentId.startsWith(PL_PREFIX) ->
                    ok(cacheAndBuild(parentId, Favorites.tracksOf(ctx, parentId.removePrefix(PL_PREFIX))))
                parentId == NODE_TREND_FR -> asyncChildren(parentId, Q_FR)
                parentId == NODE_TREND_US -> asyncChildren(parentId, Q_US)
                else -> ok(emptyList())
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

    private fun asyncChildren(
        parentId: String,
        query: String,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        browseCache[parentId]?.let {
            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(it.map { t -> playable(t, parentId) }), listParams())
            )
        }
        val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
        scope.launch {
            val tracks = runCatching {
                Engine.search(this@MusicService, query, DateFilter.ANY, 30)
            }.getOrDefault(emptyList())
            browseCache[parentId] = tracks
            future.set(
                LibraryResult.ofItemList(ImmutableList.copyOf(tracks.map { playable(t = it, parent = parentId) }), listParams())
            )
        }
        return future
    }

    /** Force un affichage en LISTE compacte (épuré, sans « gros pavés »). */
    private fun listParams(): LibraryParams =
        LibraryParams.Builder().setExtras(contentStyleExtras()).build()

    private fun contentStyleExtras() = Bundle().apply {
        putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 1) // liste
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
        private const val NODE_TREND_FR = "trend_fr"
        private const val NODE_TREND_US = "trend_us"
        private const val NODE_PLAYLISTS = "playlists"
        private const val NODE_FAVORITES = "favorites"
        private const val NODE_RADIOS = "radios"
        private const val NODE_DOWNLOADS = "downloads"
        private const val NODE_SEARCH = "search"
        private const val PL_PREFIX = "pl:"
        private const val CMD_FAV = "com.mkmemories.mkdownloader.FAV"
        const val CMD_EQ_LIST = "com.mkmemories.mkdownloader.EQ_LIST"
        const val CMD_EQ_SET = "com.mkmemories.mkdownloader.EQ_SET"
        private const val SEP = "::mk::"
        private const val SCHEME = "ytdlp"
        private const val Q_FR = "top chansons françaises du moment 2026"
        private const val Q_US = "top songs usa billboard hits 2026"
    }
}
