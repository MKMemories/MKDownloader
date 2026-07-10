package com.mkmemories.mkdownloader

import android.content.Context
import org.json.JSONArray

/**
 * Personnalités politiques de premier plan pour la recherche thématique
 * (outil d'analyse). Liste **neutre et transversale** (tous bords), enrichissable
 * par l'utilisateur dans l'app.
 */
object Actors {
    private const val PREFS = "mkdl_actors"
    private const val KEY = "custom"

    /** Presets, par ordre alphabétique, sans étiquette de bord. */
    val PRESETS = listOf(
        "Emmanuel Macron", "Marine Le Pen", "Jean-Luc Mélenchon", "Jordan Bardella",
        "Gabriel Attal", "Édouard Philippe", "Bruno Retailleau", "Marion Maréchal",
        "François Ruffin", "Manuel Bompard", "Fabien Roussel", "Olivier Faure",
        "Raphaël Glucksmann", "Sandrine Rousseau", "Marine Tondelier", "Éric Zemmour",
        "Laurent Wauquiez", "Gérald Darmanin", "François Bayrou", "Marine Le Pen",
    ).distinct()

    fun custom(c: Context): List<String> {
        val raw = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    /** Liste complète : personnalisées d'abord (récentes), puis presets. */
    fun all(c: Context): List<String> = (custom(c) + PRESETS).distinct()

    fun add(c: Context, name: String) {
        val n = name.trim()
        if (n.isBlank()) return
        val list = custom(c).filterNot { it.equals(n, ignoreCase = true) }.toMutableList()
        list.add(0, n)
        while (list.size > 40) list.removeAt(list.size - 1)
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}
