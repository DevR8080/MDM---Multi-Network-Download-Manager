package com.strategy.booster.fragments

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.strategy.booster.R
import com.strategy.booster.databinding.FragmentStatsBinding
import com.strategy.booster.vm.DownloadViewModel
import kotlinx.coroutines.launch

class StatsFragment : Fragment() {

    private lateinit var binding: FragmentStatsBinding
    private val vm: DownloadViewModel by viewModels({ requireActivity() })

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentStatsBinding.inflate(inflater, container, false)
        val view = binding.root
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.stats.collect { s ->
                    binding.bytesWifi.text = human(s.wifiBytes)
                    binding.bytesCell.text = human(s.cellBytes)
                    binding.bytesTotal.text = human(s.totalBytes)

                    binding.filesFinished.text = s.finishedCount.toString()
                    binding.filesQueue.text = s.queueCount.toString()

                    binding.peakWifi.text = "${s.peakWifiKbps} KB/s"
                    binding.peakCell.text = "${s.peakCellKbps} KB/s"
                    binding.peakTotal.text = "${s.peakTotalKbps} KB/s"
                }
            }
        }
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

}