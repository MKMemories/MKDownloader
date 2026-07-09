package com.mkmemories.mkdownloader

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mkmemories.mkdownloader.databinding.ActivityPlayerBinding
import kotlinx.coroutines.launch

/** Lecteur vidéo intégré : streaming direct des flux extraits par yt-dlp. */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
    }

    private lateinit var ui: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var videoUrl: String = ""
    private var videoTitle: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(ui.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        videoUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        videoTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        ui.playerTitle.text = videoTitle
        ui.closeButton.setOnClickListener { finish() }
        ui.downloadFromPlayer.setOnClickListener { askQualityAndDownload() }

        if (videoUrl.isEmpty()) { finish(); return }
        resolveAndPlay()
    }

    private fun resolveAndPlay() {
        ui.playerLoading.isVisible = true
        lifecycleScope.launch {
            try {
                val urls = Engine.streamUrls(this@PlayerActivity, videoUrl)
                if (urls.isEmpty()) error("Aucun flux lisible trouvé.")
                startPlayback(urls)
            } catch (e: Exception) {
                Toast.makeText(this@PlayerActivity, cleanError(e), Toast.LENGTH_LONG).show()
                finish()
            } finally {
                ui.playerLoading.isVisible = false
            }
        }
    }

    private fun startPlayback(urls: List<String>) {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            .setAllowCrossProtocolRedirects(true)
        val factory = DefaultMediaSourceFactory(httpFactory)
        val sources = urls.map { factory.createMediaSource(MediaItem.fromUri(it)) }
        val source = if (sources.size >= 2) MergingMediaSource(sources[0], sources[1]) else sources[0]

        player = ExoPlayer.Builder(this).build().also { exo ->
            ui.playerView.player = exo
            exo.setMediaSource(source)
            exo.prepare()
            exo.playWhenReady = true
        }
    }

    private fun askQualityAndDownload() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_quality)
            .setItems(QUALITIES.map { it.label }.toTypedArray()) { _, index ->
                val started = Downloads.start(
                    this,
                    VideoItem(videoUrl, videoTitle, null, 0, null),
                    QUALITIES[index],
                )
                Toast.makeText(
                    this,
                    if (started) getString(R.string.download_started) else getString(R.string.one_at_a_time),
                    Toast.LENGTH_LONG,
                ).show()
            }
            .show()
    }

    override fun onStop() {
        super.onStop()
        player?.release()
        player = null
        if (!isChangingConfigurations) finish()
    }
}
