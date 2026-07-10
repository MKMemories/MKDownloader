package com.mkmemories.mkdownloader

import android.content.Context

/** Mémorise la position de lecture par vidéo (« reprendre » + futur « continuer à regarder »). */
object Resume {
    private const val PREFS = "mkdl_resume"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(c: Context, url: String, positionMs: Long, durationMs: Long) {
        if (url.isBlank() || durationMs <= 0) return
        // On ne mémorise que « au milieu » : après 5 s, et pas dans les 10 dernières s.
        if (positionMs < 5_000 || positionMs > durationMs - 10_000) {
            prefs(c).edit().remove(key(url)).apply()
            return
        }
        prefs(c).edit().putLong(key(url), positionMs).apply()
    }

    fun get(c: Context, url: String): Long = prefs(c).getLong(key(url), 0L)

    fun clear(c: Context, url: String) = prefs(c).edit().remove(key(url)).apply()

    private fun key(url: String) = "pos_" + url.hashCode()
}
