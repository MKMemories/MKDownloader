package com.mkmemories.mkdownloader

import android.content.Context

/**
 * ⛓ Plan de téléchargement : un fichier texte structuré, rempli à la main
 * (souvent à partir d'un catalogue.csv ou des transcriptions exportées), que
 * l'app consomme pour lancer les téléchargements EN CHAÎNE — vidéos entières
 * ou extraits — sans les saisir une par une.
 *
 * Format (voir [template]) :
 *   qualite: mp4 | 1080p | 720p | max | mp3 | flac
 *   LIEN
 *   LIEN | 12:40-12:55
 *   LIEN | 12:40-12:55 | 31:02-31:15
 */
object Plan {

    /** Une ligne du plan : la vidéo, et zéro, une ou plusieurs plages. */
    data class Item(val url: String, val ranges: List<Pair<Int, Int>>)

    data class Parsed(
        val quality: Quality,
        val items: List<Item>,
        val errors: List<String>,     // « ligne N : explication »
    ) {
        val clipCount: Int get() = items.sumOf { it.ranges.size }
        val fullCount: Int get() = items.count { it.ranges.isEmpty() }
    }

    const val MAX_LINES = 100

    /** Le modèle à remplir, écrit dans Téléchargements/MKDownloader/. */
    fun template(): String = """
        |# ══════════════════════════════════════════════════
        |#  PLAN DE TÉLÉCHARGEMENT — MKDownloader
        |# ══════════════════════════════════════════════════
        |# Remplis ce fichier puis réimporte-le :
        |# ⚙ Paramètres → ⛓ Plan de téléchargement → Choisir le fichier.
        |#
        |# Règles :
        |#  • Les lignes commençant par # sont ignorées (commentaires).
        |#  • qualite: mp4 | 1080p | 720p | max | mp3 | flac
        |#    (une seule fois, vaut pour tout le plan ; mp4 par défaut)
        |#  • Ensuite, UNE LIGNE PAR VIDÉO :
        |#      LIEN                                   → vidéo entière
        |#      LIEN | 12:40-12:55                     → un extrait
        |#      LIEN | 12:40-12:55 | 31:02-31:15       → plusieurs extraits
        |#  • Minutages en m:ss ou h:mm:ss, séparateur « - ».
        |#  • $MAX_LINES lignes de vidéos maximum par plan.
        |
        |qualite: mp4
        |
        |https://www.youtube.com/watch?v=EXEMPLE_A_REMPLACER
        |https://www.youtube.com/watch?v=EXEMPLE2 | 12:40-12:55
        |https://www.youtube.com/watch?v=EXEMPLE3 | 1:02:10-1:02:45 | 1:15:00-1:15:20
        |
    """.trimMargin()

    fun writeTemplate(context: Context): String? = Backup.writeTextFile(
        context.applicationContext, "MKDownloader", "PLAN - modele a remplir.txt", template(),
    )

    /** Analyse le plan ; les lignes invalides sont listées, les valides gardées. */
    fun parse(text: String): Parsed {
        var quality = QUALITIES.first()
        val items = mutableListOf<Item>()
        val errors = mutableListOf<String>()
        text.lines().forEachIndexed { idx, raw ->
            val line = raw.trim()
            val n = idx + 1
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val lower = line.lowercase()
            if (lower.startsWith("qualite:") || lower.startsWith("qualité:")) {
                val v = line.substringAfter(":").trim().lowercase()
                val id = when (v) {
                    "mp3", "audio" -> "audio"
                    else -> v
                }
                val q = QUALITIES.find { it.id == id }
                if (q != null) quality = q
                else errors += "ligne $n : qualité inconnue « $v » (mp4, 1080p, 720p, max, mp3, flac)"
                return@forEachIndexed
            }
            val parts = line.split("|").map { it.trim() }.filter { it.isNotEmpty() }
            val url = parts.firstOrNull().orEmpty()
            if (!url.startsWith("http")) {
                errors += "ligne $n : lien attendu en début de ligne"
                return@forEachIndexed
            }
            if (items.size >= MAX_LINES) {
                errors += "ligne $n : plan limité à $MAX_LINES vidéos — ligne ignorée"
                return@forEachIndexed
            }
            val ranges = if (parts.size <= 1) emptyList() else runCatching {
                Extracts.parseRanges(parts.drop(1).joinToString(","))
            }.getOrElse {
                errors += "ligne $n : ${it.message}"
                return@forEachIndexed
            }
            items += Item(url, ranges)
        }
        return Parsed(quality, items, errors)
    }

    /** Met tout le plan en file. Renvoie le nombre de téléchargements créés. */
    fun launch(context: Context, parsed: Parsed): Int {
        var count = 0
        parsed.items.forEach { it0 ->
            val item = VideoItem(
                url = it0.url,
                title = it0.url.removePrefix("https://").removePrefix("http://")
                    .removePrefix("www.").take(70),
                uploader = null, durationSec = 0, thumbnail = null,
            )
            if (it0.ranges.isEmpty()) {
                Downloads.start(context, item, parsed.quality); count++
            } else {
                it0.ranges.forEach { (a, b) ->
                    Downloads.start(context, item, parsed.quality, a, b); count++
                }
            }
        }
        return count
    }
}
