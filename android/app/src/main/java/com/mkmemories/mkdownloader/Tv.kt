package com.mkmemories.mkdownloader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

data class TvChannel(
    val name: String,
    val logo: String?,
    val group: String?,
    val url: String,
)

/**
 * Chaînes françaises diffusées EN CLAIR (free-to-air), agrégées par le projet
 * open-source iptv-org : flux HLS publics, lisibles directement. Aucun contenu
 * payant/DRM (Canal+, beIN, Netflix… en sont volontairement absents).
 */
object Tv {
    private const val PLAYLIST_URL = "https://iptv-org.github.io/iptv/countries/fr.m3u"
    @Volatile private var cache: List<TvChannel>? = null

    suspend fun channels(force: Boolean = false): List<TvChannel> = withContext(Dispatchers.IO) {
        cache?.let { if (!force) return@withContext it }
        val text = URL(PLAYLIST_URL).openStream().bufferedReader().use { it.readText() }
        val list = parse(text)
        cache = list
        list
    }

    private fun parse(m3u: String): List<TvChannel> {
        val result = mutableListOf<TvChannel>()
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
                    result.add(TvChannel(name!!, logo, group, line))
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
