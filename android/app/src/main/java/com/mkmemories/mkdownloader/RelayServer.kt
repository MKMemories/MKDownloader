package com.mkmemories.mkdownloader

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * « MK Relais » : petit serveur HTTP de CONTRÔLE. Le téléphone Android devient un
 * serveur de téléchargement pour les autres appareils du même Wi-Fi (iPhones…) :
 * ils ouvrent une page web, collent une URL → l'Android télécharge (avec son
 * moteur + son IP résidentielle) puis leur sert le fichier fini (à enregistrer).
 */
object RelayServer {

    @Volatile var running = false; private set
    @Volatile var port = 0; private set
    private var pin = ""
    private lateinit var app: Context
    private var server: ServerSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // URLs demandées VIA LE RELAIS (session) : on n'expose QUE celles-ci côté
    // iPhone — jamais tout l'historique privé du téléphone.
    private val relayUrls = java.util.Collections.synchronizedSet(HashSet<String>())

    fun start(context: Context, pinCode: String): Boolean {
        if (running) return true
        app = context.applicationContext
        pin = pinCode
        val s = openSocket() ?: return false
        server = s
        port = s.localPort
        running = true
        Thread {
            while (!s.isClosed) {
                val sock = try { s.accept() } catch (_: Exception) { break }
                Thread { runCatching { handle(sock) }; runCatching { sock.close() } }
                    .apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
        return true
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
    }

    fun url(): String? = lanIp()?.let { "http://$it:$port" }

    private fun openSocket(): ServerSocket? {
        for (p in 8099..8109) {
            val s = runCatching { ServerSocket(p) }.getOrNull()
            if (s != null) return s
        }
        return runCatching { ServerSocket(0) }.getOrNull()
    }

    // ---------- Traitement d'une requête ----------

    private fun handle(sock: Socket) {
        val input = sock.getInputStream()
        val reader = input.bufferedReader()
        val requestLine = reader.readLine() ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val method = parts[0]
        val target = parts[1]
        var contentLength = 0
        var rangeStart = 0L
        var rangeEnd = -1L
        var hasRange = false
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            when {
                line.startsWith("Content-Length:", true) ->
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                line.startsWith("Range:", true) ->
                    Regex("bytes=(\\d*)-(\\d*)").find(line)?.let { m ->
                        rangeStart = m.groupValues[1].toLongOrNull() ?: 0L
                        rangeEnd = m.groupValues[2].toLongOrNull() ?: -1L
                        hasRange = true
                    }
            }
        }
        var body = ""
        if (method == "POST" && contentLength > 0) {
            val buf = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val r = reader.read(buf, read, contentLength - read)
                if (r < 0) break
                read += r
            }
            body = String(buf, 0, read)
        }

        val path = target.substringBefore('?')
        val query = parseQuery(target.substringAfter('?', ""))
        val out = BufferedOutputStream(sock.getOutputStream())

        when {
            path == "/" -> writeText(out, "200 OK", "text/html; charset=utf-8", PAGE)
            path == "/api/status" -> {
                if (query["pin"] != pin) { writeText(out, "403 Forbidden", "application/json", "{}"); return }
                writeText(out, "200 OK", "application/json", statusJson())
            }
            path == "/api/search" -> {
                if (query["pin"] != pin) { writeText(out, "403 Forbidden", "application/json", "{}"); return }
                writeText(out, "200 OK", "application/json", searchJson(query["q"].orEmpty()))
            }
            path == "/api/add" -> {
                val form = parseQuery(body)
                if (form["pin"] != pin) { writeText(out, "403 Forbidden", "application/json", "{}"); return }
                val u = form["url"]?.trim().orEmpty()
                if (u.startsWith("http")) enqueue(u, form["q"].orEmpty())
                writeText(out, "200 OK", "application/json", "{\"ok\":true}")
            }
            path.startsWith("/dl/") -> {
                if (query["pin"] != pin) { writeText(out, "403 Forbidden", "text/plain", "PIN"); return }
                serveFile(out, path.removePrefix("/dl/"), method, hasRange, rangeStart, rangeEnd)
            }
            else -> writeText(out, "404 Not Found", "text/plain", "404")
        }
    }

    private fun enqueue(url: String, qid: String) {
        val quality = QUALITIES.find { it.id == qid } ?: QUALITIES.first()
        scope.launch {
            val item = runCatching { Engine.getInfo(app, url) }.getOrNull()
                ?: VideoItem(url = url, title = url, uploader = null, durationSec = 0, thumbnail = null)
            relayUrls.add(item.url)
            Downloads.start(app, item, quality)
        }
    }

    private fun searchJson(q: String): String {
        if (q.isBlank()) return "{\"results\":[]}"
        val items = runCatching { runBlocking { Engine.search(app, q, DateFilter.ANY, 25) } }
            .getOrDefault(emptyList())
        val arr = JSONArray()
        items.forEach {
            arr.put(
                JSONObject()
                    .put("url", it.url)
                    .put("title", it.title)
                    .put("thumb", it.thumbnail ?: "")
                    .put("by", it.uploader ?: it.channelName ?: ""),
            )
        }
        return JSONObject().put("results", arr).toString()
    }

