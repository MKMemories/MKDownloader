package com.mkmemories.mkdownloader.tv

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.mkmemories.mkdownloader.R

/** Fiche détail TV « 10-foot » (grand visuel + méta + description + actions). */
class TvDetailActivity : FragmentActivity() {

    companion object {
        const val EXTRA_ITEM = "item_json"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.tv_frame)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.tvContainer, TvDetailFragment())
                .commit()
        }
    }
}
