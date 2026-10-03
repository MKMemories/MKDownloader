package com.mkmemories.mkdownloader

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 📇 Catalogue de chaîne : extrait pour CHAQUE vidéo d'une chaîne le lien, le
 * titre, la description, la date de mise en ligne, les tags et le lien de la
 * vignette — SANS télécharger les vidéos. Pensé pour référencer les vidéos sur
 * un site web (creteimmersion.com, tounesna.org…).
 *
 * Produit UN SEUL fichier : Téléchargements/MKDownloader/Catalogue/
 * « Catalogue - <chaîne>.zip », contenant :
 *  • LISEZMOI.txt   — explication du contenu du zip ;
 *  • catalogue.csv  — tableur (séparateur « ; », compatible Excel français) ;
 *  • catalogue.json — intégration dans un site (tableau d'objets) ;
 *  • catalogue.html — galerie prête à coller (vignette cliquable + titre + date).
 *
 * Le zip est réécrit toutes les 25 vidéos : une extraction interrompue laisse
 * quand même un catalogue partiel utilisable.
 */
object Catalog {

    /** Une fiche extraite (tout ce qu'il faut pour référencer la vidéo). */
    data class Row(
        val id: String,
        val url: String,
        val title: String,
        val date: String,          // AAAA-MM-JJ
        val durationSec: Long,
        val views: Long,
        val tags: List<String>,
        val thumbnail: String,
        val description: String,
    )

    data class Result(
        val zipName: String,       // nom du zip sous Catalogue/
        val count: Int,            // fiches extraites
        val errors: Int,           // vidéos en échec (privées, supprimées…)
        val cancelled: Boolean,
        val zipUri: String?,       // pour le bouton Partager
    )

    /** Demande d'arrêt (le fichier partiel est écrit avant de rendre la main). */
    @Volatile var stopRequested = false

    private const val FLUSH_EVERY = 25

    /**
     * Extrait les métadonnées de chaque entrée (une requête yt-dlp par vidéo,
     * ~1-2 s chacune). [onProgress] est appelé depuis le thread IO avec
     * (faites, total, titre en cours).
     */
    suspend fun build(
        context: Context,
        channelName: String,
        entries: List<Backup.Entry>,
        onProgress: (Int, Int, String) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        Engine.ensureReady(app)
        stopRequested = false
        val zipName = "Catalogue - " + Backup.sanitizeName(channelName) + ".zip"
        val rows = mutableListOf<Row>()
        var errors = 0
        var zipUri: String? = null

        fun flush(partial: Boolean) {
            if (rows.isEmpty()) return
            val bytes = zipBytes(channelName, rows, errors, partial)
            zipUri = Backup.writeBinaryFile(
                app, "MKDownloader/Catalogue", zipName, bytes, "application/zip",
            )
        }

        for ((i, e) in entries.withIndex()) {
            if (stopRequested) break
            onProgress(i, entries.size, e.title)
            val row = runCatching { fetchOne(app, e) }.getOrNull()
            if (row != null) rows += row else errors++
            if (rows.size % FLUSH_EVERY == 0) flush(partial = true)
        }
        flush(partial = stopRequested)
        onProgress(entries.size, entries.size, "")
        Result(zipName, rows.size, errors, stopRequested, zipUri)
    }

    /** Métadonnées complètes d'UNE vidéo (sans téléchargement). */
    private fun fetchOne(context: Context, e: Backup.Entry): Row {
        val request = YoutubeDLRequest(e.url).apply {
            addOption("--dump-single-json")
            addOption("--no-playlist")
            addOption("--no-warnings")
            addOption("--extractor-args", Engine.YT_ARGS)
            Settings.cookiesForUrl(context, e.url)?.let { addOption("--cookies", it.absolutePath) }
        }
        val out = YoutubeDL.getInstance().execute(request, null, null).out
        val start = out.indexOf('{')
        require(start >= 0) { "Vidéo illisible" }
        val o = JSONObject(out.substring(start))
        val id = o.optString("id").ifBlank { e.id }
        val tags = mutableListOf<String>()
        o.optJSONArray("tags")?.let { for (i in 0 until it.length()) tags += it.optString(i) }
        val raw = o.optString("upload_date")
        val date = if (raw.length == 8) {
            "${raw.substring(0, 4)}-${raw.substring(4, 6)}-${raw.substring(6, 8)}"
        } else raw
        return Row(
            id = id,
            url = o.optString("webpage_url").ifBlank { "https://www.youtube.com/watch?v=$id" },
            title = o.optString("title").ifBlank { e.title },
            date = date,
            durationSec = o.optLong("duration", 0L),
            views = o.optLong("view_count", 0L),
            tags = tags.filter { it.isNotBlank() },
            thumbnail = o.optString("thumbnail")
                .ifBlank { "https://i.ytimg.com/vi/$id/hqdefault.jpg" },
            description = o.optString("description"),
        )
    }

    // ---------- Formats de sortie ----------

    /** Assemble le zip : LISEZMOI + csv + json + html. */
    private fun zipBytes(
        channelName: String,
        rows: List<Row>,
        errors: Int,
        partial: Boolean,
    ): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(buf).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
            put("LISEZMOI.txt", readme(channelName, rows, errors, partial))
            put("catalogue.csv", toCsv(rows))
            put("catalogue.json", toJson(rows))
            put("catalogue.html", toHtml(channelName, rows))
        }
        return buf.toByteArray()
    }

    /** Mode d'emploi du zip, en clair. */
    private fun readme(
        channelName: String,
        rows: List<Row>,
        errors: Int,
        partial: Boolean,
    ): String = buildString {
        val now = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.FRANCE)
            .format(java.util.Date())
        append("📇 CATALOGUE DE CHAÎNE YOUTUBE\n")
        append("==============================\n\n")
        append("Chaîne   : ").append(channelName).append("\n")
        append("Généré le : ").append(now).append(" par MKDownloader\n")
        append("Vidéos   : ").append(rows.size).append(" fiches")
        if (errors > 0) append(" (").append(errors).append(" vidéos illisibles ignorées)")
        append("\n")
        if (partial) append("⚠ Extraction interrompue : ce catalogue est PARTIEL.\n")
        append("\nPour chaque vidéo : lien YouTube, titre, description, date de mise en\n")
        append("ligne, tags et lien de la vignette (image hébergée par YouTube, à\n")
        append("utiliser directement dans une balise <img>).\n")
        append("\nCONTENU DU ZIP\n")
        append("--------------\n\n")
        append("• catalogue.csv\n")
        append("  Tableur, à ouvrir dans Excel, LibreOffice ou Google Sheets.\n")
        append("  Séparateur « ; » (Excel français l'ouvre d'un double-clic).\n")
        append("  Colonnes : Titre ; Lien ; Date ; Durée ; Vues ; Tags ; Vignette ;\n")
        append("  Description.\n\n")
        append("• catalogue.json\n")
        append("  Le même contenu pour un site web ou un script : tableau d'objets\n")
        append("  { id, titre, lien, date, duree_sec, vues, tags[], vignette,\n")
        append("  description }.\n\n")
        append("• catalogue.html\n")
        append("  Galerie prête à l'emploi : vignettes cliquables (titre + date +\n")
        append("  durée) qui ouvrent les vidéos sur YouTube. À coller tel quel dans\n")
        append("  une page de site, ou à ouvrir dans un navigateur pour vérifier.\n\n")
        append("Astuce : les liens de vignettes pointent vers les images officielles\n")
        append("YouTube (i.ytimg.com) — rien à héberger de ton côté.\n")
    }

    private fun fmtDuration(s: Long): String = when {
        s <= 0 -> ""
        s >= 3600 -> String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        else -> String.format("%d:%02d", s / 60, s % 60)
    }

    /** CSV « ; » (Excel FR) avec BOM UTF-8 ; champs entre guillemets doublés. */
    private fun toCsv(rows: List<Row>): String {
        fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val sb = StringBuilder("﻿")
        sb.append("Titre;Lien;Date;Durée;Vues;Tags;Vignette;Description\r\n")
        rows.forEach { r ->
            sb.append(q(r.title)).append(';')
                .append(q(r.url)).append(';')
                .append(q(r.date)).append(';')
                .append(q(fmtDuration(r.durationSec))).append(';')
                .append(r.views).append(';')
                .append(q(r.tags.joinToString(", "))).append(';')
                .append(q(r.thumbnail)).append(';')
                .append(q(r.description)).append("\r\n")
        }
        return sb.toString()
    }

    private fun toJson(rows: List<Row>): String {
        val arr = JSONArray()
        rows.forEach { r ->
            arr.put(
                JSONObject().apply {
                    put("id", r.id)
                    put("titre", r.title)
                    put("lien", r.url)
                    put("date", r.date)
                    put("duree_sec", r.durationSec)
                    put("vues", r.views)
                    put("tags", JSONArray(r.tags))
                    put("vignette", r.thumbnail)
                    put("description", r.description)
                },
            )
        }
        return arr.toString(2)
    }

    private fun esc(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")

    /** Galerie HTML autonome, prête à coller dans une page de site. */
    private fun toHtml(channelName: String, rows: List<Row>): String = buildString {
        append("<!-- Catalogue « ").append(esc(channelName))
        append(" » — ").append(rows.size).append(" vidéos — généré par MKDownloader -->\n")
        append("<style>.mk-videos{display:grid;grid-template-columns:repeat(auto-fill,minmax(240px,1fr));gap:16px}")
        append(".mk-videos article{font-family:sans-serif}.mk-videos img{width:100%;border-radius:8px;display:block}")
        append(".mk-videos h3{font-size:15px;margin:8px 0 2px;line-height:1.3}")
        append(".mk-videos h3 a{color:inherit;text-decoration:none}")
        append(".mk-videos .mk-date{font-size:12px;color:#777;margin:0}</style>\n")
        append("<div class=\"mk-videos\">\n")
        rows.forEach { r ->
            append("<article><a href=\"").append(esc(r.url)).append("\" target=\"_blank\" rel=\"noopener\">")
            append("<img src=\"").append(esc(r.thumbnail)).append("\" alt=\"").append(esc(r.title))
            append("\" loading=\"lazy\"></a>")
            append("<h3><a href=\"").append(esc(r.url)).append("\" target=\"_blank\" rel=\"noopener\">")
            append(esc(r.title)).append("</a></h3>")
            append("<p class=\"mk-date\">").append(esc(r.date))
            if (r.durationSec > 0) append(" · ").append(fmtDuration(r.durationSec))
            append("</p></article>\n")
        }
        append("</div>\n")
    }
}
