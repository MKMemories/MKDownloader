package com.mkmemories.mkdownloader

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.mkmemories.mkdownloader.databinding.ItemResultBinding

class ResultsAdapter(
    private val onPlay: (VideoItem) -> Unit,
    private val onDownload: (VideoItem) -> Unit,
    private val onMp3: (VideoItem) -> Unit,
) : RecyclerView.Adapter<ResultsAdapter.Holder>() {

    private val items = mutableListOf<VideoItem>()

    fun submit(list: List<VideoItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class Holder(val ui: ItemResultBinding) : RecyclerView.ViewHolder(ui.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemResultBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        with(holder.ui) {
            resultTitle.text = item.title
            val meta = listOfNotNull(
                item.uploader?.takeIf { it.isNotEmpty() },
                formatDuration(item.durationSec).takeIf { it.isNotEmpty() },
            ).joinToString(" · ")
            resultMeta.text = meta
            resultMeta.isVisible = meta.isNotEmpty()
            if (!item.thumbnail.isNullOrEmpty()) resultThumb.load(item.thumbnail)
            playButton.setOnClickListener { onPlay(item) }
            downloadButton.setOnClickListener { onDownload(item) }
            mp3Button.setOnClickListener { onMp3(item) }
            root.setOnClickListener { onPlay(item) }
        }
    }
}