    private fun statusJson(): String {
        val urls = synchronized(relayUrls) { relayUrls.toSet() }
        val jobs = JSONArray()
        // Uniquement les téléchargements lancés via le relais (pas l'historique privé).
        Downloads.jobs().filter { it.item.url in urls }.forEach { j ->
            jobs.put(
                JSONObject()
                    .put("title", j.item.title)
                    .put("percent", j.percent)
                    .put("status", j.status.name),
            )
        }
        val files = JSONArray()
        runCatching { History.all(app) }.getOrDefault(emptyList())
            .filter { e -> urls.any { e.id.endsWith("-" + it.hashCode()) } }
            .forEach { e ->
                files.put(
                    JSONObject()
                        .put("id", e.id)
                        .put("title", e.title)
                        .put("audio", e.audio),
                )
            }
        return JSONObject().put("jobs", jobs).put("files", files).toString()
    }

    private fun serveFile(
        out: BufferedOutputStream,
        id: String,
        method: String,
        hasRange: Boolean,
        rangeStart: Long,
        rangeEnd: Long,
    ) {
        val entry = runCatching { History.all(app) }.getOrDefault(emptyList()).find { it.id == id }
        // Confidentialité : on ne sert QUE les fichiers demandés via le relais.
        val isRelay = entry != null &&
            synchronized(relayUrls) { relayUrls.any { entry.id.endsWith("-" + it.hashCode()) } }
        if (entry == null || !isRelay) { writeText(out, "404 Not Found", "text/plain", "404"); return }
        val uri = Uri.parse(entry.uri)
        val total = runCatching {
            app.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        }.getOrNull() ?: -1L
        if (total <= 0) { writeText(out, "404 Not Found", "text/plain", "404"); return }
        val end = if (rangeEnd in 0 until total) rangeEnd else total - 1
        val start = if (rangeStart in 0..end) rangeStart else 0L
        val length = end - start + 1
        val mime = if (entry.audio) "audio/mpeg" else "video/mp4"

        val head = buildString {
            append(if (hasRange) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            append("Content-Type: $mime\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Content-Length: $length\r\n")
            append("Content-Disposition: attachment; filename=\"${entry.fileName}\"\r\n")
            if (hasRange) append("Content-Range: bytes $start-$end/$total\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray())
        if (method.equals("HEAD", true)) { out.flush(); return }
        app.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { fis ->
                fis.channel.position(start)
                val buf = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0) {
                    val toRead = minOf(buf.size.toLong(), remaining).toInt()
                    val r = fis.read(buf, 0, toRead)
                    if (r < 0) break
                    out.write(buf, 0, r)
                    remaining -= r
                }
            }
        }
        out.flush()
    }

    private fun writeText(out: BufferedOutputStream, status: String, type: String, body: String) {
        val bytes = body.toByteArray()
        val head = "HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Access-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isBlank()) return emptyMap()
        return q.split("&").mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else runCatching {
                URLDecoder.decode(it.substring(0, i), "UTF-8") to URLDecoder.decode(it.substring(i + 1), "UTF-8")
            }.getOrNull()
        }.toMap()
    }

    private fun lanIp(): String? {
        val ifaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return null
        for (ni in ifaces) {
            val usable = runCatching { ni.isUp && !ni.isLoopback }.getOrDefault(false)
            if (!usable) continue
            for (addr in ni.inetAddresses) {
                if (!addr.isLoopbackAddress && addr is Inet4Address && addr.isSiteLocalAddress) {
                    return addr.hostAddress
                }
            }
        }
        return null
    }

