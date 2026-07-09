package com.mkmemories.mkdownloader

data class VideoItem(
    val url: String,
    val title: String,
    val uploader: String?,
    val durationSec: Int,
    val thumbnail: String?,
)

data class Quality(
    val id: String,
    val label: String,
    val format: String,
    val mergeMp4: Boolean,
    val audioMp3: Boolean = false,
)

val QUALITIES = listOf(
    Quality("max", "Qualité maximale", "bestvideo*+bestaudio/best", mergeMp4 = false),
    Quality(
        "mp4", "Meilleur MP4 (compatible)",
        "bestvideo*[vcodec^=avc1]+bestaudio[acodec^=mp4a]/bestvideo*[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/bestvideo*+bestaudio/best",
        mergeMp4 = true,
    ),
    Quality("1080p", "Full HD 1080p", "bestvideo*[height<=1080]+bestaudio/best[height<=1080]/best", mergeMp4 = true),
    Quality("720p", "HD 720p", "bestvideo*[height<=720]+bestaudio/best[height<=720]/best", mergeMp4 = true),
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
