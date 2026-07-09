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

fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return ""
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d h %02d min".format(h, m) else "%d:%02d".format(m, s)
}

fun cleanError(e: Throwable): String {
    val raw = e.message ?: return "Une erreur est survenue."
    val line = raw.lineSequence().firstOrNull { it.contains("ERROR", ignoreCase = true) }
        ?: raw.lineSequence().first()
    return line.removePrefix("ERROR:").trim().take(300)
}
