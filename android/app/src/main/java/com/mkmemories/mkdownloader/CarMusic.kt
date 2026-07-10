package com.mkmemories.mkdownloader

/**
 * Contenu curé pour Android Auto : au lieu des « compilations interminables »,
 * on met en avant des artistes choisis + la playlist perso. Les cartes affichent
 * une pochette aléatoire à chaque affichage (hero qui change → pas de monotonie).
 */
object CarMusic {

    /** Playlist YouTube Music personnelle de l'utilisateur. */
    const val PLAYLIST_URL =
        "https://music.youtube.com/playlist?list=PLPGpAux803kNxzyc662JgDXq1qkK8cSfN"

    const val MIX = "mix"

    data class Cat(
        val id: String,
        val label: String,
        val query: String,      // requête de recherche, ou URL si isPlaylist
        val isPlaylist: Boolean,
    )

    val CATS = listOf(
        Cat(MIX, "Mix du moment 🔀", "", false),
        Cat("cat:guetta", "David Guetta", "David Guetta best songs", false),
        Cat("cat:snake", "DJ Snake", "DJ Snake best songs", false),
        Cat("cat:jul", "Jul", "Jul meilleurs titres", false),
        Cat("cat:myplaylist", "Ma playlist ⭐", PLAYLIST_URL, true),
    )

    val ARTIST_CATS = CATS.filter { it.id != MIX }

    fun catById(id: String): Cat? = CATS.firstOrNull { it.id == id }
}
