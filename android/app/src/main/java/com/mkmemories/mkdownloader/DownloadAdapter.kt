package com.mkmemories.mkdownloader

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.mkmemories.mkdownloader.databinding.ItemDownloadBinding

/** Liste de la file de téléchargements (feuille du bas). */
class DownloadAdapter(
    private val onRetry: (Downloads.Job) -> Unit,
    private val onRemove: (Downloads.Job) -> Unit,
) : RecyclerView.Adapter<DownloadAdapter.Holder>() {

    private val items = mutableListOf<Downloads.Job>()

    fun submit(list: List<Downloads.Job>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    class Holder(val ui: ItemDownloadBinding) : RecyclerView.ViewHolder(ui.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val job = items[position]
        val ctx = holder.ui.root.context
        with(holder.ui) {
            dlTitle.text = job.item.title
            dlStatus.text = when (job.status) {
                Downloads.Status.QUEUED -> ctx.getString(R.string.dl_status_queued)
                Downloads.Status.RUNNING ->
                    if (job.percent >= 0) ctx.getString(R.string.dl_status_running, job.percent)
                    else ctx.getString(R.string.dl_status_processing)
                Downloads.Status.DONE -> ctx.getString(R.string.dl_status_done)
                Downloads.Status.ERROR -> job.error ?: ctx.getString(R.string.dl_status_error)
            }
            val running = job.status == Downloads.Status.RUNNING
            dlProgress.isVisible = running
            if (running) {
                dlProgress.isIndeterminate = job.percent < 0
                if (job.percent >= 0) dlProgress.setProgressCompat(job.percent, true)
            }
            dlRetry.isVisible = job.status == Downloads.Status.ERROR
            dlRemove.isVisible = !running
            dlRetry.setOnClickListener { onRetry(job) }
            dlRemove.setOnClickListener { onRemove(job) }
        }
    }
}
