package com.mkmemories.mkstudio

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Journal technique de l'app, PERSISTÉ sur disque (files/journal.txt) : il
 * survit aux crashs — c'est justement là qu'il sert le plus. Un gestionnaire
 * d'exceptions non rattrapées y consigne les crashs Java avant de laisser
 * l'app mourir. (Un crash natif, lui, laisse au moins la dernière ligne écrite.)
 */
object Logs {

    private val buf = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)
    private var file: File? = null
    private var crashHandlerSet = false

    /** À appeler tôt (Activity/Service). Charge l'historique + arme le crash-handler. */
    @Synchronized
    fun attach(context: Context) {
        if (file == null) {
            val f = File(context.applicationContext.filesDir, "journal.txt")
            file = f
            runCatching {
                if (f.exists()) {
                    val lines = f.readLines()
                    // Garde la fin du journal (et compacte le fichier s'il enfle).
                    lines.takeLast(300).forEach { buf.addLast(it) }
                    if (f.length() > 300_000) f.writeText(buf.joinToString("\n") + "\n")
                }
            }
        }
        if (!crashHandlerSet) {
            crashHandlerSet = true
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                runCatching {
                    add("CRASH ${e::class.java.simpleName}: ${e.message}")
                    add(e.stackTraceToString().take(1500))
                }
                prev?.uncaughtException(t, e)
            }
        }
    }

    @Synchronized
    fun add(line: String) {
        val stamped = "${fmt.format(Date())}  $line"
        buf.addLast(stamped)
        while (buf.size > 500) buf.removeFirst()
        runCatching { file?.appendText(stamped + "\n") }
    }

    /** Rapatrie les messages du moteur natif dans ce journal. */
    fun drainNative() {
        runCatching { NativeSD.getLogs() }.getOrNull()
            ?.lineSequence()
            ?.filter { it.isNotBlank() }
            ?.forEach { add("natif $it") }
    }

    @Synchronized
    fun dump(): String = buf.joinToString("\n").ifEmpty { "(journal vide)" }
}
