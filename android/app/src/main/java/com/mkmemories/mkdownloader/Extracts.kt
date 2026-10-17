package com.mkmemories.mkdownloader

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 🎬 Extraits + transcription : pour UNE vidéo choisie par l'utilisateur,
 * récupère la retranscription automatique (sous-titres YouTube, horodatés)
 * pour repérer les passages intéressants, puis découpe les plages demandées
 * en clips via la file de téléchargement (--download-sections).
 *
 * Fichiers écrits dans Téléchargements/MKDownloader/Extraits/ :
 *  • « Transcription - <titre>.txt » — une ligne horodatée par phrase ;
 *  • « Extraits - <titre>.txt »      — la fiche : source, lien, date, et pour
 *    chaque plage les mots exacts de la transcription (à vérifier à l'écoute).
 */
object Extracts {

    /** Une ligne de transcription : seconde de début + texte nettoyé. */
    data class Cue(val sec: Int, val text: String)

    /** Un commentaire récupéré (tri « top » de YouTube). */
    data class Comment(val author: String, val likes: Long, val text: String)

    data class Transcript(
        val item: VideoItem,       // pour mettre les clips en file
        val id: String,
        val date: String,          // AAAA-MM-JJ
        val lang: String,          // langue de la piste retenue
        val auto: Boolean,         // true = sous-titres automatiques
        val description: String,   // description YouTube de la vidéo
        val cues: List<Cue>,
        val comments: List<Comment> = emptyList(),
    )

    /**
     * Métadonnées + transcription en UN appel yt-dlp : le JSON de la vidéo
     * contient les adresses des pistes de sous-titres ; on télécharge ensuite
     * la piste choisie (manuelle de préférence, sinon automatique).
     */
    suspend fun fetch(
        context: Context,
        url: String,
        withComments: Boolean = false,
    ): Transcript =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            Engine.ensureReady(app)
            val request = YoutubeDLRequest(url.trim()).apply {
                addOption("--dump-single-json")
                addOption("--no-playlist")
                addOption("--no-warnings")
                if (withComments) {
                    // Les ~200 meilleurs commentaires, intégrés au JSON.
                    addOption("--write-comments")
                    addOption(
                        "--extractor-args",
                        Engine.YT_ARGS + ";comment_sort=top;max_comments=200,100,10",
                    )
                } else {
                    addOption("--extractor-args", Engine.YT_ARGS)
                }
                Settings.cookiesForUrl(app, url)?.let { addOption("--cookies", it.absolutePath) }
            }
            val out = YoutubeDL.getInstance().execute(request, null, null).out
            val start = out.indexOf('{')
            require(start >= 0) { "Vidéo illisible." }
            val o = JSONObject(out.substring(start))
            val id = o.optString("id")
            val item = VideoItem(
                url = o.optString("webpage_url").ifBlank { url.trim() },
                title = o.optString("title").ifBlank { "Vidéo" },
                uploader = o.optString("uploader").takeIf { it.isNotBlank() },
                durationSec = o.optInt("duration", 0),
                thumbnail = o.optString("thumbnail").takeIf { it.isNotBlank() },
                channelName = o.optString("channel").takeIf { it.isNotBlank() },
            )
            val raw = o.optString("upload_date")
            val date = if (raw.length == 8) {
                "${raw.substring(0, 4)}-${raw.substring(4, 6)}-${raw.substring(6, 8)}"
            } else raw

            // Piste : sous-titres publiés d'abord, automatiques sinon.
            val wanted = listOf(o.optString("language"), "fr", "fr-orig", "en").filter { it.isNotBlank() }
            fun pick(obj: JSONObject?): Pair<String, String>? {   // (langue, url vtt)
                obj ?: return null
                val keys = obj.keys().asSequence().toList()
                val lang = wanted.firstOrNull { w -> keys.any { it == w || it.startsWith("$w-") } }
                    ?.let { w -> keys.first { it == w || it.startsWith("$w-") } }
                    ?: keys.firstOrNull { it != "live_chat" } ?: return null
                val tracks = obj.optJSONArray(lang) ?: return null
                var best: String? = null
                for (i in 0 until tracks.length()) {
                    val t = tracks.optJSONObject(i) ?: continue
                    val u = t.optString("url")
                    if (u.isBlank()) continue
                    if (t.optString("ext") == "vtt") { best = u; break }
                    if (best == null) best = "$u&fmt=vtt"
                }
                return best?.let { lang to it }
            }
            val manual = pick(o.optJSONObject("subtitles"))
            val autoTrack = pick(o.optJSONObject("automatic_captions"))
            val chosen = manual ?: autoTrack
                ?: throw IllegalStateException("Aucune transcription disponible pour cette vidéo.")
            val vtt = httpGet(chosen.second)
            val cues = parseVtt(vtt)
            require(cues.isNotEmpty()) { "Transcription vide pour cette vidéo." }
            val comments = mutableListOf<Comment>()
            if (withComments) {
                o.optJSONArray("comments")?.let { arr ->
                    for (i in 0 until minOf(arr.length(), 200)) {
                        val c = arr.optJSONObject(i) ?: continue
                        val txt = c.optString("text").trim()
                        if (txt.isEmpty()) continue
                        comments += Comment(
                            author = c.optString("author").ifBlank { "?" },
                            likes = c.optLong("like_count", 0L),
                            text = txt,
                        )
                    }
                }
            }
            Transcript(
                item, id, date, chosen.first, manual == null,
                o.optString("description"), cues, comments,
            )
        }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        return conn.inputStream.use { it.readBytes().decodeToString() }
    }

    /**
     * Lit un VTT YouTube : horodatages « hh:mm:ss.mmm --> … », balises <c>
     * retirées, lignes répétées des sous-titres automatiques (défilement)
     * dédupliquées.
     */
    private fun parseVtt(vtt: String): List<Cue> {
        val timeRx = Regex("^(\\d{2}):(\\d{2}):(\\d{2})\\.\\d{3}\\s+-->")
        val cues = mutableListOf<Cue>()
        var current = -1
        var lastText = ""
        vtt.lines().forEach { lineRaw ->
            val m = timeRx.find(lineRaw.trim())
            if (m != null) {
                current = m.groupValues[1].toInt() * 3600 +
                    m.groupValues[2].toInt() * 60 + m.groupValues[3].toInt()
                return@forEach
            }
            if (current < 0) return@forEach
            val text = lineRaw
                .replace(Regex("<[^>]*>"), "")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&#39;", "'").replace("&quot;", "\"").replace("&nbsp;", " ")
                .trim()
            if (text.isEmpty() || text == lastText) return@forEach
            cues += Cue(current, text)
            lastText = text
        }
        return cues
    }

    // ---------- Affichage / fichiers ----------

    fun fmt(sec: Int): String = if (sec >= 3600) {
        String.format("%d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60)
    } else {
        String.format("%d:%02d", sec / 60, sec % 60)
    }

    fun toText(t: Transcript): String = buildString {
        append("TITRE : ").append(t.item.title).append('\n')
        append("URL : ").append(t.item.url).append('\n')
        append("DATE : ").append(t.date)
        append("   LANGUE : ").append(t.lang)
        if (t.auto) append(" (sous-titres automatiques — vérifier les mots exacts à l'écoute)")
        append('\n')
        t.item.channelName?.let { append("CHAÎNE : ").append(it).append('\n') }
        append("\nDESCRIPTION :\n")
        append(t.description.ifBlank { "(vide)" })
        append("\n\nTRANSCRIPTION :\n")
        t.cues.forEach { append('[').append(fmt(it.sec)).append("] ").append(it.text).append('\n') }
        if (t.comments.isNotEmpty()) {
            append("\nCOMMENTAIRES (").append(t.comments.size)
            append(", tri « top » de YouTube) :\n")
            t.comments.forEach { c ->
                append("\n— ").append(c.author)
                if (c.likes > 0) append(" (").append(c.likes).append(" 👍)")
                append(" :\n").append(c.text).append('\n')
            }
        }
    }

    /**
     * « 12:40-12:55, 31:02-31:15 » (ou h:mm:ss, séparées par virgule/retour à la
     * ligne) → paires de secondes. Lance une erreur claire si une plage est mal
     * écrite.
     */
    fun parseRanges(input: String): List<Pair<Int, Int>> {
        fun toSec(s: String): Int {
            val parts = s.trim().split(":").map { it.trim() }
            require(parts.size in 1..3 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) }) {
                "Plage illisible : « $s »"
            }
            return parts.fold(0) { acc, p -> acc * 60 + p.toInt() }
        }
        val ranges = input.split(Regex("[,;\\n]"))
            .map { it.trim() }.filter { it.isNotEmpty() }
            .map { token ->
                val parts = token.split("-", "–", "→").map { it.trim() }
                require(parts.size == 2) { "Plage illisible : « $token » (attendu : début-fin)" }
                val a = toSec(parts[0]); val b = toSec(parts[1])
                require(b > a) { "Plage inversée : « $token »" }
                a to b
            }
        require(ranges.isNotEmpty()) { "Aucune plage saisie." }
        return ranges
    }

    /** Lignes de transcription couvrant la plage a → b (avec 5 s d'amorce). */
    private fun excerpt(cues: List<Cue>, a: Int, b: Int): String =
        cues.filter { it.sec >= a - 5 && it.sec <= b }
            .joinToString("\n") { "[${fmt(it.sec)}] ${it.text}" }
            .ifBlank { "(pas de parole détectée sur cette plage)" }

    /** Fiche des extraits : source + mots exacts de chaque plage. */
    fun ficheText(t: Transcript, ranges: List<Pair<Int, Int>>): String = buildString {
        val now = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.FRANCE)
            .format(java.util.Date())
        append("🎬 FICHE D'EXTRAITS — ").append(t.item.title).append('\n')
        append("Chaîne : ").append(t.item.channelName ?: t.item.uploader ?: "?").append('\n')
        append("Source : ").append(t.item.url).append('\n')
        append("Mise en ligne : ").append(t.date)
        append("   Fiche générée le ").append(now).append(" par MKDownloader\n")
        append("Transcription ").append(if (t.auto) "AUTOMATIQUE (à vérifier à l'écoute)" else "officielle")
        append(", langue : ").append(t.lang).append('\n')
        ranges.forEachIndexed { i, (a, b) ->
            append("\n────────────────────────────────────────\n")
            append("EXTRAIT ").append(i + 1).append(" : ").append(fmt(a)).append(" → ").append(fmt(b))
            append(" (").append(b - a).append(" s)\n")
            append("Orateur :            Parti/bord :            Thème :\n")
            append("Contexte (à quelle question il répond) :\n\n")
            append("Transcription de la plage :\n")
            append(excerpt(t.cues, a, b)).append('\n')
        }
    }

    /** Écrit transcription + fiche dans Téléchargements/MKDownloader/Extraits/. */
    fun saveFiles(context: Context, t: Transcript, ranges: List<Pair<Int, Int>>?): String? {
        val app = context.applicationContext
        val base = Backup.sanitizeName(t.item.title)
        val uri = Backup.writeTextFile(
            app, "MKDownloader/Extraits", "Transcription - $base.txt", toText(t),
        )
        if (ranges != null) {
            Backup.writeTextFile(
                app, "MKDownloader/Extraits", "Extraits - $base.txt", ficheText(t, ranges),
            )
        }
        return uri
    }
}
