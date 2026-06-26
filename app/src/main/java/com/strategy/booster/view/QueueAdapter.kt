package com.strategy.booster.view

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.strategy.booster.R
import com.strategy.booster.data.DownloadEntry
import com.strategy.booster.data.DownloadStatus
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.math.abs

class QueueAdapter(
    private val onTogglePauseResume: (DownloadEntry) -> Unit,
    private val onDelete: ((DownloadEntry) -> Unit)? = null
) : ListAdapter<DownloadEntry, QueueAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<DownloadEntry>() {
            override fun areItemsTheSame(o: DownloadEntry, n: DownloadEntry) = o.id == n.id
            override fun areContentsTheSame(o: DownloadEntry, n: DownloadEntry) = o == n
        }

        private fun pct(downloaded: Long, total: Long?): Int =
            if (total != null && total > 0)
                ((downloaded * 100) / total).toInt().coerceIn(0, 100)
            else 0
    }

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).id

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val foreground: View = v.findViewById(R.id.foregroundCard)
        val deleteAction: View = v.findViewById(R.id.deleteAction)

        val icon: ImageView = v.findViewById(R.id.fileIcon)
        val name: TextView = v.findViewById(R.id.fileName)
        val status: TextView = v.findViewById(R.id.fileStatus)
        val pctText: TextView = v.findViewById(R.id.fileProgressText)
        val circle: CircularProgressIndicator = v.findViewById(R.id.fileProgressCircle)
    }

    private fun human(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.2f MB", mb)
        val gb = mb / 1024.0
        return String.format("%.2f GB", gb)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_queue_download, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = getItem(position)

        val percent = pct(e.downloadedBytes, e.totalBytes)
        h.pctText.text = "$percent%"
        if (e.totalBytes != null && e.totalBytes > 0) {
            h.circle.isIndeterminate = false
            h.circle.max = 100
            h.circle.progress = percent
        } else {
            h.circle.isIndeterminate = true
        }

        h.icon.setImageResource(FileIcons.iconFor(e.displayName))

        val decodedName = try {
            URLDecoder.decode(e.displayName, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            e.displayName
        }
        h.name.text = decodedName

        val stateText = when (e.status) {
            DownloadStatus.DOWNLOADING -> {
                val sizeLine = e.totalBytes?.let { "${human(e.downloadedBytes)} / ${human(it)}" }
                    ?: human(e.downloadedBytes)
                "Downloading…\n$sizeLine"
            }
            DownloadStatus.PAUSED   -> "Paused"
            DownloadStatus.PENDING  -> "Pending"
            DownloadStatus.FINISHED -> "Finished"
            DownloadStatus.FAILED   -> "Failed"
        }
        h.status.text = stateText

        val ctx = h.itemView.context
        val pctColor = when (e.status) {
            DownloadStatus.DOWNLOADING -> ContextCompat.getColor(ctx, android.R.color.holo_green_light)
            DownloadStatus.PAUSED, DownloadStatus.PENDING -> ContextCompat.getColor(ctx, android.R.color.darker_gray)
            DownloadStatus.FINISHED    -> ContextCompat.getColor(ctx, android.R.color.holo_blue_light)
            DownloadStatus.FAILED      -> ContextCompat.getColor(ctx, android.R.color.holo_red_light)
        }
        h.pctText.setTextColor(pctColor)
        h.circle.progressTintList = ColorStateList.valueOf(pctColor)

        h.foreground.translationX = 0f
        h.foreground.isClickable = true
        h.deleteAction.isEnabled = false
        h.deleteAction.isClickable = false
        h.deleteAction.alpha = 0.5f

        val revealWidthPx = h.itemView.resources.displayMetrics.density * 65f
        setDeleteEnabled(h, revealWidthPx)

        h.deleteAction.setOnClickListener {
            val pos = h.adapterPosition
            if (pos != RecyclerView.NO_POSITION) onDelete?.invoke(getItem(pos))
        }
        h.foreground.setOnClickListener {
            val pos = h.adapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            val opened = h.foreground.translationX <= -0.9f * revealWidthPx
            if (!opened) onTogglePauseResume(getItem(pos))
        }
    }

    private fun setDeleteEnabled(h: VH, revealWidthPx: Float) {
        val opened = h.foreground.translationX <= -0.9f * revealWidthPx
        h.deleteAction.isEnabled = opened
        h.deleteAction.isClickable = opened
        h.deleteAction.alpha = if (opened) 1f else 0.5f
        h.foreground.isClickable = !opened
    }

    fun onSwipeProgress(holder: RecyclerView.ViewHolder, revealWidthPx: Float) {
        (holder as? VH)?.let { setDeleteEnabled(it, revealWidthPx) }
    }

    fun closeRow(holder: RecyclerView.ViewHolder) {
        (holder as? VH)?.foreground?.animate()?.translationX(0f)?.setDuration(120)?.start()
    }
}
