package com.mkmemories.mkdownloader

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
            Downloads.start(app, item, quality)
        }
    }

    private fun statusJson(): String {
        val jobs = JSONArray()
        Downloads.jobs().forEach { j ->
            jobs.put(
                JSONObject()
                    .put("title", j.item.title)
                    .put("percent", j.percent)
                    .put("status", j.status.name),
            )
        }
        val files = JSONArray()
        runCatching { History.all(app) }.getOrDefault(emptyList()).forEach { e ->
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
        if (entry == null) { writeText(out, "404 Not Found", "text/plain", "404"); return }
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

    // Page web (client iPhone) — autonome, sans dépendance externe.
    private val PAGE = """
<!doctype html><html lang="fr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="apple-mobile-web-app-capable" content="yes">
<title>MK Relais</title>
<style>
 body{margin:0;background:#0b0b0f;color:#eee;font-family:-apple-system,system-ui,sans-serif}
 .wrap{max-width:640px;margin:0 auto;padding:16px}
 h1{font-size:20px;color:#a78bfa;margin:8px 0 16px}
 input,select,button{font-size:16px;border-radius:12px;border:1px solid #333;background:#16161d;color:#eee;padding:12px;width:100%;box-sizing:border-box;margin:6px 0}
 button{background:#7c5cff;border:none;font-weight:600}
 .row{display:flex;gap:8px}.row>*{margin:6px 0}
 .card{background:#16161d;border-radius:14px;padding:12px;margin:8px 0}
 .prog{height:6px;background:#333;border-radius:6px;overflow:hidden}.bar{height:6px;background:#7c5cff;width:0}
 a.dl{display:inline-block;background:#22c55e;color:#001;padding:8px 12px;border-radius:10px;text-decoration:none;font-weight:600}
 small{color:#888}
</style></head><body><div class="wrap">
 <h1>MK Relais</h1>
 <div id="lock" class="card">
   <div>Code PIN</div>
   <input id="pin" inputmode="numeric" placeholder="PIN affiché sur le téléphone">
   <button onclick="savePin()">Valider</button>
 </div>
 <div id="app" style="display:none">
   <input id="url" placeholder="Colle un lien vidéo (YouTube, TikTok...)">
   <div class="row">
     <select id="q">
       <option value="mp4">MP4 — meilleure qualité</option>
       <option value="1080p">MP4 1080p</option>
       <option value="720p">MP4 720p</option>
       <option value="max">Qualité max</option>
       <option value="audio">Audio MP3</option>
     </select>
     <button style="max-width:140px" onclick="add()">Télécharger</button>
   </div>
   <div id="jobs"></div>
   <h1 style="font-size:16px">Fichiers prêts</h1>
   <div id="files"></div>
 </div>
 <p><small>Servi par ton téléphone Android sur le Wi-Fi.</small></p>
</div>
<script>
 function pin(){return localStorage.getItem('mkpin')||''}
 function savePin(){localStorage.setItem('mkpin',document.getElementById('pin').value.trim());show();tick()}
 function show(){var ok=pin().length>0;document.getElementById('lock').style.display=ok?'none':'block';document.getElementById('app').style.display=ok?'block':'none'}
 function add(){var u=document.getElementById('url').value.trim();if(!u)return;
   fetch('/api/add',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},
   body:'pin='+encodeURIComponent(pin())+'&url='+encodeURIComponent(u)+'&q='+document.getElementById('q').value})
   .then(function(){document.getElementById('url').value='';tick()})}
 function esc(s){var d=document.createElement('div');d.textContent=s;return d.innerHTML}
 function tick(){fetch('/api/status?pin='+encodeURIComponent(pin())).then(function(r){return r.json()}).then(function(d){
   var j='';(d.jobs||[]).forEach(function(x){var p=x.percent>=0?x.percent:0;
     j+='<div class="card">'+esc(x.title)+'<div class="prog"><div class="bar" style="width:'+p+'%"></div></div><small>'+x.status+' '+(x.percent>=0?x.percent+'%':'')+'</small></div>'});
   document.getElementById('jobs').innerHTML=j;
   var f='';(d.files||[]).forEach(function(x){
     f+='<div class="card">'+esc(x.title)+'<br><a class="dl" href="/dl/'+encodeURIComponent(x.id)+'?pin='+encodeURIComponent(pin())+'">Enregistrer</a></div>'});
   document.getElementById('files').innerHTML=f;
 }).catch(function(){})}
 show();tick();setInterval(tick,2000);
</script></body></html>
""".trimIndent()
}
