package com.mkmemories.mkdownloader

import android.content.Context
import org.json.JSONArray

/** Dernières écoutes (musique, radios, hors-ligne) pour « Reprendre / Récents ». */
object Recents {
    private const val PREFS = "mkdl_recents"
    private const val KEY = "list"
    private const val MAX = 25

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun add(context: Context, item: VideoItem) {
        if (item.url.isBlank() || item.title.isBlank()) return
        val list = list(context).filterNot { it.url == item.url }.toMutableList()
        list.add(0, item)
        while (list.size > MAX) list.removeAt(list.size - 1)
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    fun list(context: Context): List<VideoItem> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(VideoItem::fromJson) }
        }.getOrDefault(emptyList())
    }
}
