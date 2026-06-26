package com.strategy.booster.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.strategy.booster.R
import com.strategy.booster.data.DownloadEntry
import com.strategy.booster.databinding.FragmentFinishedBinding
import com.strategy.booster.view.FinishedDownloadsAdapter
import com.strategy.booster.vm.DownloadViewModel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class FinishedFragment : Fragment() {

    private lateinit var binding: FragmentFinishedBinding
    private val viewModel: DownloadViewModel by viewModels({ requireActivity() })
    private var openedPos: Int? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentFinishedBinding.inflate(inflater, container, false)
        val view = binding.root
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {

        val adapter = FinishedDownloadsAdapter(
            onOpen = { entry -> openDownload(entry) },
            onDelete = { entry -> viewModel.remove(entry.id) }
        )
        binding.finishedRecycler.adapter = adapter
        attachSwipeToReveal(binding.finishedRecycler, adapter)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.finishedDownloads.collect { list ->
                    binding.emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                    adapter.submitList(list)
                }
            }
        }

        binding.chipClearKeep.setOnClickListener {
            confirmClearAll(keepFiles = true)
        }
        binding.chipClearDelete.setOnClickListener {
            confirmClearAll(keepFiles = false)
        }

    }

    private fun bestMimeFor(context: Context, uri: Uri, displayName: String?): String {
        context.contentResolver.getType(uri)?.let { return it }

        DocumentFile.fromSingleUri(context, uri)?.type?.let { t ->
            if (!t.isNullOrBlank() && t != "application/octet-stream") return t
        }

        val name = displayName ?: uri.lastPathSegment.orEmpty()
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext == "apk") return "application/vnd.android.package-archive"
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }

        return "application/octet-stream"
    }

    private fun broadenIfGeneric(displayName: String, mime: String?): String {
        if (mime.isNullOrBlank() || mime == "application/octet-stream") {
            val name = displayName.lowercase()

            // Images
            if (name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".png") || name.endsWith(".gif")
                || name.endsWith(".webp") || name.endsWith(".bmp") || name.endsWith(".heic")
            ) return "image/*"

            // Video
            if (name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".mov")
                || name.endsWith(".avi") || name.endsWith(".webm") || name.endsWith(".3gp")
            ) return "video/*"

            // Audio
            if (name.endsWith(".mp3") || name.endsWith(".aac") || name.endsWith(".flac")
                || name.endsWith(".wav") || name.endsWith(".m4a") || name.endsWith(".ogg")
            ) return "audio/*"

            // Documents
            if (name.endsWith(".pdf")) return "application/pdf"
            if (name.endsWith(".epub")) return "application/epub+zip"
            if (name.endsWith(".doc") || name.endsWith(".docx")) return "application/msword"
            if (name.endsWith(".xls") || name.endsWith(".xlsx")) return "application/vnd.ms-excel"
            if (name.endsWith(".ppt") || name.endsWith(".pptx")) return "application/vnd.ms-powerpoint"
            if (name.endsWith(".txt")) return "text/plain"
            if (name.endsWith(".html") || name.endsWith(".htm")) return "text/html"
            if (name.endsWith(".zip") || name.endsWith(".rar") || name.endsWith(".7z")) return "application/zip"

            return "*/*"
        }
        return mime
    }

    private fun openDownload(entry: DownloadEntry) {
        val uri = entry.uri.toUri()
        val fileName = entry.displayName.lowercase()

        if (fileName.endsWith(".apk")) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val fallback = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            runCatching { startActivity(intent) }
                .onFailure { runCatching { startActivity(fallback) } }
            return
        }

        val mimePrecise = bestMimeFor(requireContext(), uri, entry.displayName)
        val mime = broadenIfGeneric(entry.displayName, mimePrecise)

        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        runCatching {
            startActivity(Intent.createChooser(view, "Open with"))
        }.onFailure {
            val any = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { startActivity(Intent.createChooser(any, "Open with")) }
                .onFailure {
                    val bare = Intent(Intent.ACTION_VIEW).apply {
                        data = uri
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching { startActivity(bare) }
                }
        }
    }

    private fun confirmClearAll(keepFiles: Boolean) {
        val title = if (keepFiles) "Clear finished (keep files)" else "Clear finished (delete files)"
        val message = if (keepFiles) {
            "This will remove all finished items from the list.\nDownloaded files will remain on the device."
        } else {
            "This will remove all finished items and delete the downloaded files from storage."
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Confirm") { _, _ ->
                if (keepFiles) viewModel.clearFinishedRecordsOnly()
                else viewModel.clearFinishedWithFiles()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachSwipeToReveal(recycler: RecyclerView, adapter: FinishedDownloadsAdapter) {
        val density = recycler.context.resources.displayMetrics.density
        val revealWidth = 65f * density

        val callback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {

            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false

            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {
                val pos = vh.adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    adapter.notifyItemChanged(pos)
                }
            }

            override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
                return makeMovementFlags(0, ItemTouchHelper.LEFT)
            }

            override fun getSwipeThreshold(vh: RecyclerView.ViewHolder) = 1f
            override fun getSwipeEscapeVelocity(defaultValue: Float) = Float.MAX_VALUE
            override fun getSwipeVelocityThreshold(defaultValue: Float) = Float.MAX_VALUE

            override fun onChildDraw(
                c: android.graphics.Canvas,
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                val pos = vh.adapterPosition
                if (pos == RecyclerView.NO_POSITION) return
                val holder = vh as FinishedDownloadsAdapter.VH
                val fg = holder.foreground

                val clamped = max(-revealWidth, min(0f, dX))
                if (isCurrentlyActive) {
                    fg.translationX = clamped
                    openedPos?.let { openPos ->
                        if (openPos != pos) {
                            (rv.findViewHolderForAdapterPosition(openPos) as? FinishedDownloadsAdapter.VH)
                                ?.foreground?.animate()?.translationX(0f)?.setDuration(120)?.start()
                            openedPos = null
                        }
                    }
                } else {
                    val currentTx = fg.translationX
                    val shouldOpen = abs(currentTx) > revealWidth * 0.5f
                    if (shouldOpen) {
                        fg.animate().translationX(-revealWidth).setDuration(140).start()
                        openedPos = pos
                    } else {
                        fg.animate().translationX(0f).setDuration(140).start()
                        if (openedPos == pos) openedPos = null
                    }
                }

                super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
            }

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                val pos = vh.adapterPosition
                if (pos == RecyclerView.NO_POSITION) return
                val holder = vh as FinishedDownloadsAdapter.VH
                val fg = holder.foreground
                if (openedPos == pos) {
                    fg.translationX = -revealWidth
                } else {
                    fg.translationX = 0f
                }
            }
        }

        ItemTouchHelper(callback).attachToRecyclerView(recycler)

        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) closeOpened(rv)
            }
        })
        recycler.setOnTouchListener { v, ev ->
            if (ev.action == android.view.MotionEvent.ACTION_DOWN) {
                openedPos?.let { pos ->
                    val vh = recycler.findViewHolderForAdapterPosition(pos) as? FinishedDownloadsAdapter.VH
                    val r = android.graphics.Rect()
                    vh?.itemView?.getGlobalVisibleRect(r)
                    if (!r.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                        closeOpened(recycler)
                        return@setOnTouchListener true
                    }
                }
            }
            false
        }
    }

    private fun closeOpened(rv: RecyclerView) {
        val pos = openedPos ?: return
        (rv.findViewHolderForAdapterPosition(pos) as? FinishedDownloadsAdapter.VH)
            ?.foreground?.animate()?.translationX(0f)?.setDuration(120)?.start()
        openedPos = null
    }

}