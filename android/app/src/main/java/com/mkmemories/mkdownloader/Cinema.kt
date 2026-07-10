package com.mkmemories.mkdownloader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Bibliothèque cinéma : uniquement des films **libres de droits** (domaine
 * public / Creative Commons) issus de la collection curatée « feature_films »
 * d'Internet Archive.
 *
 * On teste chaque source avant de l'exposer : un film n'est retenu que s'il
 * possède réellement un fichier vidéo lisible (champ « format ») et qu'il est
 * en français, anglais ou arabe. Rien d'autre n'est montré.
 */
object Cinema {

    /** Langues exposées, avec les libellés/codes qu'emploie Internet Archive. */
    enum class Lang(val label: String, val tokens: List<String>) {
        FR("Français", listOf("french", "français", "francais", "fre", "fra")),
        EN("English", listOf("english", "eng")),
        AR("العربية", listOf("arabic", "العربية", "ara")),
    }

    /** Genres proposés, mappés vers les mots-clés « subject » d'Internet Archive. */
    enum class Genre(val label: String, val terms: List<String>?) {
        ALL("Tous", null),
        ACTION("Action", listOf("action")),
        ADVENTURE("Aventure", listOf("adventure")),
        COMEDY("Comédie", listOf("comedy")),
        DRAMA("Drame", listOf("drama")),
        HORROR("Horreur", listOf("horror")),
        SCIFI("Science-fiction", listOf("science fiction", "sci-fi", "scifi")),
        THRILLER("Thriller", listOf("thriller", "suspense", "mystery")),
        CRIME("Policier", listOf("crime", "film noir", "noir", "detective")),
        ROMANCE("Romance", listOf("romance", "romantic")),
        WESTERN("Western", listOf("western")),
        WAR("Guerre", listOf("war")),
        ANIMATION("Animation", listOf("animation", "cartoon", "animated")),
        FAMILY("Famille", listOf("family", "children")),
        DOCUMENTARY("Documentaire", listOf("documentary")),
    }

    enum class SortBy(val label: String, val token: String) {
        POPULAR("Populaires", "downloads desc"),
        RECENT("Récents", "year desc"),
        AZ("A → Z", "titleSorter asc"),
    }

    /** Décennies : axe de classification par année. */
    data class Decade(val label: String, val from: Int?, val to: Int?)

    val DECADES = listOf(
        Decade("Toutes", null, null),
        Decade("2000+", 2000, 2035),
        Decade("Années 90", 1990, 1999),
        Decade("Années 80", 1980, 1989),
        Decade("Années 70", 1970, 1979),
        Decade("Années 60", 1960, 1969),
        Decade("Années 50", 1950, 1959),
        Decade("Années 40", 1940, 1949),
        Decade("Années 30", 1930, 1939),
        Decade("Muet · –1929", 1900, 1929),
    )

    // Formats vidéo réellement lisibles : garantit une source jouable.
    private val VIDEO_FORMATS = listOf(
        "mpeg4", "h.264", "512kb", "hires", "mpeg2", "ogg video",
        "matroska", "webm", "quicktime", "divx", "cinepack", "mp4",
    )

    // Termes de filtrage adulte : on exclut le contenu érotique / nudiste.
    // Utilisés au niveau de la requête (NOT) ET côté app (double sécurité).
    private val ADULT_QUERY_TERMS = listOf(
        "nudist", "nudism", "naturist", "erotic", "erotica", "sexploitation",
        "nudie", "pornographic", "pornography", "striptease",
    )

    // Recherche par frontière de mot pour éviter les faux positifs (ex. « Middlesex »).
    private val ADULT_REGEX = Regex(
        "\\b(" + listOf(
            "nudist", "nudists", "nudism", "naturist", "naturism",
            "erotic", "erotica", "eroticism", "sexploitation",
            "nudie", "nudies", "pornographic", "pornography", "porno", "porn",
            "striptease", "stripper", "hardcore", "softcore", "smut",
            "xxx", "fetish", "orgy", "orgies", "nude", "nudity",
            "erotique", "erotiques", "nudiste", "nudistes", "pornographique",
        ).joinToString("|") + ")\\b",
        RegexOption.IGNORE_CASE,
    )

