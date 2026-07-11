package com.mkmemories.mkdownloader.tv

import android.content.Intent
import android.os.Bundle
import androidx.leanback.app.SearchSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.ObjectAdapter
import androidx.lifecycle.lifecycleScope
import com.mkmemories.mkdownloader.DateFilter
import com.mkmemories.mkdownloader.Engine
import com.mkmemories.mkdownloader.PlayerActivity
import com.mkmemories.mkdownloader.R
import com.mkmemories.mkdownloader.VideoItem
import kotlinx.coroutines.launch

/** Recherche YouTube à la télécommande → rangée de résultats → lecture. */
class TvSearchFragment : SearchSupportFragment(), SearchSupportFragment.SearchResultProvider {

    private val rows = ArrayObjectAdapter(ListRowPresenter())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSearchResultProvider(this)
        setOnItemViewClickedListener { _, item, _, _ ->
            if (item is VideoItem) startActivity(
                Intent(requireContext(), TvDetailActivity::class.java).apply {
                    putExtra(TvDetailActivity.EXTRA_ITEM, item.toJson().toString())
                }
            )
        }
    }

    override fun getResultsAdapter(): ObjectAdapter = rows

    override fun onQueryTextChange(newQuery: String?): Boolean = false

    override fun onQueryTextSubmit(query: String?): Boolean {
        val q = query?.trim().orEmpty()
        if (q.isNotEmpty()) runSearch(q)
        return true
    }

    private fun runSearch(query: String) {
        rows.clear()
        lifecycleScope.launch {
            val res = runCatching { Engine.search(requireContext(), query, DateFilter.ANY, 30) }
                .getOrDefault(emptyList())
            val a = ArrayObjectAdapter(CardPresenter()).apply { addAll(0, res) }
            rows.add(ListRow(HeaderItem(getString(R.string.tv_search_results, query)), a))
        }
    }
}
