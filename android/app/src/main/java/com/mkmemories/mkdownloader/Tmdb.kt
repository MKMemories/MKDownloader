package com.mkmemories.mkdownloader

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sorties ciné enrichies via The Movie Database (TMDB). La clé API (gratuite)
 * est saisie dans l'app et stockée localement — jamais dans le dépôt.
 * Fournit affiches, synopsis, note, année et la bande-annonce YouTube.
 */
object Tmdb {

    private const val PREFS = "mkdl_tmdb"
    private const val KEY = "api_key"
    private const val IMG = "https://image.tmdb.org/t/p/w342"
    private const val BACKDROP = "https://image.tmdb.org/t/p/w780"
    private const val PROFILE = "https://image.tmdb.org/t/p/w185"
    private const val LOGO = "https://image.tmdb.org/t/p/w92"

    data class Person(val name: String, val role: String?, val photo: String?)
    data class Provider(val name: String, val logo: String?)

    data class MovieDetail(
        val id: Int,
        val title: String,
        val overview: String,
        val backdrop: String?,
        val poster: String?,
        val runtimeMin: Int,
        val genres: List<String>,
        val rating: Double,
        val voteCount: Int,
        val cert: String?,
        val releaseFr: String?,
        val director: String?,
        val cast: List<Person>,
        val providers: List<Provider>,
        val providerLink: String?,
        val similar: List<Movie>,
    )

    data class Movie(
        val id: Int,
        val title: String,
        val year: String?,
        val releaseDate: String?,
        val poster: String?,
        val overview: String,
        val rating: Double,
    )

