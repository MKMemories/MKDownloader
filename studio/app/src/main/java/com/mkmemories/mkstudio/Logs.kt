package com.mkmemories.mkstudio

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Journal technique de l'app : événements côté Kotlin (téléchargements, temps de
 * chargement, durée par étape…) + messages du moteur natif. Consultable et
 * partageable depuis l'écran Créer (🐞 Journal technique).
 */
object Logs {

    private val buf = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)

    @Synchronized
    fun add(line: String) {
        buf.addLast("${fmt.format(Date())}  $line")
        while (buf.size > 500) buf.removeFirst()
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
