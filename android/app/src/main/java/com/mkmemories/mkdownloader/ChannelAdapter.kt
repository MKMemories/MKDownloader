package com.mkmemories.mkdownloader

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.mkmemories.mkdownloader.databinding.ItemChannelBinding

class ChannelAdapter(
    private val isFav: (ChannelItem) -> Boolean,
    private val onOpen: (ChannelItem) -> Unit,
    private val onToggleFav: (ChannelItem) -> Unit,
) : RecyclerView.Adapter<ChannelAdapter.Holder>() {

    private val items = mutableListOf<ChannelItem>()

    fun submit(list: List<ChannelItem>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    class Holder(val ui: ItemChannelBinding) : RecyclerView.ViewHolder(ui.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        with(holder.ui) {
            channelName.text = item.name
            if (!item.thumbnail.isNullOrEmpty()) channelAvatar.load(item.thumbnail)
            else channelAvatar.setImageResource(android.R.drawable.sym_def_app_icon)
            favButton.setIconResource(
                if (isFav(item)) android.R.drawable.btn_star_big_on
                else android.R.drawable.btn_star_big_off
            )
            favButton.setOnClickListener { onToggleFav(item); notifyItemChanged(position) }
            openButton.setOnClickListener { onOpen(item) }
            root.setOnClickListener { onOpen(item) }
        }
    }
}
