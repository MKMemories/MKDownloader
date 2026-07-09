package com.mkmemories.mkdownloader

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mkmemories.mkdownloader.databinding.ItemPlaylistBinding

data class PlaylistRow(val name: String, val count: Int)

class PlaylistAdapter(
    private val onOpen: (String) -> Unit,
    private val onPlay: (String) -> Unit,
    private val onDelete: (String) -> Unit,
) : RecyclerView.Adapter<PlaylistAdapter.Holder>() {

    private val items = mutableListOf<PlaylistRow>()

    fun submit(list: List<PlaylistRow>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    class Holder(val ui: ItemPlaylistBinding) : RecyclerView.ViewHolder(ui.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemPlaylistBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        with(holder.ui) {
            playlistName.text = item.name
            playlistCount.text = if (item.count > 1) "${item.count} morceaux" else "${item.count} morceau"
            playButton.setOnClickListener { onPlay(item.name) }
            deleteButton.setOnClickListener { onDelete(item.name) }
            root.setOnClickListener { onOpen(item.name) }
        }
    }
}
