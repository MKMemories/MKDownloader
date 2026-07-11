package com.mkmemories.mkdownloader.tv

import android.app.AlertDialog
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.mkmemories.mkdownloader.R
import com.mkmemories.mkdownloader.Updater
import kotlinx.coroutines.launch

/** Écran d'accueil Android TV (héberge le navigateur Leanback). */
class TvActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.tv_frame)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.tvContainer, TvBrowseFragment())
                .commit()
        }
        checkUpdate()
    }

    /** Mise à jour in-app (comme la version mobile) : la variante TV en bénéficie aussi. */
    private fun checkUpdate() {
        lifecycleScope.launch {
            val u = runCatching { Updater.check() }.getOrNull() ?: return@launch
            AlertDialog.Builder(this@TvActivity)
                .setTitle(getString(R.string.upd_available, u.versionName))
                .setMessage(R.string.upd_message)
                .setNegativeButton(R.string.upd_later, null)
                .setPositiveButton(R.string.upd_now) { _, _ -> downloadAndInstall(u) }
                .show()
        }
    }

    private fun downloadAndInstall(u: Updater.Update) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.upd_downloading)
            .setCancelable(false)
            .create()
        dialog.show()
        lifecycleScope.launch {
            val file = runCatching { Updater.download(this@TvActivity, u.apkUrl) {} }.getOrNull()
            dialog.dismiss()
            if (file != null) runCatching { Updater.install(this@TvActivity, file) }
        }
    }
}
