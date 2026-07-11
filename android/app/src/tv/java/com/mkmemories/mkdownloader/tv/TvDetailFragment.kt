package com.mkmemories.mkdownloader.tv

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.leanback.app.DetailsSupportFragment
import androidx.leanback.widget.AbstractDetailsDescriptionPresenter
import androidx.leanback.widget.Action
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ClassPresenterSelector
import androidx.leanback.widget.DetailsOverviewRow
import androidx.leanback.widget.FullWidthDetailsOverviewRowPresenter
import androidx.leanback.widget.OnActionClickedListener
import androidx.lifecycle.lifecycleScope
import coil.imageLoader
import coil.request.ImageRequest
import com.mkmemories.mkdownloader.AUDIO_QUALITY
import com.mkmemories.mkdownloader.Downloads
import com.mkmemories.mkdownloader.Engine
import com.mkmemories.mkdownloader.PlayerActivity
import com.mkmemories.mkdownloader.QUALITIES
import com.mkmemories.mkdownloader.R
import com.mkmemories.mkdownloader.VideoItem
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Données de la fiche (mutables : enrichies en arrière-plan). */
class DetailData(var title: String, var subtitle: String, var body: String)

/** Présente titre / méta / description dans la fiche détail. */
class DetailDescriptionPresenter : AbstractDetailsDescriptionPresenter() {
    override fun onBindDescription(viewHolder: ViewHolder, item: Any) {
        val d = item as DetailData
        viewHolder.title.text = d.title
        viewHolder.subtitle.text = d.subtitle
        viewHolder.body.text = d.body
    }
}

/** Fiche détail « 10-foot » : grand visuel, méta (chaîne · vues · date), description, actions. */
class TvDetailFragment : DetailsSupportFragment() {

    private lateinit var item: VideoItem
    private lateinit var data: DetailData
    private lateinit var rowsAdapter: ArrayObjectAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val json = requireActivity().intent.getStringExtra(TvDetailActivity.EXTRA_ITEM).orEmpty()
        item = runCatching { VideoItem.fromJson(JSONObject(json)) }.getOrElse {
            requireActivity().finish(); return
        }
        data = DetailData(item.title, item.uploader.orEmpty(), "")

        val rowPresenter = FullWidthDetailsOverviewRowPresenter(DetailDescriptionPresenter()).apply {
            backgroundColor = ContextCompat.getColor(requireContext(), R.color.card)
            onActionClickedListener = OnActionClickedListener { onAction(it) }
        }
        rowsAdapter = ArrayObjectAdapter(
            ClassPresenterSelector().addClassPresenter(DetailsOverviewRow::class.java, rowPresenter)
        )

        val row = DetailsOverviewRow(data)
        row.actionsAdapter = ArrayObjectAdapter().apply {
            add(Action(ACTION_PLAY, getString(R.string.tv_play)))
            add(Action(ACTION_DL, getString(R.string.tv_download)))
            add(Action(ACTION_MP3, getString(R.string.player_mp3)))
        }
        rowsAdapter.add(row)
        adapter = rowsAdapter

        // Grand visuel.
        item.thumbnail?.let { url ->
            val req = ImageRequest.Builder(requireContext())
                .data(url)
                .target(onSuccess = { d: Drawable -> row.imageDrawable = d })
                .build()
            requireContext().imageLoader.enqueue(req)
        }

        // Méta + description en arrière-plan.
        lifecycleScope.launch {
            val det = runCatching { Engine.details(requireContext(), item.url) }.getOrNull() ?: return@launch
            data.subtitle = listOfNotNull(
                det.uploader?.takeIf { it.isNotBlank() },
                det.viewCount?.let { getString(R.string.player_views, compact(it)) },
                det.uploadDate?.let(::prettyDate),
            ).joinToString("  ·  ")
            data.body = det.description.orEmpty()
            rowsAdapter.notifyArrayItemRangeChanged(0, 1)
        }
    }

    private fun onAction(action: Action) {
        val ctx = requireContext()
        when (action.id) {
            ACTION_PLAY -> startActivity(Intent(ctx, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_URL, item.url)
                putExtra(PlayerActivity.EXTRA_TITLE, item.title)
                putExtra(PlayerActivity.EXTRA_TV, true)
            })
            ACTION_DL -> { Downloads.start(ctx, item, QUALITIES.first()); toast(getString(R.string.download_queued)) }
            ACTION_MP3 -> { Downloads.start(ctx, item, AUDIO_QUALITY); toast(getString(R.string.download_queued)) }
        }
    }

    private fun compact(n: Long): String = when {
        n >= 1_000_000 -> "%.1f M".format(n / 1_000_000.0).replace(".0", "")
        n >= 1_000 -> "%.1f k".format(n / 1_000.0).replace(".0", "")
        else -> n.toString()
    }

    private fun prettyDate(d: String): String =
        if (d.length == 8) "${d.substring(6, 8)}/${d.substring(4, 6)}/${d.substring(0, 4)}" else d

    private fun toast(m: String) = Toast.makeText(requireContext(), m, Toast.LENGTH_LONG).show()

    private companion object {
        const val ACTION_PLAY = 1L
        const val ACTION_DL = 2L
        const val ACTION_MP3 = 3L
    }
}
