package com.strategy.booster.fragments

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.strategy.booster.data.DownloadStatus
import com.strategy.booster.databinding.FragmentQueueBinding
import com.strategy.booster.view.QueueAdapter
import com.strategy.booster.vm.DownloadViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class QueueFragment : Fragment() {

    private lateinit var binding: FragmentQueueBinding
    private val viewModel: DownloadViewModel by activityViewModels()
    private lateinit var adapter: QueueAdapter
    private var queueOpenedPos: Int? = null
    private var lastStatusById: Map<Long, DownloadStatus> = emptyMap()
    private var seeded = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentQueueBinding.inflate(inflater, container, false)
        val view = binding.root
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = QueueAdapter(
            onTogglePauseResume = { entry -> viewModel.togglePauseResume(entry.id) },
            onDelete = { e -> viewModel.removeNow(e.id) }
        )
        binding.queueRecycler.adapter = adapter
        attachSwipeToRevealQueue(binding.queueRecycler, adapter)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.queueDownloads.collect { list ->
                    binding.queueEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                    adapter.submitList(list)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.wifiStats.collectLatest { stat ->
                    binding.statusText.text = "Wi-Fi: ${stat.kbps} KB/s"
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.cellStats.collectLatest { stat ->
                    binding.statusText2.text = "Cell: ${stat.kbps} KB/s"
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.wifiStats.collectLatest { stat ->
                    binding.wifiStat.text = "Wi-Fi: ${viewModel.human(stat.bytes)}"
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.cellStats.collectLatest { stat ->
                    binding.cellStat.text = "Cell: ${viewModel.human(stat.bytes)}"
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.totalKbps.collectLatest { kbps ->
                    binding.totalStat.text = "Total: $kbps KB/s"
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.allDownloads.collect { list ->
                    val current = list.associate { it.id to it.status }

                    if (seeded) {
                        list.filter { e ->
                            e.status == DownloadStatus.FAILED &&
                                    (lastStatusById[e.id] != DownloadStatus.FAILED)
                        }.forEach { e ->
                            val msg = e.lastError?.takeIf { it.isNotBlank() } ?: "Failed"
                            Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
                        }
                    } else {
                        seeded = true
                    }

                    lastStatusById = current
                }
            }
        }

    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachSwipeToRevealQueue(recycler: RecyclerView, adapter: QueueAdapter) {
        val density = recycler.context.resources.displayMetrics.density
        val revealWidth = 65f * density
        val openTx = -(revealWidth + 8f * density)

        val callback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, tgt: RecyclerView.ViewHolder) = false
            override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder) =
                makeMovementFlags(0, ItemTouchHelper.LEFT)

            override fun getSwipeThreshold(vh: RecyclerView.ViewHolder) = 1f
            override fun getSwipeEscapeVelocity(defaultValue: Float) = Float.MAX_VALUE
            override fun getSwipeVelocityThreshold(defaultValue: Float) = Float.MAX_VALUE

            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {
                val pos = vh.adapterPosition
                if (pos != RecyclerView.NO_POSITION) adapter.notifyItemChanged(pos)
            }

            override fun onChildDraw(
                c: android.graphics.Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder,
                dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
            ) {
                val pos = vh.adapterPosition
                if (pos == RecyclerView.NO_POSITION) return
                val holder = vh as QueueAdapter.VH
                val fg = holder.foreground

                val clamped = maxOf(openTx, minOf(0f, dX))
                if (isCurrentlyActive) {
                    fg.translationX = clamped
                    adapter.onSwipeProgress(vh, revealWidth)

                    queueOpenedPos?.let { openPos ->
                        if (openPos != pos) {
                            (rv.findViewHolderForAdapterPosition(openPos) as? QueueAdapter.VH)?.foreground
                                ?.animate()?.translationX(0f)?.setDuration(120)?.withEndAction {
                                    (rv.adapter as? QueueAdapter)?.onSwipeProgress(
                                        rv.findViewHolderForAdapterPosition(openPos) ?: return@withEndAction,
                                        revealWidth
                                    )
                                }?.start()
                            queueOpenedPos = null
                        }
                    }
                } else {
                    val shouldOpen = kotlin.math.abs(fg.translationX) > revealWidth * 0.5f
                    if (shouldOpen) {
                        fg.animate().translationX(openTx).setDuration(140).withEndAction {
                            queueOpenedPos = pos
                            adapter.onSwipeProgress(vh, revealWidth)
                        }.start()
                    } else {
                        fg.animate().translationX(0f).setDuration(140).withEndAction {
                            if (queueOpenedPos == pos) queueOpenedPos = null
                            adapter.onSwipeProgress(vh, revealWidth)
                        }.start()
                    }
                }
                super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
            }

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                val pos = vh.adapterPosition
                if (pos == RecyclerView.NO_POSITION) return
                val opened = (queueOpenedPos == pos)
                (vh as QueueAdapter.VH).foreground.translationX = if (opened) openTx else 0f
                (rv.adapter as? QueueAdapter)?.onSwipeProgress(vh, revealWidth)
            }
        }

        ItemTouchHelper(callback).attachToRecyclerView(recycler)

        recycler.setOnTouchListener { _, ev ->
            if (ev.action == android.view.MotionEvent.ACTION_DOWN) {
                queueOpenedPos?.let { pos ->
                    val vh = recycler.findViewHolderForAdapterPosition(pos) as? QueueAdapter.VH
                    if (vh != null) {
                        val r = android.graphics.Rect()
                        vh.itemView.getGlobalVisibleRect(r)
                        if (!r.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                            vh.foreground.translationX = 0f
                            (recycler.adapter as? QueueAdapter)?.onSwipeProgress(vh, revealWidth)
                            queueOpenedPos = null
                            return@setOnTouchListener false
                        }
                    }
                }
            }
            false
        }

        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) {
                    queueOpenedPos?.let { pos ->
                        (rv.findViewHolderForAdapterPosition(pos) as? QueueAdapter.VH)?.let { h ->
                            h.foreground.translationX = 0f
                            (rv.adapter as? QueueAdapter)?.onSwipeProgress(h, revealWidth)
                        }
                        queueOpenedPos = null
                    }
                }
            }
        })
    }

}