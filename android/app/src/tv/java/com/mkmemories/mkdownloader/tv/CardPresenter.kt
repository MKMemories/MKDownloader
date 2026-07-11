package com.mkmemories.mkdownloader.tv

import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import coil.load
import com.mkmemories.mkdownloader.HistoryEntry
import com.mkmemories.mkdownloader.R
import com.mkmemories.mkdownloader.TvChannel
import com.mkmemories.mkdownloader.VideoItem

/** Carte visuelle TV (image + titre + sous-titre) pour vidéos, chaînes et téléchargements. */
class CardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val card = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(CARD_W, CARD_H)
            // Bandeau d'info sombre premium (cohérent avec l'app).
            setInfoAreaBackgroundColor(ContextCompat.getColor(context, R.color.card))
        }
        return ViewHolder(card)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val card = viewHolder.view as ImageCardView
        when (item) {
            is VideoItem -> {
                card.titleText = item.title
                card.contentText = item.uploader.orEmpty()
                item.thumbnail?.let { card.mainImageView.load(it) }
            }
            is TvChannel -> {
                card.titleText = item.name
                card.contentText = item.group ?: "Direct"
                item.logo?.let { card.mainImageView.load(it) }
            }
            is HistoryEntry -> {
                card.titleText = item.title
                card.contentText = if (item.audio) "Audio" else "Vidéo"
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        (viewHolder.view as ImageCardView).mainImage = null
    }

    private companion object {
        const val CARD_W = 352
        const val CARD_H = 198
    }
}
