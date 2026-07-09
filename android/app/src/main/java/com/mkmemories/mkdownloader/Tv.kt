package com.mkmemories.mkdownloader

/**
 * @param url    Source du direct.
 * @param resolve true  → page à résoudre par yt-dlp sur l'appareil (YouTube live,
 *                        france.tv…), la plus robuste (URLs maintenues à jour) ;
 *                false → flux HLS (.m3u8) lu directement.
 * @param note   Info éventuelle (géo/abonnement).
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
 * Liste CURÉE des chaînes demandées. On privilégie les directs 24/7 diffusés sur
 * YouTube (mondiaux, sans DRM, résolus par yt-dlp) ; les chaînes en DRM (TF1,
 * France 2/4/5, M6) ou géo-restreintes (RAI, BBC, CNN) sont signalées.
 */
object Tv {
    val CHANNELS: List<TvChannel> = listOf(
        // --- France : info en continu (directs YouTube fiables) ---
        TvChannel("France Info", "France", "https://www.youtube.com/@franceinfo/live", resolve = true),
        TvChannel("BFM TV", "France", "https://www.youtube.com/@BFMTV/live", resolve = true),
        TvChannel("LCI", "France", "https://www.youtube.com/@LCI/live", resolve = true),

        // --- France : chaînes généralistes (france.tv / DRM ou abonnement) ---
        TvChannel("TF1", "France", "https://www.tf1.fr/tf1/direct", resolve = true, note = "Compte MYTF1 requis (voir Comptes)"),
        TvChannel("France 2", "France", "https://www.france.tv/france-2/direct.html", resolve = true, note = "Peut exiger la France / VPN"),
        TvChannel("France 4", "France", "https://www.france.tv/france-4/direct.html", resolve = true, note = "Peut exiger la France / VPN"),
        TvChannel("France 5", "France", "https://www.france.tv/france-5/direct.html", resolve = true, note = "Peut exiger la France / VPN"),
        TvChannel("M6", "France", "https://www.6play.fr/m6/direct", resolve = true, note = "Compte 6play requis (voir Comptes)"),

        // --- International ---
        TvChannel("CNN", "International", "https://www.youtube.com/@CNN/live", resolve = true, note = "Direct selon disponibilité YouTube"),
        TvChannel("BBC News", "International", "https://www.youtube.com/@BBCNews/live", resolve = true, note = "Peut être géo-restreint"),
        TvChannel("RAI 1", "International", "https://www.raiplay.it/dirette/rai1", resolve = true, note = "Italie / VPN Italie"),

        // --- Tunisie (directs YouTube) ---
        TvChannel("Télévision Tunisienne (Watania 1)", "Tunisie", "https://www.youtube.com/@Watania1/live", resolve = true),
        TvChannel("Elhiwar Ettounsi", "Tunisie", "https://www.youtube.com/@elhiwarettounsi/live", resolve = true),
        TvChannel("Nessma TV", "Tunisie", "https://www.youtube.com/@NessmaTv/live", resolve = true),
    )
}
