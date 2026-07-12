package com.mkmemories.mkdownloader

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Journal d'événements en mémoire (tampon circulaire) + export en fichier,
 * pour investiguer les bugs en conditions réelles (ex. lecture des playlists).
 */
object Logs {

    private const val MAX = 3000
    private val buffer = ArrayDeque<String>()
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun d(tag: String, msg: String) = add("·", tag, msg)

    @Synchronized
    fun w(tag: String, msg: String) = add("!", tag, msg)

    @Synchronized
    fun e(tag: String, msg: String, t: Throwable? = null) {
        add("✖", tag, msg + (t?.let { " — ${it.javaClass.simpleName}: ${it.message}" } ?: ""))
    }

    private fun add(level: String, tag: String, msg: String) {
        val line = "${stamp.format(Date())} $level [$tag] $msg"
        buffer.addLast(line)
        while (buffer.size > MAX) buffer.removeFirst()
        android.util.Log.i("MKDL/$tag", msg)
    }

    @Synchronized
    fun text(): String = buffer.joinToString("\n")

    @Synchronized
    fun clear() = buffer.clear()

    /** Écrit le journal dans un fichier partageable et renvoie son Uri (FileProvider). */
    fun writeFile(context: Context): File {
        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        val header = buildString {
            append("MKDownloader — journal\n")
            append("Version : ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) — édition ${BuildConfig.FLAVOR}\n")
            append("Appareil : ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} — Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n")
            append("──────────────────────────────────────────\n\n")
        }
        val file = File(dir, "mkdl-log.txt")
        file.writeText(header + text())
        return file
    }

    /** Ouvre le partage système du journal. */
    fun share(context: Context) {
        val file = writeFile(context)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "MKDownloader — journal")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Exporter le journal").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
