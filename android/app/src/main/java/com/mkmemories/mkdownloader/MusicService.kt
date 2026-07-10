package com.mkmemories.mkdownloader

import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Service de lecture musicale : son en arrière-plan + notification/écran
 * verrouillé, ET bibliothèque parcourable premium pour **Android Auto** —
 * tendances France/US, playlists, favoris, recherche, file d'attente
 * (précédent/suivant), artwork, et bouton « favori » sur l'écran voiture.
 */
@UnstableApi
class MusicService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    // Cache nom-de-liste → morceaux (permet d'étendre un titre tapé à sa file).
    private val browseCache = HashMap<String, List<VideoItem>>()

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

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
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
        // Un titre indisponible (premium/bloqué) ne doit pas figer la lecture :
        // on saute automatiquement au suivant.
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                }
            }
        })
        session = MediaLibrarySession.Builder(this, player, LibraryCallback()).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
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
            if (customCommand.customAction == CMD_FAV) {
                session.player.currentMediaItem?.let {
                    Favorites.toggleVideo(this@MusicService, videoFromMediaItem(it))
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            // Styles de contenu premium : grilles pour les dossiers, listes pour les titres.
            val extras = Bundle().apply {
                putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 2) // grille
                putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1)  // liste
            }
            val rootParams = LibraryParams.Builder().setExtras(extras).build()
            return Futures.immediateFuture(
                LibraryResult.ofItem(browsable(ROOT, this@MusicService.getString(R.string.app_name)), rootParams)
            )
        }

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
                Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(list), params))
            return when {
                parentId == ROOT -> ok(
                    listOf(
                        browsable(NODE_TREND_FR, "Populaire · France"),
                        browsable(NODE_TREND_US, "Populaire · US"),
                        browsable(NODE_PLAYLISTS, ctx.getString(R.string.my_playlists)),
                        browsable(NODE_FAVORITES, ctx.getString(R.string.fav_videos)),
                    )
                )
                parentId == NODE_PLAYLISTS ->
                    ok(Favorites.playlistNames(ctx).map { browsable(PL_PREFIX + it, it) })
                parentId == NODE_FAVORITES -> ok(cacheAndBuild(parentId, Favorites.videos(ctx)))
                parentId.startsWith(PL_PREFIX) ->
                    ok(cacheAndBuild(parentId, Favorites.tracksOf(ctx, parentId.removePrefix(PL_PREFIX))))
                parentId == NODE_TREND_FR -> asyncChildren(parentId, Q_FR, params)
                parentId == NODE_TREND_US -> asyncChildren(parentId, Q_US, params)
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
                LibraryResult.ofItemList(ImmutableList.copyOf(tracks.map { playable(it, NODE_SEARCH) }), params)
            )
        }

        /**
         * Le téléphone fournit une file déjà résolue (URI présentes) → tel quel.
         * La voiture ne tape qu'un titre → on l'étend à sa liste sœur, on résout
         * tous les flux (en sautant les indisponibles), et on démarre au bon index
         * pour que précédent/suivant fonctionnent.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            if (mediaItems.isNotEmpty() && mediaItems.all { it.localConfiguration != null }) {
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
                )
            }
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            scope.launch {
                val tapped = mediaItems.firstOrNull()
                val (parent, url) = parseId(tapped?.mediaId ?: "")
                val list = browseCache[parent]
                    ?: listOfNotNull(tapped?.let { videoFromMediaItem(it) })
                val resolved = withContext(Dispatchers.IO) {
                    list.map { t -> async { t to Engine.audioStreamUrl(this@MusicService, t.url) } }.awaitAll()
                }.mapNotNull { (t, s) -> if (s != null) t to s else null }
                if (resolved.isEmpty()) {
                    future.set(MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0))
                    return@launch
                }
                val items = resolved.map { (t, s) -> playableResolved(t, parent, s) }
                val start = resolved.indexOfFirst { it.first.url == url }.coerceAtLeast(0)
                future.set(MediaSession.MediaItemsWithStartPosition(items, start, startPositionMs))
            }
            return future
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            if (mediaItems.all { it.localConfiguration != null }) {
                return Futures.immediateFuture(mediaItems)
            }
            val future = SettableFuture.create<MutableList<MediaItem>>()
            scope.launch {
                val resolved = withContext(Dispatchers.IO) {
                    mediaItems.map { item ->
                        async {
                            if (item.localConfiguration != null) item
                            else {
                                val (_, url) = parseId(item.mediaId)
                                val s = runCatching { Engine.audioStreamUrl(this@MusicService, url) }.getOrNull()
                                if (s != null) item.buildUpon().setUri(s).build() else null
                            }
                        }
                    }.awaitAll()
                }.filterNotNull().toMutableList()
                future.set(resolved)
            }
            return future
        }
    }

    private fun cacheAndBuild(parentId: String, tracks: List<VideoItem>): List<MediaItem> {
        browseCache[parentId] = tracks
        return tracks.map { playable(it, parentId) }
    }

    private fun asyncChildren(
        parentId: String,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        browseCache[parentId]?.let {
            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(it.map { t -> playable(t, parentId) }), params)
            )
        }
        val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
        scope.launch {
            val tracks = runCatching {
                Engine.search(this@MusicService, query, DateFilter.ANY, 30)
            }.getOrDefault(emptyList())
            browseCache[parentId] = tracks
            future.set(
                LibraryResult.ofItemList(ImmutableList.copyOf(tracks.map { playable(it, parentId) }), params)
            )
        }
        return future
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
                    .build()
            )
            .build()

    private fun playable(t: VideoItem, parent: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(trackId(parent, t.url))
            .setMediaMetadata(trackMeta(t))
            .build()

    private fun playableResolved(t: VideoItem, parent: String, stream: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(trackId(parent, t.url))
            .setUri(stream)
            .setMediaMetadata(trackMeta(t))
            .build()

    private fun trackMeta(t: VideoItem): MediaMetadata =
        MediaMetadata.Builder()
            .setTitle(t.title)
            .setArtist(t.uploader ?: t.channelName)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .apply { t.thumbnail?.let { setArtworkUri(it.toUri()) } }
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
        private const val NODE_SEARCH = "search"
        private const val PL_PREFIX = "pl:"
        private const val CMD_FAV = "com.mkmemories.mkdownloader.FAV"
        private const val SEP = "::mk::"
        private const val Q_FR = "top chansons françaises du moment 2026"
        private const val Q_US = "top songs usa billboard hits 2026"
    }
}
