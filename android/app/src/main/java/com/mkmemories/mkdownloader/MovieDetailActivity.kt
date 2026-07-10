package com.mkmemories.mkdownloader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.mkmemories.mkdownloader.databinding.ActivityMovieBinding
import kotlinx.coroutines.launch

/** Fiche film premium : backdrop, casting, « où regarder », films similaires. */
class MovieDetailActivity : AppCompatActivity() {

    private lateinit var ui: ActivityMovieBinding
    private val cast = CastAdapter()
    private lateinit var similar: TmdbAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityMovieBinding.inflate(layoutInflater)
        setContentView(ui.root)

        ui.movieBack.setOnClickListener { finish() }

        val id = intent.getIntExtra(EXTRA_ID, 0)
        ui.movieTitle.text = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        intent.getStringExtra(EXTRA_POSTER)?.let { ui.moviePoster.load(it) }

        ui.castList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        ui.castList.adapter = cast
        similar = TmdbAdapter(fixedWidthDp = 116, onOpen = { start(this, it.id, it.title, it.poster) })
        ui.similarList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        ui.similarList.adapter = similar

        if (id == 0) { finish(); return }
        load(id)
    }

    private fun load(id: Int) {
        ui.movieLoading.isVisible = true
        lifecycleScope.launch {
            val d = runCatching { Tmdb.detail(this@MovieDetailActivity, id) }.getOrNull()
            ui.movieLoading.isVisible = false
            if (d == null) { toast(getString(R.string.tmdb_error)); return@launch }
            bind(d)
        }
    }

    private fun bind(d: Tmdb.MovieDetail) {
        ui.movieTitle.text = d.title
        d.backdrop?.let { ui.backdrop.load(it) }
        d.poster?.let { ui.moviePoster.load(it) }

        ui.movieRelease.text = d.releaseFr?.let { getString(R.string.tmdb_release_fr, it) }.orEmpty()
        ui.movieRelease.isVisible = d.releaseFr != null

        val facts = buildList {
            if (d.runtimeMin > 0) add(formatDuration(d.runtimeMin * 60))
            if (d.rating > 0) add("★ %.1f".format(d.rating) + if (d.voteCount > 0) " (${d.voteCount})" else "")
            d.cert?.let { add(it) }
        }.joinToString("  ·  ")
        ui.movieFacts.text = facts
        ui.movieFacts.isVisible = facts.isNotEmpty()

        ui.movieGenres.text = d.genres.joinToString(", ")
        ui.movieGenres.isVisible = d.genres.isNotEmpty()

        ui.movieDirector.text = d.director?.let { getString(R.string.movie_director, it) }.orEmpty()
        ui.movieDirector.isVisible = d.director != null

        ui.movieOverview.text = d.overview.ifBlank { getString(R.string.tmdb_no_synopsis) }

        ui.movieTrailer.setOnClickListener { playTrailer(d) }

        buildProviders(d)

        cast.submit(d.cast)
        ui.castHeader.isVisible = d.cast.isNotEmpty()
        ui.castList.isVisible = d.cast.isNotEmpty()

        similar.submit(d.similar)
        ui.similarHeader.isVisible = d.similar.isNotEmpty()
        ui.similarList.isVisible = d.similar.isNotEmpty()
    }

    private fun buildProviders(d: Tmdb.MovieDetail) {
        ui.providerRow.removeAllViews()
        if (d.providers.isEmpty()) { ui.providerSection.isVisible = false; return }
        ui.providerSection.isVisible = true
        val size = (44 * resources.displayMetrics.density).toInt()
        val margin = (8 * resources.displayMetrics.density).toInt()
        d.providers.forEach { p ->
            val img = ImageView(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(size, size).apply { marginEnd = margin }
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = p.name
                if (!p.logo.isNullOrEmpty()) load(p.logo) { crossfade(true) }
                else setImageResource(android.R.drawable.ic_menu_view)
            }
            ui.providerRow.addView(img)
        }
        val link = d.providerLink
        if (link != null) ui.providerRow.setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
        }
    }

    private fun playTrailer(d: Tmdb.MovieDetail) {
        toast(getString(R.string.tmdb_loading_trailer))
        lifecycleScope.launch {
            val key = runCatching { Tmdb.trailerYoutubeKey(this@MovieDetailActivity, d.id) }.getOrNull()
            if (key != null) {
                startActivity(Intent(this@MovieDetailActivity, PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_URL, "https://www.youtube.com/watch?v=$key")
                    putExtra(PlayerActivity.EXTRA_TITLE, "${d.title} — bande-annonce")
                })
            } else {
                toast(getString(R.string.tmdb_no_trailer))
            }
        }
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_POSTER = "poster"

        fun start(ctx: android.content.Context, id: Int, title: String, poster: String?) {
            ctx.startActivity(Intent(ctx, MovieDetailActivity::class.java).apply {
                putExtra(EXTRA_ID, id)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_POSTER, poster)
            })
        }
    }
}
