package com.mkmemories.mkdownloader.tv

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.mkmemories.mkdownloader.R

/** Recherche vocale/clavier TV (Leanback). */
class TvSearchActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.tv_frame)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.tvContainer, TvSearchFragment())
                .commit()
        }
    }
}
