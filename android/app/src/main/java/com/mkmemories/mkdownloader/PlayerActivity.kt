package com.mkmemories.mkdownloader

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mkmemories.mkdownloader.databinding.ActivityPlayerBinding
import kotlinx.coroutines.launch

/** Lecteur vidéo plein écran premium : démarrage rapide, immersif, avec Cast. */
@UnstableApi
class PlayerActivity : AppCompatActivity(), SessionAvailabilityListener {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
    }

    private lateinit var ui: ActivityPlayerBinding
    private var exo: ExoPlayer? = null
    private var castPlayer: CastPlayer? = null
    private var mediaItem: MediaItem? = null
    private var videoUrl = ""
    private var videoTitle = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(ui.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        enableImmersive()

        videoUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        videoTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        ui.playerTitle.text = videoTitle
        ui.closeButton.setOnClickListener { finish() }
        ui.downloadFromPlayer.setOnClickListener { askQualityAndDownload() }

        setupCastButton()

        if (videoUrl.isEmpty()) { finish(); return }
        resolveAndPlay()
    }

    private fun enableImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, ui.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun setupCastButton() {
        try {
            CastButtonFactory.setUpMediaRouteButton(applicationContext, ui.castButton)
            val castContext = CastContext.getSharedInstance(this)
            castPlayer = CastPlayer(castContext).also { it.setSessionAvailabilityListener(this) }
            ui.castButton.isVisible = true
        } catch (e: Exception) {
            // Services Google Play/Cast indisponibles : lecture locale uniquement.
            ui.castButton.isVisible = false
        }
    }

    private fun resolveAndPlay() {
        ui.playerLoading.isVisible = true
        lifecycleScope.launch {
            val url = try {
                Engine.streamUrl(this@PlayerActivity, videoUrl)
            } catch (e: Exception) {
                toast(cleanError(e)); finish(); return@launch
            }
            if (url.isNullOrEmpty()) { toast(getString(R.string.no_results)); finish(); return@launch }

            mediaItem = MediaItem.Builder()
                .setUri(url)
                .setMimeType(MimeTypes.VIDEO_MP4)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(videoTitle).build())
                .build()

            buildExo()
            // Si une session Cast est déjà active, on démarre directement sur le téléviseur.
            val onCast = castPlayer?.isCastSessionAvailable == true
            setCurrentPlayer(if (onCast) castPlayer!! else exo!!)
            ui.playerLoading.isVisible = false
        }
    }

    private fun buildExo() {
        // Buffers réduits = image affichée quasi instantanément.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 1_000, 2_000)
            .build()
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            .setAllowCrossProtocolRedirects(true)
        exo = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            // Gestion du focus audio : démarrer cette lecture met en pause toute
            // autre lecture en cours (musique en arrière-plan, autre vidéo…).
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    private fun setCurrentPlayer(player: Player) {
        val item = mediaItem ?: return
        val resumePosition = ui.playerView.player?.currentPosition ?: 0L
        // Débranche l'ancien lecteur
        exo?.takeIf { it !== player }?.pause()
        castPlayer?.takeIf { it !== player }?.pause()

        ui.playerView.player = player
        player.setMediaItem(item, resumePosition)
        player.prepare()
        player.playWhenReady = true
    }

    // ---------- Cast ----------

    override fun onCastSessionAvailable() {
        castPlayer?.let { setCurrentPlayer(it) }
    }

    override fun onCastSessionUnavailable() {
        exo?.let { setCurrentPlayer(it) }
    }

    // ---------- Téléchargement depuis le lecteur ----------

    private fun askQualityAndDownload() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_quality)
            .setItems(QUALITIES.map { it.label }.toTypedArray()) { _, index ->
                val started = Downloads.start(
                    this, VideoItem(videoUrl, videoTitle, null, 0, null), QUALITIES[index]
                )
                toast(getString(if (started) R.string.download_started else R.string.one_at_a_time))
            }
            .show()
    }

    override fun onStop() {
        super.onStop()
        exo?.release(); exo = null
        castPlayer?.setSessionAvailabilityListener(null)
        castPlayer?.release(); castPlayer = null
        if (!isChangingConfigurations) finish()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
