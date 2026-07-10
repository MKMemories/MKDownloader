package com.mkmemories.mkdownloader

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlin.math.roundToInt
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
import androidx.media3.exoplayer.source.MergingMediaSource
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mkmemories.mkdownloader.databinding.ActivityPlayerBinding
import kotlinx.coroutines.launch

/** Lecteur vidéo : portrait « à la YouTube » + plein écran, qualité, extrait, Cast. */
@UnstableApi
class PlayerActivity : AppCompatActivity(), SessionAvailabilityListener {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_DIRECT = "direct" // flux HLS direct (TV), sans yt-dlp
        const val EXTRA_LIVE = "live"     // direct TV : plein écran, sans qualité/extrait
    }

    private lateinit var ui: ActivityPlayerBinding
    private var exo: ExoPlayer? = null
    private var castPlayer: CastPlayer? = null
    private var videoUrl = ""
    private var videoTitle = ""
    private var direct = false
    private var live = false
    private var fullscreen = false
    private var maxHeight = 1080
    private var durationSec = 0
    private var sources: List<String> = emptyList()
    private var speed = 1f

    private val qualityOptions = listOf(
        2160 to "4K (max)", 1080 to "1080p", 720 to "720p", 480 to "480p", 360 to "360p"
    )
    private val speedOptions = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideGesture = Runnable { ui.gestureIndicator.isVisible = false }
    private var scrollStartVol = -1
    private var scrollStartBright = -1f

    private val gestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                scrollStartVol = -1; scrollStartBright = -1f
                return true
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (ui.playerView.isControllerFullyVisible) ui.playerView.hideController()
                else ui.playerView.showController()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val p = exo ?: return true
                val forward = e.x > ui.playerView.width / 2f
                val target = if (forward) p.currentPosition + 10_000 else p.currentPosition - 10_000
                val max = if (p.duration > 0) p.duration else Long.MAX_VALUE
                p.seekTo(target.coerceIn(0, max))
                showGesture(if (forward) "+10 s ⏩" else "⏪ −10 s")
                return true
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (e1 == null || live) return false
                val h = ui.videoContainer.height.takeIf { it > 0 } ?: return false
                val frac = (e1.y - e2.y) / h // vers le haut = augmentation
                if (e1.x > ui.playerView.width / 2f) adjustVolume(frac) else adjustBrightness(frac)
                return true
            }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(ui.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        videoUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        videoTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        direct = intent.getBooleanExtra(EXTRA_DIRECT, false)
        live = intent.getBooleanExtra(EXTRA_LIVE, false)

        ui.clipRange.values = listOf(0f, 100f) // valeurs initiales (requis par RangeSlider)
        ui.playerTitle.text = videoTitle
        ui.panelTitle.text = videoTitle
        ui.closeButton.setOnClickListener { finish() }
        ui.fullscreenButton.setOnClickListener { if (fullscreen) exitFullscreen() else enterFullscreen() }
        ui.qualityButton.setOnClickListener { chooseQuality() }
        ui.downloadButton.setOnClickListener { askQualityAndDownload(null, null) }
        ui.downloadClipButton.setOnClickListener { downloadClip() }
        ui.clipRange.addOnChangeListener { _, _, _ -> updateClipLabels() }
        ui.speedButton.setOnClickListener { chooseSpeed() }
        ui.pipButton.setOnClickListener { enterPip() }
        ui.pipButton.isVisible = hasPip()
        @Suppress("ClickableViewAccessibility")
        ui.playerView.setOnTouchListener { _, ev -> gestureDetector.onTouchEvent(ev) }
        setupCastButton()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreen && !live) exitFullscreen() else finish()
            }
        })

        if (videoUrl.isEmpty()) { finish(); return }

        if (live || direct) {
            // Direct TV : plein écran paysage immersif d'emblée, sans panneau.
            ui.panel.isVisible = false
            enterFullscreen()
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            setPortraitVideoSize()
        }
        resolveAndPlay()
    }

    // ---------- Dimensions / plein écran ----------

    private fun setPortraitVideoSize() {
        val w = resources.displayMetrics.widthPixels
        ui.videoContainer.layoutParams = ui.videoContainer.layoutParams.apply {
            height = w * 9 / 16
        }
    }

    private fun enterFullscreen() {
        fullscreen = true
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        ui.panel.isVisible = false
        ui.fullscreenButton.setIconResource(android.R.drawable.ic_menu_view)
        ui.videoContainer.layoutParams = ui.videoContainer.layoutParams.apply {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, ui.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun exitFullscreen() {
        fullscreen = false
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, ui.root).show(WindowInsetsCompat.Type.systemBars())
        ui.panel.isVisible = true
        ui.fullscreenButton.setIconResource(android.R.drawable.ic_menu_crop)
        setPortraitVideoSize()
    }

    // ---------- Lecture ----------

    private fun resolveAndPlay() {
        ui.playerLoading.isVisible = true
        lifecycleScope.launch {
            sources = try {
                when {
                    direct -> listOf(videoUrl)
                    live -> listOfNotNull(Engine.streamUrl(this@PlayerActivity, videoUrl))
                    else -> Engine.playbackSources(this@PlayerActivity, videoUrl, maxHeight)
                }
            } catch (e: Exception) {
                toast(cleanError(e)); finish(); return@launch
            }
            if (sources.isEmpty()) { toast(getString(R.string.no_results)); finish(); return@launch }
            val startAt = if (!live && !direct) Resume.get(this@PlayerActivity, videoUrl) else 0L
            buildAndPlay(startAt)
            if (startAt > 0) toast(getString(R.string.resume_at, fmt((startAt / 1000).toInt())))
            ui.playerLoading.isVisible = false
        }
    }

    private fun httpFactory() = DefaultHttpDataSource.Factory()
        .setUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
        .setAllowCrossProtocolRedirects(true)

    private fun mediaItem(url: String): MediaItem {
        val b = MediaItem.Builder().setUri(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(videoTitle).build())
        when {
            url.contains(".m3u8") || url.contains("/manifest/hls") -> b.setMimeType(MimeTypes.APPLICATION_M3U8)
            url.contains(".mpd") -> b.setMimeType(MimeTypes.APPLICATION_MPD)
            url.contains(".mp4") -> b.setMimeType(MimeTypes.VIDEO_MP4)
        }
        return b.build()
    }

    private fun buildAndPlay(resumeMs: Long) {
        exo?.release()
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 60_000, 1_500, 3_000).build()
        val factory = DefaultMediaSourceFactory(httpFactory())
        exo = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(factory)
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build().also { player ->
                ui.playerView.player = player
                val src = if (sources.size >= 2) {
                    MergingMediaSource(
                        factory.createMediaSource(mediaItem(sources[0])),
                        factory.createMediaSource(mediaItem(sources[1])),
                    )
                } else {
                    factory.createMediaSource(mediaItem(sources[0]))
                }
                player.setMediaSource(src)
                player.prepare()
                if (resumeMs > 0) player.seekTo(resumeMs)
                player.setPlaybackSpeed(speed)
                player.playWhenReady = true
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY && durationSec == 0) {
                            val d = player.duration
                            if (d > 0 && !live) setupClip((d / 1000).toInt())
                        }
                    }
                })
            }
        // Si une session Cast est active, on y bascule.
        if (castPlayer?.isCastSessionAvailable == true) castPlayer?.let { switchToCast(it) }
    }

    // ---------- Qualité ----------

    private fun chooseQuality() {
        val labels = qualityOptions.map { it.second }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_stream_quality)
            .setItems(labels) { _, i ->
                maxHeight = qualityOptions[i].first
                ui.qualityButton.text = getString(R.string.quality_label, qualityOptions[i].second)
                reloadKeepingPosition()
            }
            .show()
    }

    // ---------- Vitesse ----------

    private fun chooseSpeed() {
        val labels = speedOptions.map { "${it}×".replace(".0×", "×") }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.speed_dialog)
            .setItems(labels) { _, i ->
                speed = speedOptions[i]
                exo?.setPlaybackSpeed(speed)
                ui.speedButton.text = labels[i]
            }
            .show()
    }

    // ---------- Gestes (double-tap ±10 s, glisser luminosité/volume) ----------

    private fun adjustVolume(frac: Float) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (scrollStartVol < 0) scrollStartVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val nv = (scrollStartVol + frac * max).roundToInt().coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, nv, 0)
        showGesture("🔊 ${nv * 100 / max}%")
    }

    private fun adjustBrightness(frac: Float) {
        if (scrollStartBright < 0) {
            scrollStartBright = window.attributes.screenBrightness.takeIf { it >= 0f } ?: 0.5f
        }
        val nb = (scrollStartBright + frac).coerceIn(0.02f, 1f)
        window.attributes = window.attributes.apply { screenBrightness = nb }
        showGesture("🔆 ${(nb * 100).toInt()}%")
    }

    private fun showGesture(text: String) {
        ui.gestureIndicator.text = text
        ui.gestureIndicator.isVisible = true
        uiHandler.removeCallbacks(hideGesture)
        uiHandler.postDelayed(hideGesture, 700)
    }

    // ---------- Picture-in-Picture ----------

    private fun hasPip() =
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun enterPip() {
        if (!hasPip()) return
        runCatching {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
            )
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Passe en fenêtre flottante quand on quitte l'app pendant la lecture.
        if (hasPip() && exo?.isPlaying == true && !isChangingConfigurations) enterPip()
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
        // En PiP : plus que la vidéo, on masque tout le reste.
        ui.playerView.useController = !inPip
        ui.topBar.isVisible = !inPip
        ui.fullscreenButton.isVisible = !inPip
        if (!live) ui.panel.isVisible = !inPip && !fullscreen
        if (inPip) {
            ui.videoContainer.layoutParams = ui.videoContainer.layoutParams.apply {
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
        } else if (!fullscreen && !live) {
            setPortraitVideoSize()
        }
    }

    private fun reloadKeepingPosition() {
        val pos = exo?.currentPosition ?: 0L
        ui.playerLoading.isVisible = true
        lifecycleScope.launch {
            sources = try {
                Engine.playbackSources(this@PlayerActivity, videoUrl, maxHeight)
            } catch (e: Exception) { toast(cleanError(e)); ui.playerLoading.isVisible = false; return@launch }
            if (sources.isNotEmpty()) buildAndPlay(pos)
            ui.playerLoading.isVisible = false
        }
    }

    // ---------- Extrait ----------

    private fun setupClip(dur: Int) {
        durationSec = dur
        ui.clipRange.valueFrom = 0f
        ui.clipRange.valueTo = dur.toFloat()
        ui.clipRange.values = listOf(0f, dur.toFloat())
        ui.clipRange.isEnabled = true
        ui.downloadClipButton.isEnabled = true
        updateClipLabels()
    }

    private fun updateClipLabels() {
        val v = ui.clipRange.values
        if (v.size < 2) return
        ui.clipStart.text = getString(R.string.clip_from, fmt(v[0].toInt()))
        ui.clipEnd.text = getString(R.string.clip_to, fmt(v[1].toInt()))
    }

    private fun downloadClip() {
        val v = ui.clipRange.values
        if (v.size < 2 || v[1] <= v[0]) { toast(getString(R.string.clip_invalid)); return }
        askQualityAndDownload(v[0].toInt(), v[1].toInt())
    }

    private fun fmt(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)

    // ---------- Téléchargement ----------

    private fun askQualityAndDownload(startSec: Int?, endSec: Int?) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_quality)
            .setItems(QUALITIES.map { it.label }.toTypedArray()) { _, index ->
                val started = Downloads.start(
                    this, VideoItem(videoUrl, videoTitle, null, 0, null),
                    QUALITIES[index], startSec, endSec,
                )
                toast(getString(if (started) R.string.download_started else R.string.one_at_a_time))
            }
            .show()
    }

    // ---------- Cast ----------

    private fun setupCastButton() {
        try {
            CastButtonFactory.setUpMediaRouteButton(applicationContext, ui.castButton)
            castPlayer = CastPlayer(CastContext.getSharedInstance(this))
                .also { it.setSessionAvailabilityListener(this) }
            ui.castButton.isVisible = true
        } catch (e: Exception) {
            ui.castButton.isVisible = false
        }
    }

    private fun switchToCast(cast: CastPlayer) {
        exo?.pause()
        ui.playerView.player = cast
        cast.setMediaItem(mediaItem(sources.first()), exo?.currentPosition ?: 0L)
        cast.prepare(); cast.playWhenReady = true
    }

    override fun onCastSessionAvailable() { castPlayer?.let { switchToCast(it) } }
    override fun onCastSessionUnavailable() {
        ui.playerView.player = exo
        exo?.playWhenReady = true
    }

    override fun onStop() {
        super.onStop()
        uiHandler.removeCallbacks(hideGesture)
        exo?.let { if (!live && !direct) Resume.save(this, videoUrl, it.currentPosition, it.duration) }
        exo?.release(); exo = null
        castPlayer?.setSessionAvailabilityListener(null)
        castPlayer?.release(); castPlayer = null
        if (!isChangingConfigurations) finish()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.hold, R.anim.slide_out_down)
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
