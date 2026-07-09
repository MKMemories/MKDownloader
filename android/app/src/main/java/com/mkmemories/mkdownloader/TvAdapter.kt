package com.mkmemories.mkdownloader

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.mkmemories.mkdownloader.databinding.ItemTvBinding

class TvAdapter(
    private val onPlay: (TvChannel) -> Unit,
) : RecyclerView.Adapter<TvAdapter.Holder>() {

    private val items = mutableListOf<TvChannel>()

    fun submit(list: List<TvChannel>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    class Holder(val ui: ItemTvBinding) : RecyclerView.ViewHolder(ui.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemTvBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val c = items[position]
        with(holder.ui) {
            tvName.text = c.name
            tvGroup.text = c.group ?: ""
            tvGroup.isVisible = !c.group.isNullOrEmpty()
            if (!c.logo.isNullOrEmpty()) tvLogo.load(c.logo)
            else tvLogo.setImageResource(android.R.drawable.ic_menu_slideshow)
            root.setOnClickListener { onPlay(c) }
            tvPlay.setOnClickListener { onPlay(c) }
        }
    }
}
