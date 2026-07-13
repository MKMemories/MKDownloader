package com.mkmemories.mkdownloader

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Connexion YouTube « in-app » : l'utilisateur se connecte à Google dans une
 * WebView (session repartie de zéro = navigation privée, pour un compte dédié à
 * cet usage). Une fois connecté, on capture les cookies de session et on les
 * écrit au format Netscape (cookies.txt) que yt-dlp sait consommer via --cookies.
 *
 * Raison : YouTube n'accepte plus la connexion par login/mot de passe pour les
 * outils tiers ; les cookies d'une session réelle sont la méthode fiable.
 */
class YoutubeLoginActivity : AppCompatActivity() {

    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val cm = CookieManager.getInstance()
        // Session « privée » : on repart d'un pot à cookies vierge (compte dédié).
        cm.removeAllCookies(null)
        cm.flush()
        cm.setAcceptCookie(true)

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // UA navigateur de bureau : réduit le blocage « navigateur non sécurisé ».
            settings.userAgentString = DESKTOP_UA
            webViewClient = WebViewClient()
        }
        cm.setAcceptThirdPartyCookies(web, true)

        val pad = (12 * resources.displayMetrics.density).toInt()
        val hint = TextView(this).apply {
            text = getString(R.string.yt_login_hint)
            setPadding(pad, pad, pad, pad / 2)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#101014"))
        }
        val done = Button(this).apply {
            text = getString(R.string.yt_login_save)
            setOnClickListener { captureAndFinish() }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101014"))
            gravity = Gravity.CENTER_HORIZONTAL
            addView(hint)
            addView(done)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        web.loadUrl("https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fwww.youtube.com%2F")
    }

    /** Vrai si les cookies de session YouTube sont présents. */
    private fun isLoggedIn(): Boolean {
        val c = CookieManager.getInstance().getCookie("https://www.youtube.com") ?: return false
        return c.contains("__Secure-3PSID") || Regex("(^|;\\s*)SID=").containsMatchIn(c) ||
            c.contains("LOGIN_INFO")
    }

    private fun captureAndFinish() {
        val cm = CookieManager.getInstance()
        cm.flush()
        if (!isLoggedIn()) {
            Toast.makeText(this, R.string.yt_login_not_detected, Toast.LENGTH_LONG).show()
            return
        }
        val byDomain = mapOf(
            ".youtube.com" to cm.getCookie("https://www.youtube.com"),
            ".google.com" to cm.getCookie("https://www.google.com"),
        )
        runCatching { Settings.saveCookies(this, "youtube", buildNetscape(byDomain)) }
            .onSuccess {
                Logs.d("account", "cookies YouTube enregistrés")
                Toast.makeText(this, R.string.yt_login_ok, Toast.LENGTH_SHORT).show()
                setResult(Activity.RESULT_OK)
                finish()
            }
            .onFailure {
                Logs.e("account", "échec écriture cookies", it)
                Toast.makeText(this, R.string.yt_login_not_detected, Toast.LENGTH_LONG).show()
            }
    }

    /** Construit un fichier cookies.txt Netscape à partir des en-têtes Cookie. */
    private fun buildNetscape(byDomain: Map<String, String?>): String {
        val expiry = System.currentTimeMillis() / 1000 + 365L * 24 * 3600  // +1 an
        val sb = StringBuilder("# Netscape HTTP Cookie File\n")
        byDomain.forEach { (domain, header) ->
            header?.split(";")?.forEach { pair ->
                val t = pair.trim()
                val eq = t.indexOf('=')
                if (eq > 0) {
                    val name = t.substring(0, eq).trim()
                    val value = t.substring(eq + 1).trim()
                    if (name.isNotEmpty()) {
                        // domaine  includeSubdomains  chemin  secure  expiration  nom  valeur
                        sb.append("$domain\tTRUE\t/\tTRUE\t$expiry\t$name\t$value\n")
                    }
                }
            }
        }
        return sb.toString()
    }

    override fun onDestroy() {
        runCatching { (web.parent as? ViewGroup)?.removeView(web); web.destroy() }
        super.onDestroy()
    }

    companion object {
        // UA de Chrome mobile réel (sans « ; wv ») : limite la détection WebView.
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
