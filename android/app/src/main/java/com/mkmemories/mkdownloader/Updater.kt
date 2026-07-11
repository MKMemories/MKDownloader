package com.mkmemories.mkdownloader

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mise à jour in-app : va chercher la dernière version publiée dans la release
 * GitHub « latest », compare à la version installée, télécharge l'APK du bon
 * flavor et lance l'installateur système.
 *
 * Le CI publie à chaque build un `update.json` ({versionCode, versionName}) et
 * les APK à des URL stables (`releases/latest/download/<fichier>`).
 */
object Updater {

    private const val REPO = "MKMemories/MKDownloader"
    private const val JSON_URL = "https://github.com/$REPO/releases/latest/download/update.json"

    data class Update(val versionCode: Int, val versionName: String, val apkUrl: String)

    private fun apkName(): String =
        if (BuildConfig.FLAVOR == "analyste") "LAnalyste2027.apk" else "MKDownloader.apk"

    /** Renvoie la mise à jour disponible (plus récente que l'installée) ou null. */
    suspend fun check(): Update? = withContext(Dispatchers.IO) {
        val txt = runCatching { fetchText(JSON_URL) }.getOrNull() ?: return@withContext null
        val o = runCatching { JSONObject(txt) }.getOrNull() ?: return@withContext null
        val vc = o.optInt("versionCode", -1)
        if (vc <= BuildConfig.VERSION_CODE) return@withContext null
        Update(
            versionCode = vc,
            versionName = o.optString("versionName", vc.toString()),
            apkUrl = "https://github.com/$REPO/releases/latest/download/${apkName()}",
        )
    }

    private fun fetchText(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 15_000; instanceFollowRedirects = true
        }
        try {
            conn.inputStream.use { return it.readBytes().decodeToString() }
        } finally {
            conn.disconnect()
        }
    }

    /** Télécharge l'APK dans le cache et renvoie le fichier. onProgress en %. */
    suspend fun download(context: Context, url: String, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, apkName())
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000; readTimeout = 60_000; instanceFollowRedirects = true
            }
            try {
                val total = conn.contentLength.toLong()
                conn.inputStream.use { input ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        var n: Int
                        var last = -1
                        while (input.read(buf).also { n = it } >= 0) {
                            o.write(buf, 0, n)
                            read += n
                            if (total > 0) {
                                val p = (read * 100 / total).toInt()
                                if (p != last) { last = p; onProgress(p) }
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            out
        }

    /** Lance l'installateur système sur l'APK téléchargé. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
