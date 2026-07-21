package com.mkmemories.mkdownloader

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Collections

/**
 * Navigateur intégré « capteur de flux » : ouvre n'importe quel site gratuit,
 * intercepte les requêtes réseau et détecte les flux vidéo (HLS .m3u8, DASH
 * .mpd, .mp4/.webm…). L'utilisateur télécharge le flux capturé via yt-dlp, avec
 * les en-têtes de session (Referer / Cookie / User-Agent) qui vont bien.
 *
 * Permet de récupérer une vidéo même sur les sites que yt-dlp ne connaît pas.
 */
class BrowserActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var urlBar: EditText
    private lateinit var captureBtn: Button

    // URL de flux → type (HLS/MP4…). Ordre d'insertion préservé, dédupliqué.
    private val captured = Collections.synchronizedMap(LinkedHashMap<String, String>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (8 * d).toInt()

        urlBar = EditText(this).apply {
            hint = getString(R.string.br_url_hint)
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> loadFromBar(); true }
        }
        val goBtn = Button(this).apply {
            text = getString(R.string.br_go)
            setOnClickListener { loadFromBar() }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, pad, pad, 0)
            addView(urlBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(goBtn)
        }

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = MOBILE_UA
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    request?.url?.toString()?.let(::onRequest)
                    return null
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    if (!urlBar.hasFocus()) urlBar.setText(url ?: "")
                }
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                bar,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        // 🎯 Cible la vraie vidéo (scan du DOM), visible en permanence.
        val targetBtn = Button(this).apply {
            text = getString(R.string.br_target)
            setOnClickListener { targetMainVideo() }
        }
        captureBtn = Button(this).apply {
            text = getString(R.string.br_capture, 0)
            visibility = View.GONE
            setOnClickListener { showCaptured() }
        }
        val actionsBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(captureBtn)
            addView(targetBtn)
        }
        val root = FrameLayout(this).apply {
            addView(col)
            addView(
                actionsBar,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.END
                    setMargins(0, 0, pad * 2, pad * 4)
                },
            )
        }
        setContentView(root)

        val start = intent?.getStringExtra(EXTRA_URL)
        web.loadUrl(if (!start.isNullOrBlank()) start else "https://www.google.com/")
    }

    private fun loadFromBar() {
        var u = urlBar.text?.toString()?.trim().orEmpty()
        if (u.isEmpty()) return
        if (!u.startsWith("http")) {
            u = if (u.contains(".") && !u.contains(" ")) "https://$u"
            else "https://www.google.com/search?q=" + android.net.Uri.encode(u)
        }
        web.loadUrl(u)
    }

    /** Chaque requête réseau du navigateur : on repère les flux vidéo (hors pubs). */
    private fun onRequest(url: String) {
        val type = mediaType(url) ?: return
        if (isAd(url)) return   // pubs / trackers : on ignore
        val added = synchronized(captured) {
            if (captured.containsKey(url) || captured.size >= MAX) false
            else { captured[url] = type; true }
        }
        if (!added) return
        val count = synchronized(captured) { captured.size }
        runOnUiThread {
            captureBtn.visibility = View.VISIBLE
            captureBtn.text = getString(R.string.br_capture, count)
        }
    }

    private fun mediaType(url: String): String? {
        val low = url.lowercase()
        val path = low.substringBefore('?').substringBefore('#')
        if ("thumb" in low || "preview" in low || path.endsWith(".jpg") || path.endsWith(".png")) return null
        return when {
            path.endsWith(".m3u8") || ".m3u8" in low -> "HLS"
            path.endsWith(".mpd") -> "DASH"
            path.endsWith(".mp4") || path.endsWith(".m4v") -> "MP4"
            path.endsWith(".webm") -> "WEBM"
            path.endsWith(".mov") -> "MOV"
            path.endsWith(".mkv") -> "MKV"
            else -> null
        }
    }

    /** Publicités / trackers vidéo à ignorer (source du bruit « N mini-vidéos »). */
    private fun isAd(url: String): Boolean {
        val low = url.lowercase()
        return AD_HOSTS.any { it in low } || AD_PATHS.any { it in low }
    }

    /** Score de pertinence : le flux principal (manifeste, même domaine) remonte. */
    private fun score(url: String, type: String): Int {
        var s = when (type) {
            "HLS" -> 80
            "DASH" -> 70
            else -> 40   // .mp4/.webm… souvent des pubs ou des extraits
        }
        val low = url.lowercase()
        if ("master" in low || "playlist" in low || "manifest" in low) s += 20
        if ("/ad" in low || "advert" in low || "preroll" in low) s -= 60
        pageHost?.let { host -> if (host.isNotEmpty() && host in low) s += 25 }
        return s
    }

    private var pageHost: String? = null

    private fun showCaptured() {
        val entries = synchronized(captured) { captured.entries.map { it.key to it.value } }
        if (entries.isEmpty()) { toast(getString(R.string.br_none)); return }
        pageHost = runCatching { android.net.Uri.parse(web.url ?: "").host }.getOrNull()
        val ranked = entries.sortedByDescending { score(it.first, it.second) }
        val labels = ranked.mapIndexed { i, (u, t) ->
            (if (i == 0) "⭐ " else "") + "$t • ${shortUrl(u)}"
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.br_captured_title_n, ranked.size))
            .setItems(labels.toTypedArray()) { _, i -> showStreamActions(ranked[i].first, ranked[i].second) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 🎯 Scanne le DOM : trouve la plus grande balise <video> à l'écran et capte SON
     *  flux (précis, contourne les pubs). Repli sur les flux réseau détectés. */
    private fun targetMainVideo() {
        web.evaluateJavascript(JS_FIND_VIDEO) { raw ->
            val json = runCatching {
                val inner = org.json.JSONTokener(raw).nextValue()
                org.json.JSONObject(if (inner is String) inner else raw)
            }.getOrNull()
            val found = json?.optBoolean("found") == true
            val src = json?.optString("src").orEmpty()
            when {
                found && src.startsWith("http") -> {
                    val type = mediaType(src) ?: "VIDEO"
                    val w = json!!.optInt("w"); val h = json.optInt("h")
                    toast(getString(R.string.br_target_found, w, h))
                    showStreamActions(src, type)
                }
                found -> {
                    // Vidéo repérée mais flux interne (blob/MSE) → on utilise le réseau.
                    val w = json!!.optInt("w"); val h = json.optInt("h")
                    toast(getString(R.string.br_target_stream, w, h))
                    showCaptured()
                }
                else -> {
                    toast(getString(R.string.br_target_none))
                    showCaptured()
                }
            }
        }
    }

    /** Pour un flux : télécharger (VOD) ou enregistrer un direct par tranches de 5 min. */
    private fun showStreamActions(streamUrl: String, type: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(shortUrl(streamUrl))
            .setItems(arrayOf(getString(R.string.br_dl), getString(R.string.br_record))) { _, i ->
                if (i == 0) downloadStream(streamUrl, type) else askRecordDuration(streamUrl)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askRecordDuration(streamUrl: String) {
        val labels = arrayOf("30 min", "1 h", "2 h", "3 h")
        val mins = intArrayOf(30, 60, 120, 180)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.br_record_title)
            .setItems(labels) { _, i ->
                val item = streamItem(streamUrl)
                val n = Downloads.recordLive(this, item, captureHeaders(streamUrl), mins[i])
                toast(getString(R.string.br_record_started, n))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun shortUrl(u: String): String {
        val host = runCatching { android.net.Uri.parse(u).host }.getOrNull().orEmpty()
        val file = u.substringBefore('?').substringAfterLast('/')
        return if (file.length in 1..40) "$host/$file" else host
    }

    /** En-têtes de session à joindre au flux (Referer = page, Cookie, User-Agent). */
    private fun captureHeaders(streamUrl: String): Map<String, String> = buildMap {
        put("Referer", web.url ?: streamUrl)
        put("User-Agent", MOBILE_UA)
        CookieManager.getInstance().getCookie(streamUrl)?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
    }

    private fun streamItem(streamUrl: String): VideoItem = VideoItem(
        url = streamUrl,
        title = web.title?.takeIf { it.isNotBlank() } ?: shortUrl(streamUrl),
        uploader = runCatching { android.net.Uri.parse(web.url ?: streamUrl).host }.getOrNull(),
        durationSec = 0,
        thumbnail = null,
    )

    private fun downloadStream(streamUrl: String, type: String) {
        Downloads.registerHeaders(streamUrl, captureHeaders(streamUrl))
        Downloads.start(this, streamItem(streamUrl), Quality("capture", type, "bv*+ba/b/best", mergeMp4 = true))
        toast(getString(R.string.download_queued))
    }

    override fun onDestroy() {
        runCatching { (web.parent as? ViewGroup)?.removeView(web); web.destroy() }
        super.onDestroy()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_URL = "url"
        private const val MAX = 40
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"

        // Régies pub / trackers vidéo → source du bruit « N mini-vidéos ».
        private val AD_HOSTS = listOf(
            "doubleclick", "googlesyndication", "googleadservices", "google-analytics",
            "googletagservices", "imasdk", "adservice", "adsystem", "amazon-adsystem",
            "moatads", "adsafeprotected", "teads", "taboola", "outbrain", "criteo",
            "adnxs", "2mdn.net", "innovid", "springserve", "spotx", "smartadserver",
            "adform", "yieldmo", "pubmatic", "rubiconproject", "scorecardresearch",
        )
        private val AD_PATHS = listOf(
            "/vast", "/vmap", "/ads/", "/ad/", "/advert", "/preroll", "/midroll", "/commercial",
        )

        // Scan DOM : renvoie la plus grande balise <video> visible et sa source.
        private const val JS_FIND_VIDEO = """
            (function(){
              try {
                var vids = Array.prototype.slice.call(document.querySelectorAll('video'));
                var best=null, bestArea=0;
                vids.forEach(function(v){
                  var r=v.getBoundingClientRect();
                  var a=r.width*r.height;
                  if(r.width>0 && r.height>0 && a>bestArea){ bestArea=a; best=v; }
                });
                if(!best) return JSON.stringify({found:false});
                var src=best.currentSrc||best.src||'';
                if(!src){ var s=best.querySelector('source'); if(s) src=s.src||''; }
                return JSON.stringify({found:true, src:src, w:best.videoWidth||0, h:best.videoHeight||0, paused:best.paused});
              } catch(e){ return JSON.stringify({found:false}); }
            })();
        """
    }
}
