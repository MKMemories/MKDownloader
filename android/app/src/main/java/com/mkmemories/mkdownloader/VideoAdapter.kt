package com.mkmemories.mkdownloader

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.mkmemories.mkdownloader.databinding.ItemResultBinding

/** Liste de vidéos/morceaux : tap = lecture, ★ = favori, ⋮ = toutes les actions. */
class VideoAdapter(
    private val isFav: (VideoItem) -> Boolean,
    private val onPlay: (VideoItem) -> Unit,
    private val onToggleFav: (VideoItem) -> Unit,
    private val onMore: (VideoItem, View) -> Unit,
    private val isSelected: ((VideoItem) -> Boolean)? = null,
    private val onToggleSelect: ((VideoItem) -> Unit)? = null,
) : RecyclerView.Adapter<VideoAdapter.Holder>() {

    private val items = mutableListOf<VideoItem>()

    /** Mode sélection multiple (outil d'analyse : choisir des vidéos en lot). */
    var selectionMode: Boolean = false
        set(value) { field = value; notifyDataSetChanged() }

    fun items(): List<VideoItem> = items.toList()

    fun submit(list: List<VideoItem>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    class Holder(val ui: ItemResultBinding) : RecyclerView.ViewHolder(ui.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemResultBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

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

            // Badge de source : d'où vient la vidéo (YouTube / TikTok / Instagram…).
            val src = platformOf(item.url)
            resultSource.text = src
            resultSource.isVisible = src.isNotEmpty() && src != "Autre"

            favButton.setIconResource(
                if (isFav(item)) android.R.drawable.btn_star_big_on
                else android.R.drawable.btn_star_big_off
            )
            favButton.setOnClickListener { v ->
                onToggleFav(item)
                favButton.setIconResource(
                    if (isFav(item)) android.R.drawable.btn_star_big_on
                    else android.R.drawable.btn_star_big_off
                )
                // Rebond premium sur l'étoile.
                v.animate().scaleX(1.4f).scaleY(1.4f).setDuration(110)
                    .withEndAction {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                    }.start()
            }
            moreButton.setOnClickListener { onMore(item, it) }

            val selecting = selectionMode && onToggleSelect != null
            if (selecting) {
                val sel = isSelected?.invoke(item) == true
                root.strokeWidth = if (sel) (2 * root.resources.displayMetrics.density).toInt() else 0
                root.strokeColor = androidx.core.content.ContextCompat.getColor(root.context, R.color.accent2)
                root.alpha = if (sel) 1f else 0.7f
                root.setOnClickListener { onToggleSelect?.invoke(item); notifyItemChanged(position) }
            } else {
                root.strokeWidth = 0
                root.alpha = 1f
                root.setOnClickListener { onPlay(item) }
            }
        }
    }
}
