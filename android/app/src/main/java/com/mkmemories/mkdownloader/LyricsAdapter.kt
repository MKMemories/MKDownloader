package com.mkmemories.mkdownloader

import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/** Affiche les paroles synchronisées, la ligne courante mise en avant. */
class LyricsAdapter : RecyclerView.Adapter<LyricsAdapter.VH>() {

    private var lines: List<LrcLine> = emptyList()
    private var current = -1

    fun submit(list: List<LrcLine>) {
        lines = list
        current = -1
        notifyDataSetChanged()
    }

    fun setCurrent(index: Int) {
        if (index == current) return
        val prev = current
        current = index
        if (prev in lines.indices) notifyItemChanged(prev)
        if (index in lines.indices) notifyItemChanged(index)
    }

    override fun getItemCount() = lines.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = TextView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            val p = (9 * resources.displayMetrics.density).toInt()
            setPadding(0, p, 0, p)
            gravity = Gravity.CENTER
        }
        return VH(tv)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val tv = holder.itemView as TextView
        tv.text = lines[position].text.ifEmpty { "♪" }
        val ctx = tv.context
        if (position == current) {
            tv.setTextColor(ContextCompat.getColor(ctx, R.color.text))
            tv.textSize = 21f
            tv.alpha = 1f
            tv.setTypeface(null, Typeface.BOLD)
        } else {
            tv.setTextColor(ContextCompat.getColor(ctx, R.color.text_dim))
            tv.textSize = 17f
            tv.alpha = 0.55f
            tv.setTypeface(null, Typeface.NORMAL)
        }
    }

    class VH(itemView: TextView) : RecyclerView.ViewHolder(itemView)
}
