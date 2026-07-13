package com.mkmemories.mkdownloader

import org.json.JSONObject

data class VideoItem(
    val url: String,
    val title: String,
    val uploader: String?,
    val durationSec: Int,
    val thumbnail: String?,
    val channelName: String? = null,
    val channelUrl: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("url", url); put("title", title); put("uploader", uploader)
        put("durationSec", durationSec); put("thumbnail", thumbnail)
        put("channelName", channelName); put("channelUrl", channelUrl)
    }

    companion object {
        fun fromJson(o: JSONObject) = VideoItem(
            url = o.optString("url"),
            title = o.optString("title", "Vidéo"),
            uploader = o.optStringOrNull("uploader"),
            durationSec = o.optInt("durationSec"),
            thumbnail = o.optStringOrNull("thumbnail"),
            channelName = o.optStringOrNull("channelName"),
            channelUrl = o.optStringOrNull("channelUrl"),
        )
    }
}

/**
 * Un film de la bibliothèque cinéma (source : Internet Archive, domaine public
 * / Creative Commons). L'URL de lecture/téléchargement est la page « details »
 * qu'yt-dlp sait résoudre, ce qui réutilise tout le pipeline existant.
 */
data class Film(
    val identifier: String,
    val title: String,
    val year: Int?,
    val genres: List<String>,
    val language: String?,
) {
    val detailsUrl get() = "https://archive.org/details/$identifier"
    val poster get() = "https://archive.org/services/img/$identifier"

    fun toVideoItem(): VideoItem = VideoItem(
        url = detailsUrl,
        title = title,
        uploader = listOfNotNull(year?.toString(), genres.firstOrNull()).joinToString(" · ").ifEmpty { null },
        durationSec = 0,
        thumbnail = poster,
    )
}

data class ChannelItem(
    val url: String,
    val name: String,
    val thumbnail: String?,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("url", url); put("name", name); put("thumbnail", thumbnail)
    }

    companion object {
        fun fromJson(o: JSONObject) = ChannelItem(
            url = o.optString("url"),
            name = o.optString("name", "Chaîne"),
            thumbnail = o.optStringOrNull("thumbnail"),
        )
    }
}

fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).ifEmpty { null }

data class Quality(
    val id: String,
    val label: String,
    val format: String,
    val mergeMp4: Boolean,
    val audioMp3: Boolean = false,
)

// MP4 (H.264/AAC) en tête = sortie par défaut compatible partout (pas de .webm).
// On préfère avc1+mp4a et on force le conteneur mp4 (remux rapide, sans ré-encodage).
val QUALITIES = listOf(
    Quality(
        "mp4", "MP4 — meilleure qualité compatible",
        "bestvideo*[vcodec^=avc1]+bestaudio[acodec^=mp4a]/bestvideo*[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/bestvideo*+bestaudio/best",
        mergeMp4 = true,
    ),
    Quality(
        "1080p", "MP4 — Full HD 1080p",
        "bestvideo*[height<=1080][vcodec^=avc1]+bestaudio[acodec^=mp4a]/bestvideo*[height<=1080][ext=mp4]+bestaudio[ext=m4a]/best[height<=1080][ext=mp4]/bestvideo*[height<=1080]+bestaudio/best[height<=1080]",
        mergeMp4 = true,
    ),
    Quality(
        "720p", "MP4 — HD 720p",
        "bestvideo*[height<=720][vcodec^=avc1]+bestaudio[acodec^=mp4a]/bestvideo*[height<=720][ext=mp4]+bestaudio[ext=m4a]/best[height<=720][ext=mp4]/bestvideo*[height<=720]+bestaudio/best[height<=720]",
        mergeMp4 = true,
    ),
    // Qualité absolue (4K/HDR VP9/AV1) : peut sortir en .webm/.mkv, moins compatible.
    Quality("max", "Qualité maximale (peut être .webm)", "bestvideo*+bestaudio/best", mergeMp4 = false),
    Quality("audio", "Audio MP3", "bestaudio/best", mergeMp4 = false, audioMp3 = true),
)

val AUDIO_QUALITY = QUALITIES.first { it.audioMp3 }

/** Filtres de date pour la recherche YouTube (jetons "sp" de l'URL de résultats). */
enum class DateFilter(val label: String, val spToken: String?) {
    ANY("Toutes dates", null),
    WEEK("Cette semaine", "EgIIAw%3D%3D"),
    MONTH("Ce mois-ci", "EgIIBA%3D%3D"),
    YEAR("Cette année", "EgIIBQ%3D%3D"),
}

/** Détecte la plateforme d'origine à partir de l'URL, pour l'organisation par source. */
fun platformOf(url: String): String {
    val u = url.lowercase()
    return when {
        "music.youtube.com" in u -> "YT Music"
        "youtube.com" in u || "youtu.be" in u -> "YouTube"
        "facebook.com" in u || "fb.watch" in u || "fb.com" in u -> "Facebook"
        "instagram.com" in u || "instagr.am" in u -> "Instagram"
        "tiktok.com" in u -> "TikTok"
        "twitter.com" in u || "x.com" in u -> "X"
        "vimeo.com" in u -> "Vimeo"
        "dailymotion.com" in u -> "Dailymotion"
        else -> "Autre"
    }
}

fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return ""
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d h %02d min".format(h, m) else "%d:%02d".format(m, s)
}

fun cleanError(e: Throwable): String {
    val raw = e.message ?: return "Une erreur est survenue."
    val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    if (lines.isEmpty()) return "Une erreur est survenue."
    val line = lines.lastOrNull { it.startsWith("ERROR", ignoreCase = true) }
        ?: lines.lastOrNull { !it.startsWith("WARNING", ignoreCase = true) }
        ?: lines.last()
    return line.removePrefix("ERROR:").trim().take(300)
}
