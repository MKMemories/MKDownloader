package com.mkmemories.mkdownloader

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.mkmemories.mkdownloader.databinding.ItemCastBinding

/** Rangée « têtes d'affiche » (casting) d'une fiche film. */
class CastAdapter : RecyclerView.Adapter<CastAdapter.Holder>() {

    private val items = mutableListOf<Tmdb.Person>()

    fun submit(list: List<Tmdb.Person>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    class Holder(val ui: ItemCastBinding) : RecyclerView.ViewHolder(ui.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemCastBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val p = items[position]
        with(holder.ui) {
            castName.text = p.name
            castRole.text = p.role.orEmpty()
            castRole.isVisible = !p.role.isNullOrEmpty()
            if (!p.photo.isNullOrEmpty()) {
                castPhoto.load(p.photo) {
                    crossfade(true)
                    transformations(CircleCropTransformation())
                    placeholder(R.drawable.skeleton_box)
                    error(android.R.drawable.ic_menu_myplaces)
                }
            } else {
                castPhoto.setImageResource(android.R.drawable.ic_menu_myplaces)
            }
        }
    }
}
