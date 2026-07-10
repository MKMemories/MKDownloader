package com.mkmemories.mkdownloader

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/** File d'attente réordonnable : glisser pour réordonner, balayer pour retirer. */
class QueueAdapter(
    private val items: MutableList<VideoItem>,
    private val currentUrl: () -> String?,
    private val onTap: (Int) -> Unit,
) : RecyclerView.Adapter<QueueAdapter.VH>() {

    fun move(from: Int, to: Int) {
        items.add(to, items.removeAt(from))
        notifyItemMoved(from, to)
    }

    fun removeAt(pos: Int) {
        items.removeAt(pos)
        notifyItemRemoved(pos)
    }

    fun snapshot(): List<VideoItem> = items.toList()

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_queue, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = items[position]
        val playing = t.url == currentUrl()
        holder.title.text = t.title
        holder.artist.text = t.uploader ?: t.channelName ?: ""
        val ctx = holder.itemView.context
        holder.title.setTextColor(
            ContextCompat.getColor(ctx, if (playing) R.color.accent2 else R.color.text)
        )
        holder.title.setTypeface(null, if (playing) Typeface.BOLD else Typeface.NORMAL)
        holder.itemView.setOnClickListener {
            val p = holder.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onTap(p)
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.queueTitle)
        val artist: TextView = v.findViewById(R.id.queueArtist)
    }
}
