package com.mkmemories.mkdownloader

/**
 * Décide si une URL se joue **directement** (fichier hors-ligne, flux radio)
 * ou doit passer par yt-dlp (page YouTube, etc.).
 */
object Playback {

    fun isDirect(url: String): Boolean =
        url.startsWith("content://") || url.startsWith("file://") || Radio.isRadio(url)

    /** URL réelle à donner au lecteur pour une source directe. */
    fun directUri(url: String): String =
        if (Radio.isRadio(url)) Radio.streamOf(url) else url
}
