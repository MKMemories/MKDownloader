package com.mkmemories.mkdownloader

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
}
