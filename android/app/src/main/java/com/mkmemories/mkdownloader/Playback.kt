package com.mkmemories.mkdownloader

/**
 * Décide si une URL se joue **directement** (fichier hors-ligne, flux radio)
 * ou doit passer par yt-dlp (page YouTube, etc.).
 */
object Playback {

    const val YTDLP = "ytdlp"

    fun isDirect(url: String): Boolean =
        url.startsWith("content://") || url.startsWith("file://") || Radio.isRadio(url)

    /** URL réelle à donner au lecteur pour une source directe. */
    fun directUri(url: String): String =
        if (Radio.isRadio(url)) Radio.streamOf(url) else url

    /**
     * URI à mettre dans un MediaItem. Les sources directes (radio, hors-ligne)
     * sont jouées telles quelles ; les autres portent le schéma « ytdlp: » et
     * sont résolues **paresseusement** par le ResolvingDataSource — d'où un
     * démarrage instantané et un préchargement de la piste suivante.
     */
    fun playbackUri(url: String): String =
        if (isDirect(url)) directUri(url) else "$YTDLP:$url"
}
