package com.mkmemories.mkdownloader

import android.content.ComponentName
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.request.ImageRequest
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.common.util.concurrent.ListenableFuture
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
    /** Playlist source (si lancé depuis une playlist), pour le retrait direct. */
    var playlistName: String? = null
    /** true → rouvrir le lecteur sans relancer la file (depuis le mini-lecteur). */
    var resume: Boolean = false
}

/** Écran « en lecture » premium, piloté par le service média (lecture en arrière-plan). */
@UnstableApi
class MusicPlayerActivity : AppCompatActivity() {

    private lateinit var ui: ActivityMusicPlayerBinding
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var queue: List<VideoItem> = emptyList()
    private var seeking = false

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityMusicPlayerBinding.inflate(layoutInflater)
        setContentView(ui.root)

        if (MusicQueue.tracks.isEmpty()) { finish(); return }
        val resume = MusicQueue.resume
        MusicQueue.resume = false
        requestNotificationsIfNeeded()

        ui.closeButton.setOnClickListener { finish() }
        ui.playPauseButton.setOnClickListener { togglePlay() }
        ui.nextButton.setOnClickListener { controller?.seekToNextMediaItem() }
        ui.prevButton.setOnClickListener { controller?.seekToPreviousMediaItem() }
        ui.shuffleButton.setOnClickListener { toggleShuffle() }
        ui.repeatButton.setOnClickListener { cycleRepeat() }
        ui.favButton.setOnClickListener { toggleFav() }
        ui.downloadButton.setOnClickListener { currentTrack()?.let { downloadMp3(it) } }
        ui.addPlaylistButton.setOnClickListener { togglePlaylist() }
        ui.queueButton.setOnClickListener { showQueue() }

