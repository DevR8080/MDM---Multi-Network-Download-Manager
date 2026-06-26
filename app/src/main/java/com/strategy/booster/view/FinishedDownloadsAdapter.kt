package com.strategy.booster.view

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.strategy.booster.R
import com.strategy.booster.data.DownloadEntry
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class FinishedDownloadsAdapter(
    private val onOpen: (DownloadEntry) -> Unit,
    private val onDelete: (DownloadEntry) -> Unit
) : ListAdapter<DownloadEntry, FinishedDownloadsAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<DownloadEntry>() {
            override fun areItemsTheSame(o: DownloadEntry, n: DownloadEntry) = o.id == n.id
            override fun areContentsTheSame(o: DownloadEntry, n: DownloadEntry) = o == n
        }
        private fun human(bytes: Long?): String {
            if (bytes == null) return "—"
            if (bytes < 1024) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024) return String.format("%.1f KB", kb)
            val mb = kb / 1024.0
            if (mb < 1024) return String.format("%.2f MB", mb)
            val gb = mb / 1024.0
            return String.format("%.2f GB", gb)
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val foreground: View = v.findViewById(R.id.foregroundCard)
        val deleteAction: View = v.findViewById(R.id.deleteAction)

        val icon: ImageView = v.findViewById(R.id.fileIcon)
        val name: TextView = v.findViewById(R.id.fileName)
        val meta: TextView = v.findViewById(R.id.fileMeta)
        val open: TextView = v.findViewById(R.id.openText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_finished_download, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val item = getItem(position)
        h.icon.setImageResource(FileIcons.iconFor(item.displayName))
        val name = try {
            URLDecoder.decode(item.displayName, StandardCharsets.UTF_8.name())
        } catch (_: Exception) { item.displayName }
        h.name.text = name
        h.meta.text = "Size: ${human(item.totalBytes)}"

        h.itemView.setOnClickListener { onOpen(item) }
        h.open.setOnClickListener { onOpen(item) }
        h.deleteAction.setOnClickListener { onDelete(item) }

        h.foreground.translationX = 0f
    }
}
