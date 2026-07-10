package com.mkmemories.mkdownloader

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.mkmemories.mkdownloader.databinding.ItemFilmBinding

/** Grille d'affiches des sorties TMDB (réutilise la carte film). */
class TmdbAdapter(
    private val onOpen: (Tmdb.Movie) -> Unit,
) : RecyclerView.Adapter<TmdbAdapter.Holder>() {

    private val items = mutableListOf<Tmdb.Movie>()

    fun submit(list: List<Tmdb.Movie>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    class Holder(val ui: ItemFilmBinding) : RecyclerView.ViewHolder(ui.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemFilmBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val m = items[position]
        with(holder.ui) {
            filmTitle.text = m.title
            val meta = listOfNotNull(
                Tmdb.frenchDate(m.releaseDate, short = true) ?: m.year,
                if (m.rating > 0) "★ %.1f".format(m.rating) else null,
            ).joinToString(" · ")
            filmMeta.text = meta
            filmMeta.isVisible = meta.isNotEmpty()
            filmLang.isVisible = false
            if (!m.poster.isNullOrEmpty()) {
                filmPoster.load(m.poster) {
                    crossfade(true)
                    placeholder(R.drawable.skeleton_box)
                    error(android.R.drawable.ic_menu_slideshow)
                }
            } else {
                filmPoster.setImageResource(R.drawable.skeleton_box)
            }
            root.setOnClickListener { onOpen(m) }
        }
    }
}
