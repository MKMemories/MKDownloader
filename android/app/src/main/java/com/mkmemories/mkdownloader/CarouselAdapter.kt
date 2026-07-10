package com.mkmemories.mkdownloader

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.mkmemories.mkdownloader.databinding.ItemCarouselBinding

/** Un élément de carrousel : une vidéo, avec une progression optionnelle (« Reprendre »). */
data class CarouselItem(val video: VideoItem, val progress: Int = -1)

/** Carrousel horizontal réutilisé par l'accueil découverte (Reprendre / Chaînes / Tendances). */
class CarouselAdapter(
    private val onPlay: (VideoItem) -> Unit,
) : RecyclerView.Adapter<CarouselAdapter.Holder>() {

    private val items = mutableListOf<CarouselItem>()

    fun submit(list: List<CarouselItem>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    class Holder(val ui: ItemCarouselBinding) : RecyclerView.ViewHolder(ui.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemCarouselBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val v = item.video
        with(holder.ui) {
            cTitle.text = v.title
            cMeta.text = v.uploader.orEmpty()
            cMeta.isVisible = !v.uploader.isNullOrEmpty()
            if (!v.thumbnail.isNullOrEmpty()) {
                cThumb.load(v.thumbnail) { crossfade(true); placeholder(R.drawable.skeleton_box) }
            } else {
                cThumb.setImageResource(R.drawable.skeleton_box)
            }
            if (item.progress in 1..99) {
                cProgress.isVisible = true
                cProgress.setProgressCompat(item.progress, false)
            } else {
                cProgress.isVisible = false
            }
            root.setOnClickListener { onPlay(v) }
        }
    }
}