    /** Formate une date « yyyy-MM-dd » en français (ex. « 5 août 2026 »). */
    fun frenchDate(raw: String?, short: Boolean = false): String? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val d = java.time.LocalDate.parse(raw.take(10))
            val pattern = if (short) "d MMM yyyy" else "d MMMM yyyy"
            d.format(java.time.format.DateTimeFormatter.ofPattern(pattern, java.util.Locale.FRENCH))
        }.getOrNull()
    }

    enum class Category(val label: String, val path: String) {
        NOW("À l'affiche", "movie/now_playing"),
        UPCOMING("Prochaines sorties", "movie/upcoming"),
        POPULAR("Populaires", "movie/popular"),
        TOP("Mieux notés", "movie/top_rated"),
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun key(c: Context): String = prefs(c).getString(KEY, "").orEmpty()
    fun hasKey(c: Context): Boolean = key(c).isNotBlank()
    fun setKey(c: Context, k: String) = prefs(c).edit().putString(KEY, k.trim()).apply()

    suspend fun movies(context: Context, category: Category, page: Int = 1): List<Movie> =
        withContext(Dispatchers.IO) {
            val k = key(context).ifBlank { return@withContext emptyList() }
            val url = "https://api.themoviedb.org/3/${category.path}" +
                "?api_key=$k&language=fr-FR&region=FR&page=$page"
            val json = httpGet(url) ?: return@withContext emptyList()
            parseMovies(json)
        }

    /** Clé YouTube de la bande-annonce (fr puis en), ou null. */
    suspend fun trailerYoutubeKey(context: Context, movieId: Int): String? =
        withContext(Dispatchers.IO) {
            val k = key(context).ifBlank { return@withContext null }
            fun pick(lang: String): String? {
                val json = httpGet(
                    "https://api.themoviedb.org/3/movie/$movieId/videos?api_key=$k&language=$lang"
                ) ?: return null
                val arr = JSONObject(json).optJSONArray("results") ?: return null
                var teaser: String? = null
                for (i in 0 until arr.length()) {
                    val v = arr.optJSONObject(i) ?: continue
                    if (v.optString("site") != "YouTube") continue
                    val type = v.optString("type")
                    val key = v.optStringOrNull("key") ?: continue
                    if (type == "Trailer") return key
                    if (type == "Teaser" && teaser == null) teaser = key
                }
                return teaser
            }
            pick("fr-FR") ?: pick("en-US")
        }

    /**
     * Date de sortie **officielle en salle en France** (type théâtral), formatée
     * en français. Repli sur une sortie limitée puis toute date FR si besoin.
     */
    suspend fun frenchTheatricalDate(context: Context, movieId: Int): String? =
        withContext(Dispatchers.IO) {
            val k = key(context).ifBlank { return@withContext null }
            val json = httpGet("https://api.themoviedb.org/3/movie/$movieId/release_dates?api_key=$k")
                ?: return@withContext null
            val results = JSONObject(json).optJSONArray("results") ?: return@withContext null
            for (i in 0 until results.length()) {
                val c = results.optJSONObject(i) ?: continue
                if (c.optString("iso_3166_1") != "FR") continue
                val dates = c.optJSONArray("release_dates") ?: continue
                var limited: String? = null
                var any: String? = null
                for (j in 0 until dates.length()) {
                    val rd = dates.optJSONObject(j) ?: continue
                    val date = rd.optStringOrNull("release_date") ?: continue
                    if (any == null) any = date
                    when (rd.optInt("type")) {
                        3 -> return@withContext frenchDate(date)   // sortie en salle
                        2 -> if (limited == null) limited = date    // sortie limitée
                    }
                }
                return@withContext frenchDate(limited ?: any)
            }
            null
        }

    private fun httpGet(url: String): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 15000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
        }
        if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() }
        else null
    } catch (e: Exception) {
        null
    }

    private fun movieFrom(o: JSONObject): Movie? {
        val title = o.optStringOrNull("title") ?: o.optStringOrNull("original_title") ?: return null
        val release = o.optStringOrNull("release_date")
        return Movie(
            id = o.optInt("id"),
            title = title,
            year = release?.take(4),
            releaseDate = release,
            poster = o.optStringOrNull("poster_path")?.let { IMG + it },
            overview = o.optString("overview"),
            rating = o.optDouble("vote_average", 0.0),
        )
    }

    private fun parseMovies(json: String): List<Movie> {
        val arr = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::movieFrom) }
    }

    /** Fiche complète : casting, durée, genres, « où regarder », similaires — un seul appel. */
    suspend fun detail(context: Context, id: Int): MovieDetail? = withContext(Dispatchers.IO) {
        val k = key(context).ifBlank { return@withContext null }
        val url = "https://api.themoviedb.org/3/movie/$id?api_key=$k&language=fr-FR" +
            "&append_to_response=credits,similar,release_dates,watch/providers"
        val json = httpGet(url) ?: return@withContext null
        val o = JSONObject(json)
        val title = o.optStringOrNull("title") ?: o.optStringOrNull("original_title") ?: return@withContext null

        val genres = o.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optStringOrNull("name") }
        }.orEmpty()

        // Casting + réalisateur.
        val credits = o.optJSONObject("credits")
        val cast = credits?.optJSONArray("cast")?.let { arr ->
            (0 until minOf(arr.length(), 15)).mapNotNull { i ->
                val c = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = c.optStringOrNull("name") ?: return@mapNotNull null
                Person(name, c.optStringOrNull("character"), c.optStringOrNull("profile_path")?.let { PROFILE + it })
            }
        }.orEmpty()
        val director = credits?.optJSONArray("crew")?.let { arr ->
            (0 until arr.length()).firstNotNullOfOrNull { i ->
                val c = arr.optJSONObject(i)
                if (c?.optString("job") == "Director") c.optStringOrNull("name") else null
            }
        }

        // Date de sortie France + classification d'âge.
        var releaseFr: String? = null
        var cert: String? = null
        o.optJSONObject("release_dates")?.optJSONArray("results")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                if (c.optString("iso_3166_1") != "FR") continue
                val dates = c.optJSONArray("release_dates") ?: continue
                for (j in 0 until dates.length()) {
                    val rd = dates.optJSONObject(j) ?: continue
                    if (releaseFr == null || rd.optInt("type") == 3) releaseFr = rd.optStringOrNull("release_date")
                    rd.optStringOrNull("certification")?.let { if (it.isNotBlank()) cert = it }
                }
            }
        }

        // Où regarder (France) — données JustWatch via TMDB.
        val fr = o.optJSONObject("watch/providers")?.optJSONObject("results")?.optJSONObject("FR")
        val providers = LinkedHashMap<String, String?>()
        listOf("flatrate", "free", "ads", "rent", "buy").forEach { kind ->
            fr?.optJSONArray(kind)?.let { arr ->
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    val name = p.optStringOrNull("provider_name") ?: continue
                    if (!providers.containsKey(name)) {
                        providers[name] = p.optStringOrNull("logo_path")?.let { LOGO + it }
                    }
                }
            }
        }

        val similar = o.optJSONObject("similar")?.optJSONArray("results")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::movieFrom) }
        }.orEmpty()

        MovieDetail(
            id = id,
            title = title,
            overview = o.optString("overview"),
            backdrop = o.optStringOrNull("backdrop_path")?.let { BACKDROP + it },
            poster = o.optStringOrNull("poster_path")?.let { IMG + it },
            runtimeMin = o.optInt("runtime"),
            genres = genres,
            rating = o.optDouble("vote_average", 0.0),
            voteCount = o.optInt("vote_count"),
            cert = cert,
            releaseFr = frenchDate(releaseFr),
            director = director,
            cast = cast,
            providers = providers.map { Provider(it.key, it.value) },
            providerLink = fr?.optStringOrNull("link"),
            similar = similar,
        )
    }
}
