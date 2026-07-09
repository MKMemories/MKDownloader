package com.mkmemories.mkdownloader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Favoris (chaînes + vidéos) et playlists musicales, persistés localement. */
object Favorites {
    private const val PREFS = "mkdl_favorites"
    private const val KEY_CHANNELS = "channels"
    private const val KEY_VIDEOS = "videos"
    private const val KEY_PLAYLISTS = "playlists"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- Chaînes favorites ----------

    fun channels(c: Context): List<ChannelItem> =
        readArray(c, KEY_CHANNELS).map(ChannelItem::fromJson)

    fun isChannelFav(c: Context, url: String) = channels(c).any { it.url == url }

    fun toggleChannel(c: Context, channel: ChannelItem): Boolean {
        val list = channels(c).toMutableList()
        val existed = list.removeAll { it.url == channel.url }
        if (!existed) list.add(0, channel)
        writeArray(c, KEY_CHANNELS, list.map { it.toJson() })
        return !existed // true = ajouté
    }

    // ---------- Vidéos favorites ----------

    fun videos(c: Context): List<VideoItem> =
        readArray(c, KEY_VIDEOS).map(VideoItem::fromJson)

    fun isVideoFav(c: Context, url: String) = videos(c).any { it.url == url }

    fun toggleVideo(c: Context, item: VideoItem): Boolean {
        val list = videos(c).toMutableList()
        val existed = list.removeAll { it.url == item.url }
        if (!existed) list.add(0, item)
        writeArray(c, KEY_VIDEOS, list.map { it.toJson() })
        return !existed
    }

    // ---------- Playlists musicales ----------

    /** Renvoie la map ordonnée nom -> morceaux. */
    fun playlists(c: Context): LinkedHashMap<String, MutableList<VideoItem>> {
        val raw = prefs(c).getString(KEY_PLAYLISTS, "{}") ?: "{}"
        val obj = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        val map = LinkedHashMap<String, MutableList<VideoItem>>()
        obj.keys().forEach { name ->
            val arr = obj.optJSONArray(name) ?: JSONArray()
            map[name] = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(VideoItem::fromJson) }.toMutableList()
        }
        return map
    }

    fun playlistNames(c: Context): List<String> = playlists(c).keys.toList()

    fun tracksOf(c: Context, name: String): List<VideoItem> = playlists(c)[name] ?: emptyList()

    fun createPlaylist(c: Context, name: String) {
        val map = playlists(c)
        if (!map.containsKey(name)) { map[name] = mutableListOf(); savePlaylists(c, map) }
    }

    fun deletePlaylist(c: Context, name: String) {
        val map = playlists(c); map.remove(name); savePlaylists(c, map)
    }

    fun addToPlaylist(c: Context, name: String, item: VideoItem) {
        val map = playlists(c)
        val list = map.getOrPut(name) { mutableListOf() }
        if (list.none { it.url == item.url }) list.add(item)
        savePlaylists(c, map)
    }

    fun removeFromPlaylist(c: Context, name: String, url: String) {
        val map = playlists(c)
        map[name]?.removeAll { it.url == url }
        savePlaylists(c, map)
    }

    private fun savePlaylists(c: Context, map: Map<String, List<VideoItem>>) {
        val obj = JSONObject()
        map.forEach { (name, list) ->
            val arr = JSONArray(); list.forEach { arr.put(it.toJson()) }
            obj.put(name, arr)
        }
        prefs(c).edit().putString(KEY_PLAYLISTS, obj.toString()).apply()
    }

    // ---------- Utilitaires ----------

    private fun readArray(c: Context, key: String): List<JSONObject> {
        val raw = prefs(c).getString(key, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    private fun writeArray(c: Context, key: String, items: List<JSONObject>) {
        val arr = JSONArray(); items.forEach { arr.put(it) }
        prefs(c).edit().putString(key, arr.toString()).apply()
    }
}
