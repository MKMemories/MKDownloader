package com.mkmemories.mkdownloader

/**
 * Radios françaises en direct, jouées via le service audio (fond, mini-lecteur,
 * Android Auto). Les flux sont directs (HTTPS) : ils portent le préfixe
 * « radio: » pour être joués tels quels, sans passer par yt-dlp.
 *
 * L'ordre reflète la popularité, avec **Radio Nova en tête** (choix éditorial).
 * Les stations assimilées à l'extrême droite (Europe 1, Sud Radio, Radio
 * Courtoisie…) sont volontairement exclues.
 */
object Radio {

    const val PREFIX = "radio:"

    data class Station(val name: String, val stream: String, val genre: String)

    val STATIONS = listOf(
        Station("Radio Nova", "https://novazz.ice.infomaniak.ch/novazz-128.mp3", "Éclectique"),
        Station("France Inter", "https://icecast.radiofrance.fr/franceinter-midfi.mp3", "Généraliste"),
        Station("NRJ", "https://scdn.nrjaudio.fm/adwz1/fr/30001/mp3_128.mp3", "Hits"),
        Station("France Info", "https://icecast.radiofrance.fr/franceinfo-midfi.mp3", "Info"),
        Station("RMC", "https://audio.bfmtv.com/rmcradio_128.mp3", "Talk & sport"),
        Station("Nostalgie", "https://scdn.nrjaudio.fm/adwz1/fr/30601/mp3_128.mp3", "Hits d'hier"),
        Station("Skyrock", "https://icecast.skyrock.net/s/natio_mp3_128k", "Rap & R'n'B"),
        Station("Chérie FM", "https://scdn.nrjaudio.fm/adwz1/fr/30201/mp3_128.mp3", "Pop"),
        Station("FIP", "https://icecast.radiofrance.fr/fip-midfi.mp3", "Éclectique"),
        Station("Rire & Chansons", "https://scdn.nrjaudio.fm/adwz1/fr/30401/mp3_128.mp3", "Humour"),
        Station("France Culture", "https://icecast.radiofrance.fr/franceculture-midfi.mp3", "Culture"),
        Station("France Musique", "https://icecast.radiofrance.fr/francemusique-midfi.mp3", "Classique"),
        Station("Mouv'", "https://icecast.radiofrance.fr/mouv-midfi.mp3", "Rap & culture urbaine"),
        Station("TSF Jazz", "https://tsfjazz.ice.infomaniak.ch/tsfjazz-high.mp3", "Jazz"),
    )

    fun Station.toVideoItem(): VideoItem = VideoItem(
        url = PREFIX + stream,
        title = name,
        uploader = "Radio · $genre",
        durationSec = 0,
        thumbnail = null,
    )

    fun stations(): List<VideoItem> = STATIONS.map { it.toVideoItem() }

    fun isRadio(url: String): Boolean = url.startsWith(PREFIX)

    fun streamOf(url: String): String = url.removePrefix(PREFIX)
}
