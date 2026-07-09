package com.mkmemories.mkdownloader

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mkmemories.mkdownloader.databinding.ItemHistoryBinding

class HistoryAdapter(
    private val onOpen: (HistoryEntry) -> Unit,
    private val onDelete: (HistoryEntry) -> Unit,
) : RecyclerView.Adapter<HistoryAdapter.Holder>() {

    private val items = mutableListOf<HistoryEntry>()

    fun submit(list: List<HistoryEntry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class Holder(val ui: ItemHistoryBinding) : RecyclerView.ViewHolder(ui.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        with(holder.ui) {
            historyTitle.text = item.title
            val kind = if (item.audio) "MP3" else "Vidéo"
            val ago = DateUtils.getRelativeTimeSpanString(
                item.timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
            )
            historyMeta.text = "${item.platform} · $kind · $ago"
            openButton.setOnClickListener { onOpen(item) }
            deleteButton.setOnClickListener { onDelete(item) }
            root.setOnClickListener { onOpen(item) }
        }
    }
}
