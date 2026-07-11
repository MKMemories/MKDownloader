package com.mkmemories.mkdownloader.tv

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.core.content.ContextCompat
import androidx.leanback.app.BackgroundManager
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.lifecycle.lifecycleScope
import coil.imageLoader
import coil.request.ImageRequest
import com.mkmemories.mkdownloader.Engine
import com.mkmemories.mkdownloader.HistoryEntry
import com.mkmemories.mkdownloader.OfflineLibrary
import com.mkmemories.mkdownloader.PlayerActivity
import com.mkmemories.mkdownloader.R
import com.mkmemories.mkdownloader.Tv
import com.mkmemories.mkdownloader.TvChannel
import com.mkmemories.mkdownloader.VideoItem
import kotlinx.coroutines.launch

/** Accueil TV Leanback : rangées de cartes + recherche, navigation à la télécommande. */
class TvBrowseFragment : BrowseSupportFragment() {

    private val rows = ArrayObjectAdapter(ListRowPresenter())
    private var bg: BackgroundManager? = null
    private val bgHandler = Handler(Looper.getMainLooper())
    private var bgUrl: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Marque : titre + couleurs premium (accent violet, orbe cyan).
        title = getString(R.string.tv_title)
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        brandColor = ContextCompat.getColor(requireContext(), R.color.accent)
        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.accent2)
        adapter = rows
        setupBackground()
        setOnItemViewClickedListener { _, item, _, _ -> onItemClicked(item) }
        setOnItemViewSelectedListener { _, item, _, _ -> updateBackground(item) }
        setOnSearchClickedListener {
            startActivity(Intent(requireContext(), TvSearchActivity::class.java))
        }
        buildRows()
    }

    // ---------- Fond cinématique dynamique (façon Netflix/Disney+) ----------

    private fun setupBackground() {
        bg = BackgroundManager.getInstance(requireActivity()).also {
            if (!it.isAttached) it.attach(requireActivity().window)
            it.color = ContextCompat.getColor(requireContext(), R.color.bg)
        }
    }

    /** Charge la vignette de l'élément en focus en fond plein écran, assombri. */
    private fun updateBackground(item: Any?) {
        val url = when (item) {
            is VideoItem -> item.thumbnail
            is TvChannel -> item.logo
            else -> null
        }
        if (url == bgUrl) return
        bgUrl = url
        bgHandler.removeCallbacksAndMessages(null)
        if (url == null) return
        bgHandler.postDelayed({
            val ctx = context ?: return@postDelayed
            val req = ImageRequest.Builder(ctx)
                .data(url)
                .target(onSuccess = { d: Drawable ->
                    // Voile sombre pour garder titres et cartes lisibles.
                    bg?.drawable = LayerDrawable(arrayOf(d, ColorDrawable(0xB3000000.toInt())))
                })
                .build()
            ctx.imageLoader.enqueue(req)
        }, 350)
    }

    override fun onDestroyView() {
        bgHandler.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }

    private fun buildRows() {
        val ctx = requireContext()
        // Contenu statique immédiat.
        addRow(getString(R.string.tv_row_live), Tv.CHANNELS)
        addRow(getString(R.string.tv_row_downloads), OfflineLibrary.entries(ctx))
        // Tendances (réseau) : insérées en tête une fois chargées.
        lifecycleScope.launch {
            val trend = runCatching { Engine.trending(ctx, 24) }.getOrDefault(emptyList())
            if (trend.isNotEmpty()) {
                val a = ArrayObjectAdapter(CardPresenter()).apply { addAll(0, trend) }
                rows.add(0, ListRow(HeaderItem(getString(R.string.tv_row_trending)), a))
            }
        }
    }

    private fun addRow(title: String, items: List<Any>) {
        if (items.isEmpty()) return
        val a = ArrayObjectAdapter(CardPresenter()).apply { addAll(0, items) }
        rows.add(ListRow(HeaderItem(title), a))
    }

    private fun onItemClicked(item: Any?) {
        val ctx = requireContext()
        when (item) {
            is VideoItem -> startActivity(Intent(ctx, TvDetailActivity::class.java).apply {
                putExtra(TvDetailActivity.EXTRA_ITEM, item.toJson().toString())
            })
            is TvChannel -> startActivity(Intent(ctx, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_URL, item.url)
                putExtra(PlayerActivity.EXTRA_TITLE, item.name)
                putExtra(PlayerActivity.EXTRA_DIRECT, !item.resolve)
                putExtra(PlayerActivity.EXTRA_LIVE, true)
            })
            is HistoryEntry -> startActivity(Intent(ctx, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_URL, item.uri)
                putExtra(PlayerActivity.EXTRA_TITLE, item.title)
                putExtra(PlayerActivity.EXTRA_DIRECT, true)
            })
        }
    }
}
