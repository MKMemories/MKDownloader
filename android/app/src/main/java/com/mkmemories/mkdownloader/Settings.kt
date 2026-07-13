package com.mkmemories.mkdownloader

import android.content.Context
import java.io.File

/**
 * Identifiants optionnels des services FR en clair nécessitant un compte gratuit
 * (TF1/MYTF1, M6/6play). Stockés localement ; transmis à yt-dlp à la lecture.
 * Pour YouTube, on utilise des COOKIES (login/mot de passe n'est plus accepté) :
 * capturés par YoutubeLoginActivity, écrits dans un cookies.txt Netscape.
 */
object Settings {
    private const val PREFS = "mkdl_settings"
    private const val COOKIES = "youtube_cookies.txt"

    data class Creds(val user: String, val pass: String)

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun setCreds(c: Context, key: String, user: String, pass: String) {
        prefs(c).edit().putString("${key}_u", user).putString("${key}_p", pass).apply()
    }

    fun creds(c: Context, key: String): Creds? {
        val u = prefs(c).getString("${key}_u", "").orEmpty()
        val p = prefs(c).getString("${key}_p", "").orEmpty()
        return if (u.isNotEmpty() && p.isNotEmpty()) Creds(u, p) else null
    }

    /** Renvoie les identifiants correspondant à la plateforme d'une URL, si connus. */
    fun credsForUrl(c: Context, url: String): Creds? {
        val u = url.lowercase()
        return when {
            "tf1.fr" in u || "mytf1" in u -> creds(c, "tf1")
            "6play.fr" in u || "m6.fr" in u || "6play" in u -> creds(c, "m6")
            else -> null
        }
    }

    fun isYoutube(url: String): Boolean {
        val u = url.lowercase()
        return "youtube.com" in u || "youtu.be" in u
    }

    /** Fichier cookies YouTube (session connectée), ou null si absent/vide. */
    fun youtubeCookies(c: Context): File? =
        File(c.filesDir, COOKIES).takeIf { it.exists() && it.length() > 0 }

    fun clearYoutubeCookies(c: Context) {
        File(c.filesDir, COOKIES).delete()
    }

    /** Écrit le contenu d'un cookies.txt (import fichier ou capture WebView). */
    fun saveYoutubeCookies(c: Context, text: String) {
        File(c.filesDir, COOKIES).writeText(text)
    }
}