    // Page web (client iPhone) — autonome, aux couleurs MK, avec recherche YouTube.
    private val PAGE = """
<!doctype html><html lang="fr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-title" content="MKDownloader">
<title>MKDownloader</title>
<style>
 :root{--bg:#0a0a0f;--card:#15151d;--line:#26262f;--accent:#7c5cff;--accent2:#a78bfa;--ok:#22c55e}
 *{box-sizing:border-box}
 body{margin:0;background:var(--bg);color:#f2f2f5;font-family:-apple-system,system-ui,sans-serif}
 header{position:sticky;top:0;background:rgba(10,10,15,.92);backdrop-filter:blur(8px);padding:14px 16px;border-bottom:1px solid var(--line);z-index:5}
 header b{font-size:19px;color:#fff}header b span{color:var(--accent2)}
 .wrap{max-width:680px;margin:0 auto;padding:14px 16px 40px}
 input,select,button{font-size:16px;border-radius:13px;border:1px solid var(--line);background:var(--card);color:#f2f2f5;padding:13px}
 input,select{width:100%;margin:6px 0}
 button{background:var(--accent);border:none;font-weight:700;color:#fff}
 .searchrow{display:flex;gap:8px;margin:4px 0}.searchrow input{margin:0}.searchrow button{white-space:nowrap;padding:13px 18px}
 .card{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:12px;margin:10px 0}
 .res{display:flex;gap:12px;align-items:center}
 .res img{width:132px;height:76px;object-fit:cover;border-radius:10px;background:#000;flex:none}
 .res .t{font-weight:600;font-size:14px;line-height:1.25;max-height:3.2em;overflow:hidden}
 .res small{color:#9a9aa6}
 .res button{margin-top:6px;padding:8px 12px;font-size:14px;border-radius:10px}
 .prog{height:6px;background:#2a2a33;border-radius:6px;overflow:hidden;margin:8px 0 4px}.bar{height:6px;background:var(--accent);width:0}
 a.save{display:inline-block;background:var(--ok);color:#00230d;padding:9px 14px;border-radius:11px;text-decoration:none;font-weight:700}
 h2{font-size:15px;color:var(--accent2);margin:20px 0 6px}
 small{color:#8a8a96}
 .sep{text-align:center;color:#6a6a76;font-size:13px;margin:14px 0 4px}
</style></head><body>
<header><b>MK<span>Downloader</span></b></header>
<div class="wrap">
 <div id="lock" class="card">
   <div style="margin-bottom:6px">Code PIN (affiché sur le téléphone)</div>
   <input id="pinIn" inputmode="numeric" placeholder="PIN">
   <button style="width:100%;margin-top:8px" onclick="savePin()">Déverrouiller</button>
 </div>
 <div id="app" style="display:none">
   <select id="q">
     <option value="mp4">MP4 — meilleure qualité</option>
     <option value="1080p">MP4 1080p</option>
     <option value="720p">MP4 720p</option>
     <option value="max">Qualité max</option>
     <option value="audio">Audio MP3</option>
   </select>
   <div class="searchrow">
     <input id="q1" placeholder="Rechercher une vidéo YouTube…" onkeydown="if(event.key=='Enter')doSearch()">
     <button onclick="doSearch()">Rechercher</button>
   </div>
   <div id="results"></div>
   <div class="sep">— ou —</div>
   <div class="searchrow">
     <input id="url" placeholder="Colle un lien vidéo…">
     <button onclick="addLink()">Ajouter</button>
   </div>
   <div id="dl"></div>
 </div>
</div>
<script>
 var R=[];
 function P(){return encodeURIComponent(localStorage.getItem('mkpin')||'')}
 function has(){return (localStorage.getItem('mkpin')||'').length>0}
 function el(i){return document.getElementById(i)}
 function esc(s){var d=document.createElement('div');d.textContent=s||'';return d.innerHTML}
 function qual(){return el('q').value}
 function savePin(){localStorage.setItem('mkpin',el('pinIn').value.trim());show();tick()}
 function show(){el('lock').style.display=has()?'none':'block';el('app').style.display=has()?'block':'none'}
 function j(r){return r.json()}
 function doSearch(){var q=el('q1').value.trim();if(!q)return;el('results').innerHTML='<small>Recherche…</small>';
   fetch('/api/search?pin='+P()+'&q='+encodeURIComponent(q)).then(j).then(function(d){
     R=d.results||[];if(!R.length){el('results').innerHTML='<small>Aucun résultat.</small>';return}
     var h='';for(var i=0;i<R.length;i++){var x=R[i];
       h+='<div class="card res"><img src="'+esc(x.thumb)+'" loading="lazy"><div><div class="t">'+esc(x.title)+'</div><small>'+esc(x.by)+'</small><br><button onclick="addIdx('+i+')">Télécharger</button></div></div>'}
     el('results').innerHTML=h}).catch(function(){el('results').innerHTML='<small>Erreur.</small>'})}
 function addIdx(i){addUrl(R[i].url)}
 function addLink(){var u=el('url').value.trim();if(u){addUrl(u);el('url').value=''}}
 function addUrl(u){fetch('/api/add',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},
   body:'pin='+P()+'&url='+encodeURIComponent(u)+'&q='+qual()}).then(function(){tick()})}
 function tick(){if(!has())return;fetch('/api/status?pin='+P()).then(j).then(function(d){
   var h='';var jobs=d.jobs||[],files=d.files||[];
   if(jobs.length||files.length)h+='<h2>Téléchargements</h2>';
   jobs.forEach(function(x){var p=x.percent>=0?x.percent:0;
     h+='<div class="card">'+esc(x.title)+'<div class="prog"><div class="bar" style="width:'+p+'%"></div></div><small>'+esc(x.status)+' '+(x.percent>=0?x.percent+'%':'')+'</small></div>'});
   files.forEach(function(x){
     h+='<div class="card">'+esc(x.title)+'<br><a class="save" href="/dl/'+encodeURIComponent(x.id)+'?pin='+P()+'">⬇ Enregistrer</a></div>'});
   el('dl').innerHTML=h}).catch(function(){})}
 show();tick();setInterval(tick,2000);
</script></body></html>
""".trimIndent()
}
