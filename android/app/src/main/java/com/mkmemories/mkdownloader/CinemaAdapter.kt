package com.mkmemories.mkdownloader

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.mkmemories.mkdownloader.databinding.ItemFilmBinding

/** Grille d'affiches de la bibliothèque cinéma. */
class CinemaAdapter(
    private val onOpen: (Film) -> Unit,
) : RecyclerView.Adapter<CinemaAdapter.Holder>() {

    private val items = mutableListOf<Film>()

    fun submit(list: List<Film>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    class Holder(val ui: ItemFilmBinding) : RecyclerView.ViewHolder(ui.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemFilmBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val f = items[position]
        with(holder.ui) {
            filmTitle.text = f.title
            val meta = listOfNotNull(f.year?.toString(), f.genres.firstOrNull()).joinToString(" · ")
            filmMeta.text = meta
            filmMeta.isVisible = meta.isNotEmpty()
            filmLang.text = f.language.orEmpty()
            filmLang.isVisible = !f.language.isNullOrEmpty()
            filmPoster.load(f.poster) {
                crossfade(true)
                placeholder(R.drawable.skeleton_box)
                error(android.R.drawable.ic_menu_slideshow)
            }
            root.setOnClickListener { onOpen(f) }
        }
    }
}
