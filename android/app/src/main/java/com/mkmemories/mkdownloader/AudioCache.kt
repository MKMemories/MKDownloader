package com.mkmemories.mkdownloader

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Module audio local : extrait le son des vidéos musicales dans un fichier sur
 * le disque (cache LRU). La lecture se fait alors depuis le fichier local →
 * démarrage instantané et fiable (plus de 403 / d'attente de résolution), au
 * lieu de dépendre à chaque fois du flux YouTube (lent + bridé).
 */
object AudioCache {

    private const val MAX_FILES = 120        // ~ quelques centaines de Mo
    private val busy = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun dir(context: Context): File =
        File(context.cacheDir, "audio").apply { mkdirs() }

    private fun keyOf(url: String): String =
        Clips.ytId(url) ?: Integer.toHexString(url.hashCode())

    private fun fileFor(context: Context, url: String): File =
        File(dir(context), keyOf(url) + ".m4a")

    /** Fichier local prêt à jouer, ou null. */
    fun cached(context: Context, url: String): File? =
        fileFor(context, url).takeIf { it.exists() && it.length() > 0 }

    /** Résout puis télécharge l'audio (si absent). Renvoie le fichier local. */
    suspend fun ensure(context: Context, url: String): File? = withContext(Dispatchers.IO) {
        cached(context, url)?.let { return@withContext it }
        val stream = Engine.audioStreamUrl(context, url) ?: return@withContext null
        store(context, url, stream)
    }

    /** Télécharge un flux déjà résolu vers le cache local. */
    fun store(context: Context, url: String, streamUrl: String): File? {
        val key = keyOf(url)
        if (!busy.add(key)) return null          // déjà en cours de téléchargement
        return try {
            val dest = fileFor(context, url)
            if (dest.exists() && dest.length() > 0) return dest
            val tmp = File(dir(context), "$key.part")
            val t0 = android.os.SystemClock.elapsedRealtime()
            val conn = (URL(streamUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/120.0.0.0 Mobile Safari/537.36",
                )
            }
            try {
                if (conn.responseCode !in 200..299) {
                    Logs.w("cache", "téléchargement audio refusé (${conn.responseCode}) — $url")
                    return null
                }
                conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
            } finally {
                conn.disconnect()
            }
            if (tmp.length() <= 0) { tmp.delete(); return null }
            tmp.renameTo(dest)
            Logs.d("cache", "audio mis en cache ${dest.length() / 1024} Ko en ${android.os.SystemClock.elapsedRealtime() - t0}ms — $url")
            trim(context)
            dest
        } catch (e: Exception) {
            Logs.e("cache", "échec mise en cache $url", e)
            null
        } finally {
            busy.remove(key)
        }
    }

    /** Garde les MAX_FILES fichiers les plus récents. */
    private fun trim(context: Context) {
        val files = dir(context).listFiles()?.filter { it.name.endsWith(".m4a") } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_FILES)
            .forEach { it.delete() }
    }
}
