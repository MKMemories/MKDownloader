package com.mkmemories.mkdownloader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder

/** Une ligne de paroles synchronisée (horodatage + texte). */
data class LrcLine(val timeMs: Long, val text: String)

/**
 * Paroles synchronisées via LRCLIB (lrclib.net) — API gratuite, ouverte, sans
 * clé. Renvoie null si aucune parole synchronisée n'est trouvée pour le titre.
 */
object Lyrics {

    suspend fun fetch(title: String, artist: String?, durationSec: Int): List<LrcLine>? =
        withContext(Dispatchers.IO) {
            val synced = getSynced(title, artist, durationSec) ?: searchSynced(title, artist)
            synced?.let(::parseLrc)?.takeIf { it.isNotEmpty() }
        }

    private fun getSynced(title: String, artist: String?, durationSec: Int): String? {
        val params = buildString {
            append("track_name=").append(enc(cleanTitle(title)))
            if (!artist.isNullOrBlank()) append("&artist_name=").append(enc(cleanArtist(artist)))
            if (durationSec > 0) append("&duration=").append(durationSec)
        }
        val json = runCatching { JSONObject(get("https://lrclib.net/api/get?$params")) }.getOrNull()
            ?: return null
        return json.optString("syncedLyrics").takeIf { it.isNotBlank() }
    }

    private fun searchSynced(title: String, artist: String?): String? {
        val q = enc(((artist?.let { cleanArtist(it) + " " }) ?: "") + cleanTitle(title))
        val arr = runCatching { JSONArray(get("https://lrclib.net/api/search?q=$q")) }.getOrNull()
            ?: return null
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i)?.optString("syncedLyrics")
            if (!s.isNullOrBlank()) return s
        }
        return null
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection()
        conn.setRequestProperty("User-Agent", "MKDownloader (personal use)")
        conn.connectTimeout = 12000
        conn.readTimeout = 12000
        return conn.getInputStream().bufferedReader().use { it.readText() }
    }

    /** Parse le format LRC : lignes « [mm:ss.xx] texte » (horodatages multiples gérés). */
    private fun parseLrc(lrc: String): List<LrcLine> {
        val out = ArrayList<LrcLine>()
        val tag = Regex("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?]")
        lrc.lineSequence().forEach { line ->
            val matches = tag.findAll(line).toList()
            if (matches.isEmpty()) return@forEach
            val text = line.substring(matches.last().range.last + 1).trim()
            matches.forEach { m ->
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3]
                val ms = when (frac.length) {
                    0 -> 0L
                    1 -> frac.toLong() * 100
                    2 -> frac.toLong() * 10
                    else -> frac.take(3).toLong()
                }
                out.add(LrcLine(min * 60_000 + sec * 1000 + ms, text))
            }
        }
        return out.sortedBy { it.timeMs }
    }

    /** Nettoie le titre YouTube (retire « (Official Video) », « [HD] »…). */
    private fun cleanTitle(t: String): String =
        t.replace(Regex("(?i)[(\\[][^)\\]]*(official|video|audio|lyrics?|clip|hd|4k|mv|remaster)[^)\\]]*[)\\]]"), "")
            .replace(Regex("(?i)\\b(official (music )?video|lyric[s]? video|visualizer)\\b"), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()

    private fun cleanArtist(a: String): String =
        a.replace(Regex("(?i)\\s*-\\s*topic$"), "")
            .replace(Regex("(?i)\\bVEVO$"), "")
            .trim()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
