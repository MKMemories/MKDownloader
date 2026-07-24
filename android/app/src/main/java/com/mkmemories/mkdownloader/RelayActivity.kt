package com.mkmemories.mkdownloader

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Écran « MK Relais » : active le pont Wi-Fi, affiche l'adresse + le PIN + un QR
 * code que les iPhones scannent pour ouvrir la page de téléchargement.
 */
class RelayActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var qr: ImageView
    private lateinit var toggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()

        val title = TextView(this).apply {
            text = getString(R.string.relay_title)
            textSize = 20f
            setTextColor(Color.WHITE)
        }
        val desc = TextView(this).apply {
            text = getString(R.string.relay_desc)
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(0, (8 * d).toInt(), 0, (12 * d).toInt())
        }
        status = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        qr = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams((240 * d).toInt(), (240 * d).toInt()).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = (16 * d).toInt()
                bottomMargin = (16 * d).toInt()
            }
        }
        toggle = Button(this).apply { setOnClickListener { toggle() } }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(title)
            addView(desc)
            addView(toggle)
            addView(status)
            addView(qr)
        }
        setContentView(root)
        render()
    }

    private fun pin(): String {
        val p = getSharedPreferences("mkdl_relay", MODE_PRIVATE)
        var code = p.getString("pin", null)
        if (code.isNullOrEmpty()) {
            // PIN à 4 chiffres, stable, sans Math.random (dispo ici mais on reste simple).
            code = (1000 + (System.currentTimeMillis() % 9000)).toString()
            p.edit().putString("pin", code).apply()
        }
        return code
    }

    private fun toggle() {
        if (RelayServer.running) {
            RelayService.stop(this)
            RelayServer.stop()
        } else {
            RelayServer.start(this, pin())
            RelayService.start(this)
        }
        render()
    }

    private fun render() {
        val on = RelayServer.running
        toggle.text = getString(if (on) R.string.relay_off else R.string.relay_on)
        val url = RelayServer.url()
        if (on && url != null) {
            status.text = getString(R.string.relay_status, url, pin())
            qr.setImageBitmap(qrBitmap(url))
            qr.visibility = ViewGroup.VISIBLE
        } else {
            status.text = getString(if (on) R.string.relay_no_wifi else R.string.relay_stopped)
            qr.setImageBitmap(null)
        }
    }

    private fun qrBitmap(text: String): Bitmap? = runCatching {
        val size = 512
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until size) for (y in 0 until size) {
                setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
    }.getOrNull()
}