        ui.seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar) { seeking = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                seeking = false
                controller?.let { it.seekTo((it.duration * sb.progress / 100).coerceAtLeast(0)) }
            }
        })

        renderStatic(MusicQueue.startIndex.coerceIn(MusicQueue.tracks.indices))
        connectAndPlay(resume)
    }

    private fun connectAndPlay(resume: Boolean) {
        ui.musicLoading.isVisible = true
        val token = SessionToken(this, ComponentName(this, MusicService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            controller = future.get()
            controller?.addListener(playerListener)
            val c = controller
            if (resume && c != null && c.mediaItemCount > 0) adoptExisting(c)
            else loadQueue()
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Ouverture depuis le mini-lecteur : la file joue déjà dans le service, on
     * s'y raccroche sans re-résoudre les flux ni redémarrer le morceau.
     */
    private fun adoptExisting(c: MediaController) {
        queue = (0 until c.mediaItemCount).mapNotNull { i ->
            val id = c.getMediaItemAt(i).mediaId
            MusicQueue.tracks.find { t -> t.url == id }
        }
        ui.musicLoading.isVisible = false
        ui.playPauseButton.setIconResource(
            if (c.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
        handler.post(ticker)
        renderCurrent()
    }

    private fun loadQueue() {
        lifecycleScope.launch {
            val resolved = try {
                withContext(Dispatchers.IO) {
                    MusicQueue.tracks.map { t ->
                        async { t to Engine.audioStreamUrl(this@MusicPlayerActivity, t.url) }
                    }.awaitAll()
                }
            } catch (e: Exception) {
                toast(cleanError(e)); ui.musicLoading.isVisible = false; return@launch
            }
            val playable = resolved.filter { it.second != null }
            if (playable.isEmpty()) { toast(getString(R.string.no_audio)); ui.musicLoading.isVisible = false; return@launch }

            queue = playable.map { it.first }
            val items = playable.map { (t, streamUrl) ->
                MediaItem.Builder()
                    .setUri(streamUrl)
                    .setMediaId(t.url)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(t.title)
                            .setArtist(t.uploader ?: t.channelName)
                            .apply { t.thumbnail?.let { setArtworkUri(it.toUri()) } }
                            .build()
                    )
                    .build()
            }
            val start = MusicQueue.startIndex.coerceIn(playable.indices)
            controller?.apply {
                setMediaItems(items, start, 0)
                prepare()
                playWhenReady = true
            }
            ui.musicLoading.isVisible = false
            handler.post(ticker)
            renderCurrent()
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = renderCurrent()
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            ui.playPauseButton.setIconResource(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            )
        }
    }

    private fun currentTrack(): VideoItem? =
        controller?.currentMediaItemIndex?.let { queue.getOrNull(it) }

    private fun renderStatic(index: Int) {
        val t = MusicQueue.tracks.getOrNull(index) ?: return
        ui.musicTitle.text = t.title
        ui.musicArtist.text = t.uploader ?: t.channelName ?: ""
        loadArt(t.thumbnail)
    }

    private fun renderCurrent() {
        val md = controller?.currentMediaItem?.mediaMetadata
        ui.musicTitle.text = md?.title ?: ""
        ui.musicArtist.text = md?.artist ?: ""
        loadArt(md?.artworkUri?.toString())
        updateFavIcon()
        updatePlaylistButton()
        val dur = controller?.duration ?: 0L
        ui.durTime.text = formatMs(if (dur > 0) dur else 0)
    }

    private fun loadArt(url: String?) {
        if (url.isNullOrEmpty()) return
        val loader = ImageLoader(this)
        val request = ImageRequest.Builder(this)
            .data(url)
            .allowHardware(false)
            .target { drawable ->
                ui.musicArt.setImageDrawable(drawable)
                (drawable as? BitmapDrawable)?.bitmap?.let { bmp ->
                    Palette.from(bmp).generate { palette ->
                        val c = palette?.getVibrantColor(
                            palette.getDominantColor(Color.parseColor("#1B2233"))
                        ) ?: Color.parseColor("#1B2233")
                        applyGradient(c)
                    }
                }
            }
            .build()
        loader.enqueue(request)
    }

    private fun applyGradient(color: Int) {
        val top = Color.argb(200, Color.red(color), Color.green(color), Color.blue(color))
        val bg = Color.parseColor("#0A0E1A")
        ui.gradientBg.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bg)
        )
    }

    private fun togglePlay() {
        controller?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    private fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
        ui.shuffleButton.setIconTintResource(if (c.shuffleModeEnabled) R.color.accent2 else R.color.text_dim)
    }

    private fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        ui.repeatButton.setIconResource(
            if (c.repeatMode == Player.REPEAT_MODE_ONE) android.R.drawable.ic_menu_today
            else android.R.drawable.ic_menu_revert
        )
        ui.repeatButton.setIconTintResource(
            if (c.repeatMode == Player.REPEAT_MODE_OFF) R.color.text_dim else R.color.accent2
        )
    }

    private fun updateProgress() {
        val c = controller ?: return
        val dur = c.duration
        if (dur > 0 && !seeking) {
            ui.seek.progress = (c.currentPosition * 100 / dur).toInt().coerceIn(0, 100)
            ui.posTime.text = formatMs(c.currentPosition)
            ui.durTime.text = formatMs(dur)
        }
    }

    private fun toggleFav() {
        val t = currentTrack() ?: run { toast(getString(R.string.no_audio)); return }
        val added = Favorites.toggleVideo(this, t)
        updateFavIcon()
        toast(getString(if (added) R.string.fav_added else R.string.fav_removed))
    }

    private fun updateFavIcon() {
        val t = currentTrack() ?: return
        val fav = Favorites.isVideoFav(this, t.url)
        ui.favButton.setIconResource(
            if (fav) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off
        )
        ui.favButton.setIconTintResource(if (fav) R.color.accent2 else R.color.text_dim)
    }

    private fun togglePlaylist() {
        val t = currentTrack() ?: return
        val pl = MusicQueue.playlistName
        if (pl == null) { choosePlaylist(t); return }
        val inList = Favorites.tracksOf(this, pl).any { it.url == t.url }
        if (inList) {
            Favorites.removeFromPlaylist(this, pl, t.url)
            toast(getString(R.string.removed_from, pl))
        } else {
            Favorites.addToPlaylist(this, pl, t)
            toast(getString(R.string.added_to, pl))
        }
        updatePlaylistButton()
    }

    private fun updatePlaylistButton() {
        val t = currentTrack()
        val pl = MusicQueue.playlistName
        val inList = pl != null && t != null && Favorites.tracksOf(this, pl).any { it.url == t.url }
        ui.addPlaylistButton.setText(if (inList) R.string.remove_from_playlist else R.string.add_to_playlist)
    }

    private fun downloadMp3(t: VideoItem) {
        if (!Downloads.start(this, t, AUDIO_QUALITY)) toast(getString(R.string.one_at_a_time))
        else toast(getString(R.string.download_started))
    }

    private fun choosePlaylist(t: VideoItem) {
        val names = Favorites.playlistNames(this)
        if (names.isEmpty()) { toast(getString(R.string.create_playlist_first)); return }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_to_playlist)
            .setItems(names.toTypedArray()) { _, i ->
                Favorites.addToPlaylist(this, names[i], t)
                toast(getString(R.string.added_to, names[i]))
            }
            .show()
    }

    private fun showQueue() {
        if (queue.isEmpty()) return
        val labels = queue.mapIndexed { i, t ->
            (if (i == controller?.currentMediaItemIndex) "▶ " else "") + t.title
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.up_next)
            .setItems(labels) { _, i -> controller?.seekTo(i, 0) }
            .show()
    }

    private fun requestNotificationsIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun formatMs(ms: Long): String {
        val totalSec = (ms / 1000).toInt()
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onDestroy()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
