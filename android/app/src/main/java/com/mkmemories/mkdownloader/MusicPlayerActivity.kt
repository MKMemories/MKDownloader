package com.mkmemories.mkdownloader

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import coil.load
import com.mkmemories.mkdownloader.databinding.ActivityMusicPlayerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** File d'attente musicale partagée avec l'écran de lecture. */
object MusicQueue {
    var tracks: List<VideoItem> = emptyList()
    var startIndex: Int = 0
}

/** Lecteur audio premium : écoute en ligne d'une playlist avec pochette et file d'attente. */
@androidx.annotation.OptIn(UnstableApi::class)
class MusicPlayerActivity : AppCompatActivity() {

    private lateinit var ui: ActivityMusicPlayerBinding
    private var player: ExoPlayer? = null
    private var tracks: List<VideoItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityMusicPlayerBinding.inflate(layoutInflater)
        setContentView(ui.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        tracks = MusicQueue.tracks
        if (tracks.isEmpty()) { finish(); return }
        ui.closeButton.setOnClickListener { finish() }
        renderTrack(MusicQueue.startIndex.coerceIn(tracks.indices))
        resolveAndPlay()
    }

    private fun renderTrack(index: Int) {
        val t = tracks.getOrNull(index) ?: return
        ui.musicTitle.text = t.title
        ui.musicArtist.text = t.uploader ?: t.channelName ?: ""
        if (!t.thumbnail.isNullOrEmpty()) ui.musicArt.load(t.thumbnail)
    }

    private fun resolveAndPlay() {
        ui.musicLoading.isVisible = true
        lifecycleScope.launch {
            val resolved = try {
                withContext(Dispatchers.IO) {
                    tracks.map { t -> async { t to Engine.audioStreamUrl(this@MusicPlayerActivity, t.url) } }.awaitAll()
                }
            } catch (e: Exception) {
                toast(cleanError(e)); finish(); return@launch
            }
            val playable = resolved.filter { it.second != null }
            if (playable.isEmpty()) { toast(getString(R.string.no_audio)); finish(); return@launch }

            val httpFactory = DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android 14)")
                .setAllowCrossProtocolRedirects(true)
            val factory = DefaultMediaSourceFactory(httpFactory)

            player = ExoPlayer.Builder(this@MusicPlayerActivity)
                .setMediaSourceFactory(factory)
                .build().also { exo ->
                    ui.playerController.setPlayer(exo)
                    playable.forEach { (track, streamUrl) ->
                        exo.addMediaItem(
                            MediaItem.Builder()
                                .setUri(streamUrl)
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(track.title)
                                        .setArtist(track.uploader ?: track.channelName)
                                        .apply { track.thumbnail?.let { setArtworkUri(it.toUri()) } }
                                        .build()
                                )
                                .build()
                        )
                    }
                    val start = MusicQueue.startIndex.coerceIn(playable.indices)
                    exo.seekTo(start, 0)
                    exo.addListener(object : Player.Listener {
                        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                            ui.musicTitle.text = mediaItem?.mediaMetadata?.title ?: ""
                            ui.musicArtist.text = mediaItem?.mediaMetadata?.artist ?: ""
                            mediaItem?.mediaMetadata?.artworkUri?.let { ui.musicArt.load(it) }
                        }
                    })
                    exo.prepare()
                    exo.playWhenReady = true
                }
            ui.musicLoading.isVisible = false
        }
    }

    override fun onStop() {
        super.onStop()
        player?.release()
        player = null
        if (!isChangingConfigurations) finish()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
