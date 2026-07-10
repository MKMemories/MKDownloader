package com.mkmemories.mkdownloader

/**
 * « Machine » d'extraits — aller-retour bijectif entre transcriptions et
 * téléchargements. Chaque vidéo porte un **identifiant unique** (l'ID YouTube
 * à 11 caractères, ou l'URL complète pour les autres plateformes). L'utilisateur
 * repère des passages hors de l'app, puis renvoie une liste de lignes :
 *
 *     <ID>  <début>-<fin>   [libellé optionnel]
 *     dQw4w9WgXcQ  12:40-14:10   Retraites
 *
 * Les temps acceptent h:mm:ss, mm:ss ou des secondes. Séparateurs tolérés :
 * `-`, `–`, `—`, `→`, `..`, `to`, `à`. Les lignes vides ou en `#` sont ignorées.
 */
object Clips {

    data class Clip(val url: String, val startSec: Int, val endSec: Int, val label: String?)

    private val YT_ID = Regex("(?:v=|youtu\\.be/|shorts/|embed/|/live/)([A-Za-z0-9_-]{11})")
    private val BARE_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val URL = Regex("https?://\\S+")
    private val RANGE = Regex(
        "(\\d{1,3}(?::\\d{1,2}){0,2}(?:[.,]\\d+)?)\\s*(?:-|–|—|→|\\.\\.|to|à|=>)\\s*(\\d{1,3}(?::\\d{1,2}){0,2}(?:[.,]\\d+)?)"
    )

    /** Identifiant à afficher dans l'en-tête d'une transcription (ID YouTube sinon URL). */
    fun idFor(url: String): String = ytId(url) ?: url

    fun ytId(url: String): String? {
        YT_ID.find(url)?.let { return it.groupValues[1] }
        if (BARE_ID.matches(url.trim())) return url.trim()
        return null
    }

    /** ID (11c) ou URL → URL canonique téléchargeable. */
    private fun canonicalUrl(token: String): String? {
        val t = token.trim()
        if (BARE_ID.matches(t)) return "https://www.youtube.com/watch?v=$t"
        if (t.startsWith("http")) return t
        return null
    }

    /** h:mm:ss / mm:ss / ss(.ms) → secondes entières, ou null. */
    fun parseTime(s: String): Int? {
        val clean = s.trim().replace(',', '.')
        if (clean.isEmpty()) return null
        val parts = clean.split(":")
        return try {
            when (parts.size) {
                1 -> parts[0].toDouble().toInt()
                2 -> parts[0].toInt() * 60 + parts[1].toDouble().toInt()
                3 -> parts[0].toInt() * 3600 + parts[1].toInt() * 60 + parts[2].toDouble().toInt()
                else -> null
            }
        } catch (_: Exception) { null }
    }

    fun parseLine(line: String): Clip? {
        val raw = line.trim()
        if (raw.isEmpty() || raw.startsWith("#") || raw.startsWith("//")) return null

        // 1) Identifiant : URL explicite en priorité, sinon un token ID à 11 caractères.
        val urlMatch = URL.find(raw)
        val token = urlMatch?.value
            ?: raw.split(Regex("\\s+")).firstOrNull { BARE_ID.matches(it) }
            ?: return null
        val url = canonicalUrl(ytId(token) ?: token) ?: return null

        // 2) Plage horaire, cherchée sur le reste de la ligne (après le token).
        val rest = raw.replace(token, " ")
        val rm = RANGE.find(rest) ?: return null
        val start = parseTime(rm.groupValues[1]) ?: return null
        val end = parseTime(rm.groupValues[2]) ?: return null
        if (end <= start) return null

        // 3) Libellé optionnel : ce qui reste après la plage.
        val label = rest.removeRange(rm.range).replace(Regex("\\s+"), " ").trim()
            .trim('-', '–', '—', '·', ':').trim().ifBlank { null }

        return Clip(url, start, end, label)
    }

    fun parse(text: String): List<Clip> =
        text.split("\n").mapNotNull { parseLine(it) }
}
