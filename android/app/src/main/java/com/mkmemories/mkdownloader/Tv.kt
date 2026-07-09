package com.mkmemories.mkdownloader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

/**
 * @param url     Source du direct (résolue par yt-dlp sur l'appareil).
 * @param resolve true → page à résoudre (YouTube live) ; false → HLS direct.
 * @param note    Info éventuelle (géo).
 */
data class TvChannel(
    val name: String,
    val group: String?,
    val url: String,
    val resolve: Boolean,
    val logo: String? = null,
    val note: String? = null,
)

/**
 * Chaînes réellement diffusables : uniquement des directs 24/7 en clair (YouTube),
 * sans DRM. Les chaînes protégées par DRM Widevine (TF1, France 2/4/5, M6, RAI…)
 * sont volontairement EXCLUES : yt-dlp les refuse (« [DRM] … will NOT be
 * downloaded ») et aucune app ne peut les lire sans licence propriétaire.
 */
object Tv {
    val CHANNELS: List<TvChannel> = listOf(
        // --- France : info en continu (directs YouTube fiables) ---
        TvChannel("France Info", "France", "https://www.youtube.com/@franceinfo/live", resolve = true),
        TvChannel("BFM TV", "France", "https://www.youtube.com/@BFMTV/live", resolve = true),
        TvChannel("LCI", "France", "https://www.youtube.com/@LCI/live", resolve = true),
        TvChannel("France 24", "France", "https://www.youtube.com/@FRANCE24/live", resolve = true),
        TvChannel("Euronews", "France", "https://www.youtube.com/@euronewsfr/live", resolve = true),

        // --- International (directs YouTube) ---
        TvChannel("CNN", "International", "https://www.youtube.com/@CNN/live", resolve = true, note = "Direct selon disponibilité"),
        TvChannel("BBC News", "International", "https://www.youtube.com/@BBCNews/live", resolve = true, note = "Peut être géo-restreint → VPN"),
        TvChannel("Al Jazeera English", "International", "https://www.youtube.com/@aljazeeraenglish/live", resolve = true),

        // --- Tunisie (directs YouTube) ---
        TvChannel("Nessma TV", "Tunisie", "https://www.youtube.com/@NessmaTv/live", resolve = true),
        TvChannel("Elhiwar Ettounsi", "Tunisie", "https://www.youtube.com/@elhiwarettounsi/live", resolve = true),
        TvChannel("Télévision Tunisienne (Watania 1)", "Tunisie", "https://www.youtube.com/@Watania1/live", resolve = true),
    )

    /**
     * Télécharge et parse une playlist IPTV. On privilégie le HLS (output=m3u8),
     * bien mieux lu par ExoPlayer que le MPEG-TS brut pour du direct ; repli sur
     * l'URL d'origine si le serveur ne le propose pas.
     */
    suspend fun fetchIptv(url: String): List<TvChannel> = withContext(Dispatchers.IO) {
        val hls = url.replace("output=ts", "output=m3u8")
        val primary = runCatching { download(hls) }.getOrNull()
        if (!primary.isNullOrEmpty()) return@withContext primary
        download(url)
    }

    private fun download(url: String): List<TvChannel> {
        val conn = URL(url).openConnection()
        conn.setRequestProperty("User-Agent", "VLC/3.0 (Linux; Android)")
        conn.connectTimeout = 20000
        conn.readTimeout = 20000
        val text = conn.getInputStream().bufferedReader().use { it.readText() }
        return parseM3u(text)
    }

    private fun parseM3u(m3u: String): List<TvChannel> {
        val result = ArrayList<TvChannel>()
        var name: String? = null
        var logo: String? = null
        var group: String? = null
        m3u.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF") -> {
                    name = line.substringAfterLast(",", "").trim().ifEmpty { null }
                    logo = attr(line, "tvg-logo")
                    group = attr(line, "group-title")
                }
                line.startsWith("#") -> Unit
                line.isNotEmpty() && name != null -> {
                    result.add(TvChannel(name!!, group, line, resolve = false, logo = logo))
                    name = null; logo = null; group = null
                }
            }
        }
        return result.distinctBy { it.url }
    }

    private fun attr(line: String, key: String): String? {
        val marker = "$key=\""
        val start = line.indexOf(marker).takeIf { it >= 0 } ?: return null
        val from = start + marker.length
        val end = line.indexOf('"', from).takeIf { it >= 0 } ?: return null
        return line.substring(from, end).ifEmpty { null }
    }
}
