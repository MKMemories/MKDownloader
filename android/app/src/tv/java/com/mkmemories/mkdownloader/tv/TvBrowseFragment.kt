package com.mkmemories.mkdownloader.tv

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.lifecycle.lifecycleScope
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        title = getString(R.string.tv_title)
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        adapter = rows
        setOnItemViewClickedListener { _, item, _, _ -> onItemClicked(item) }
        setOnSearchClickedListener {
            startActivity(Intent(requireContext(), TvSearchActivity::class.java))
        }
        buildRows()
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
            is VideoItem -> startActivity(Intent(ctx, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_URL, item.url)
                putExtra(PlayerActivity.EXTRA_TITLE, item.title)
                putExtra(PlayerActivity.EXTRA_TV, true)
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