    private fun isAdult(title: String, subjects: List<String>): Boolean {
        val haystack = (title + " " + subjects.joinToString(" "))
        return ADULT_REGEX.containsMatchIn(haystack)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun langClause(langs: Set<Lang>): String {
        val active = langs.ifEmpty { Lang.values().toSet() }
        val ors = active.flatMap { it.tokens }.map { "language:(\"$it\")" }
        return "(" + ors.joinToString(" OR ") + ")"
    }

    /**
     * Interroge Internet Archive et renvoie les films validés selon les axes
     * de classification choisis (langue, genre, décennie, tri).
     */
    suspend fun browse(
        langs: Set<Lang>,
        genre: Genre = Genre.ALL,
        decade: Decade = DECADES.first(),
        sort: SortBy = SortBy.POPULAR,
        rows: Int = 60,
        page: Int = 1,
    ): List<Film> = withContext(Dispatchers.IO) {
        val clauses = mutableListOf(
            "mediatype:(movies)",
            "collection:(feature_films)",
            langClause(langs),
        )
        genre.terms?.let { terms ->
            clauses += "(" + terms.joinToString(" OR ") { "subject:(\"$it\")" } + ")"
        }
        if (decade.from != null && decade.to != null) {
            clauses += "year:[${decade.from} TO ${decade.to}]"
        }
        // Exclut le contenu adulte directement dans la requête.
        clauses += "NOT (" + ADULT_QUERY_TERMS.joinToString(" OR ") {
            "subject:(\"$it\") OR title:(\"$it\")"
        } + ")"
        val q = clauses.joinToString(" AND ")
        val url = buildString {
            append("https://archive.org/advancedsearch.php?q=").append(enc(q))
            listOf("identifier", "title", "year", "subject", "language", "format", "downloads")
                .forEach { append("&fl[]=").append(it) }
            append("&sort[]=").append(enc(sort.token))
            append("&rows=").append(rows)
            append("&page=").append(page)
            append("&output=json")
        }
        val json = httpGet(url) ?: return@withContext emptyList()
        parseFilms(json, langs)
    }

    private fun httpGet(url: String): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 15000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MKDownloader/1.0 (Android)")
            setRequestProperty("Accept", "application/json")
        }
        conn.inputStream.bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        null
    }

    private fun asList(o: JSONObject, key: String): List<String> {
        if (o.isNull(key)) return emptyList()
        o.optJSONArray(key)?.let { arr ->
            return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
        }
        val s = o.optString(key)
        return if (s.isEmpty()) emptyList() else listOf(s)
    }

    private fun langLabelFor(values: List<String>): String? {
        val low = values.joinToString(" ") { it.lowercase() }
        return when {
            Lang.FR.tokens.any { low.contains(it) } -> Lang.FR.label
            Lang.AR.tokens.any { low.contains(it) } -> Lang.AR.label
            Lang.EN.tokens.any { low.contains(it) } -> Lang.EN.label
            else -> null
        }
    }

    private fun matchesLang(values: List<String>, langs: Set<Lang>): Boolean {
        val active = langs.ifEmpty { Lang.values().toSet() }
        val low = values.joinToString(" ") { it.lowercase() }
        return active.any { l -> l.tokens.any { low.contains(it) } }
    }

    private fun hasPlayableVideo(formats: List<String>): Boolean {
        val low = formats.joinToString(" ") { it.lowercase() }
        return VIDEO_FORMATS.any { low.contains(it) }
    }

    private fun parseFilms(json: String, langs: Set<Lang>): List<Film> {
        val docs = JSONObject(json).optJSONObject("response")?.optJSONArray("docs")
            ?: return emptyList()
        val out = ArrayList<Film>()
        for (i in 0 until docs.length()) {
            val d = docs.optJSONObject(i) ?: continue
            val id = d.optStringOrNull("identifier") ?: continue
            val title = d.optStringOrNull("title")?.trim().takeUnless { it.isNullOrEmpty() } ?: continue
            // Test de la source : on écarte tout ce qui n'a pas de vidéo lisible.
            if (!hasPlayableVideo(asList(d, "format"))) continue
            val languages = asList(d, "language")
            if (!matchesLang(languages, langs)) continue
            val subjects = asList(d, "subject")
            // Sécurité côté app : on écarte tout contenu érotique / nudiste.
            if (isAdult(title, subjects)) continue
            val year = when (val y = d.opt("year")) {
                is Number -> y.toInt()
                is String -> Regex("\\d{4}").find(y)?.value?.toIntOrNull()
                else -> null
            }
            val genres = subjects
                .flatMap { it.split(";", ",") }
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.length <= 24 }
                .distinctBy { it.lowercase() }
                .take(3)
            out += Film(id, title, year, genres, langLabelFor(languages))
        }
        return out
    }
}
