package com.mkmemories.mkdownloader

import org.json.JSONArray
import org.json.JSONObject
import android.content.Context

/** Mémorise la position de lecture par vidéo (« reprendre » / continuer à regarder). */
object Resume {
    private const val PREFS = "mkdl_resume"
    private const val KEY_RECENT = "recent_json"
    private const val MAX_RECENT = 15

    /** Une lecture reprenable, avec de quoi bâtir une belle carte (carrousel « Reprendre »). */
    data class Item(
        val url: String,
        val title: String,
        val thumbnail: String?,
        val positionMs: Long,
        val durationMs: Long,
    ) {
        /** Progression 0..100 pour la barre sous l'affiche. */
        val percent: Int get() =
            if (durationMs > 0) ((positionMs * 100) / durationMs).toInt().coerceIn(1, 99) else 0
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(c: Context, url: String, title: String, positionMs: Long, durationMs: Long) {
        if (url.isBlank() || durationMs <= 0) return
        // On ne mémorise que « au milieu » : après 5 s, et pas dans les 10 dernières s.
        if (positionMs < 5_000 || positionMs > durationMs - 10_000) {
            prefs(c).edit().remove(key(url)).apply()
            removeRecent(c, url)
            return
        }
        prefs(c).edit().putLong(key(url), positionMs).apply()
        upsertRecent(c, Item(url, title.ifBlank { "Vidéo" }, thumbFromUrl(url), positionMs, durationMs))
    }

    fun get(c: Context, url: String): Long = prefs(c).getLong(key(url), 0L)

    fun clear(c: Context, url: String) {
        prefs(c).edit().remove(key(url)).apply()
        removeRecent(c, url)
    }

    /** Les lectures récentes reprenables, la plus récente en tête. */
    fun recent(c: Context): List<Item> {
        val raw = prefs(c).getString(KEY_RECENT, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url").ifEmpty { return@mapNotNull null }
                Item(
                    url = url,
                    title = o.optString("title", "Vidéo"),
                    thumbnail = o.optStringOrNull("thumbnail"),
                    positionMs = o.optLong("pos"),
                    durationMs = o.optLong("dur"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun upsertRecent(c: Context, item: Item) {
        val list = recent(c).filterNot { it.url == item.url }.toMutableList()
        list.add(0, item)
        while (list.size > MAX_RECENT) list.removeAt(list.size - 1)
        persist(c, list)
    }

    private fun removeRecent(c: Context, url: String) {
        val list = recent(c).filterNot { it.url == url }
        persist(c, list)
    }

    private fun persist(c: Context, list: List<Item>) {
        val arr = JSONArray()
        list.forEach { it ->
            arr.put(
                JSONObject().apply {
                    put("url", it.url); put("title", it.title); put("thumbnail", it.thumbnail)
                    put("pos", it.positionMs); put("dur", it.durationMs)
                }
            )
        }
        prefs(c).edit().putString(KEY_RECENT, arr.toString()).apply()
    }

    /** Déduit une miniature à partir de l'URL (YouTube, Internet Archive). */
    private fun thumbFromUrl(url: String): String? {
        Regex("(?:v=|youtu\\.be/|/shorts/|/embed/)([A-Za-z0-9_-]{11})").find(url)?.let {
            return "https://i.ytimg.com/vi/${it.groupValues[1]}/hqdefault.jpg"
        }
        Regex("archive\\.org/details/([^/?#]+)").find(url)?.let {
            return "https://archive.org/services/img/${it.groupValues[1]}"
        }
        return null
    }

    private fun key(url: String) = "pos_" + url.hashCode()
}
