package com.mkmemories.mkdownloader

import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Service de lecture musicale : garde le son en arrière-plan, affiche une
 * notification/contrôles sur l'écran verrouillé, ET expose une bibliothèque
 * parcourable (Playlists · Favoris) pour **Android Auto** — l'écran de la
 * voiture navigue dans l'arbre et lance un titre sans toucher le téléphone.
 */
@UnstableApi
class MusicService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        // Gros tampon (30 s → 2 min) : absorbe les irrégularités réseau.
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

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(
                LibraryResult.ofItem(browsable(ROOT, this@MusicService.getString(R.string.app_name)), params)
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
            val items: List<MediaItem> = when {
                parentId == ROOT -> listOf(
                    browsable(NODE_PLAYLISTS, ctx.getString(R.string.my_playlists)),
                    browsable(NODE_FAVORITES, ctx.getString(R.string.fav_videos)),
                )
                parentId == NODE_PLAYLISTS ->
                    Favorites.playlistNames(ctx).map { name -> browsable(PL_PREFIX + name, name) }
                parentId == NODE_FAVORITES ->
                    Favorites.videos(ctx).map(::playable)
                parentId.startsWith(PL_PREFIX) ->
                    Favorites.tracksOf(ctx, parentId.removePrefix(PL_PREFIX)).map(::playable)
                else -> emptyList()
            }
            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
            )
        }

        /**
         * Résolution du flux audio au moment de la lecture. Le téléphone fournit
         * déjà une URI (localConfiguration ≠ null) → on ne touche à rien. La
         * voiture ne fournit qu'un identifiant → on résout via yt-dlp.
         */
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
                val resolved = mediaItems.map { item ->
                    if (item.localConfiguration != null) return@map item
                    val stream = runCatching {
                        Engine.audioStreamUrl(this@MusicService, item.mediaId)
                    }.getOrNull()
                    if (stream != null) item.buildUpon().setUri(stream).build() else item
                }.toMutableList()
                future.set(resolved)
            }
            return future
        }
    }

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

    private fun playable(t: VideoItem): MediaItem =
        MediaItem.Builder()
            .setMediaId(t.url)
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

    companion object {
        private const val ROOT = "root"
        private const val NODE_PLAYLISTS = "playlists"
        private const val NODE_FAVORITES = "favorites"
        private const val PL_PREFIX = "pl:"
    }
}
