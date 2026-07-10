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

    data class Movie(
        val id: Int,
        val title: String,
        val year: String?,
        val poster: String?,
        val overview: String,
        val rating: Double,
    )

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

    private fun parseMovies(json: String): List<Movie> {
        val arr = JSONObject(json).optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Movie>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optStringOrNull("title") ?: o.optStringOrNull("original_title") ?: continue
            val poster = o.optStringOrNull("poster_path")?.let { IMG + it }
            out += Movie(
                id = o.optInt("id"),
                title = title,
                year = o.optStringOrNull("release_date")?.take(4),
                poster = poster,
                overview = o.optString("overview"),
                rating = o.optDouble("vote_average", 0.0),
            )
        }
        return out
    }
}
