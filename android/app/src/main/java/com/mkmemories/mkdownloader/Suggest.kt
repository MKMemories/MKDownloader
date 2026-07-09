package com.mkmemories.mkdownloader

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.Filter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.URL
import java.net.URLEncoder

/** Suggestions de recherche YouTube (autocomplétion), via l'endpoint public Google. */
object Suggest {
    suspend fun fetch(query: String): List<String> = withContext(Dispatchers.IO) {
        if (query.length < 2) return@withContext emptyList()
        val url = "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&hl=fr&q=" +
            URLEncoder.encode(query, "UTF-8")
        val conn = URL(url).openConnection()
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        val text = conn.getInputStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = JSONArray(text)
        val list = arr.optJSONArray(1) ?: return@withContext emptyList()
        (0 until list.length()).map { list.optString(it) }.filter { it.isNotEmpty() }.take(10)
    }
}

/**
 * Adaptateur d'autocomplétion qui n'applique AUCUN filtre local : il affiche
 * telles quelles les suggestions renvoyées par le serveur.
 */
class SuggestionsAdapter(context: Context) :
    ArrayAdapter<String>(context, R.layout.item_suggestion, R.id.suggestionText) {

    fun replace(items: List<String>) {
        clear(); addAll(items); notifyDataSetChanged()
    }

    private val passthrough = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults =
            FilterResults().apply { count = this@SuggestionsAdapter.count }
        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            notifyDataSetChanged()
        }
    }

    override fun getFilter(): Filter = passthrough
}
