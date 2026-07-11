package com.mkmemories.mkdownloader.tv

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.mkmemories.mkdownloader.R

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
    }
}
