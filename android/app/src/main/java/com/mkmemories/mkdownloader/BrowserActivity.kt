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
        captureBtn = Button(this).apply {
            text = getString(R.string.br_capture, 0)
            visibility = View.GONE
            setOnClickListener { showCaptured() }
        }
        val root = FrameLayout(this).apply {
            addView(col)
            addView(
                captureBtn,
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

    /** Chaque requête réseau du navigateur : on repère les flux vidéo. */
    private fun onRequest(url: String) {
        val type = mediaType(url) ?: return
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

    private fun showCaptured() {
        val entries = synchronized(captured) { captured.entries.map { it.key to it.value } }
        if (entries.isEmpty()) { toast(getString(R.string.br_none)); return }
        val labels = entries.map { (u, t) -> "$t • ${shortUrl(u)}" }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.br_captured_title)
            .setItems(labels.toTypedArray()) { _, i -> showStreamActions(entries[i].first, entries[i].second) }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
    }
}
