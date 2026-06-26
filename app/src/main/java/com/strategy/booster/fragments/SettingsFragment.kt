package com.strategy.booster.fragments

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.strategy.booster.databinding.FragmentSettingsBinding
import com.strategy.booster.vm.DownloadViewModel
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private lateinit var binding: FragmentSettingsBinding
    private val vm: DownloadViewModel by viewModels({ requireActivity() })

    private val pickDir = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            vm.setDownloadDir(uri.toString())
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentSettingsBinding.inflate(inflater, container, false)
        val view = binding.root
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {

        val useBothListener = android.widget.CompoundButton.OnCheckedChangeListener { _, isChecked ->
            vm.setUseBoth(isChecked)
        }
        binding.switchUseBoth.setOnCheckedChangeListener(useBothListener)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.settings.collect { s ->
                    binding.switchUseBoth.setOnCheckedChangeListener(null)
                    binding.switchUseBoth.isChecked = s.useBoth
                    binding.switchUseBoth.setOnCheckedChangeListener(useBothListener)
                    binding.seekWifi.progress = s.wifiSessions
                    binding.seekCell.progress = s.cellSessions
                    binding.seekWifiVal.text = s.wifiSessions.toString()
                    binding.seekCellVal.text = s.cellSessions.toString()

                    binding.inputWifiQuotaGb.setText(
                        if (s.wifiQuotaBytes == 0L) "0"
                        else (s.wifiQuotaBytes / (1024L * 1024L * 1024L)).toString()
                    )
                    binding.inputCellQuotaGb.setText(
                        if (s.cellQuotaBytes == 0L) "0"
                        else (s.cellQuotaBytes / (1024L * 1024L * 1024L)).toString()
                    )

                    binding.dirLabel.text = s.downloadDirUri ?: "System Downloads (default)"
                }
            }
        }

        binding.seekWifiVal.text = binding.seekWifi.progress.toString()
        binding.seekCellVal.text = binding.seekCell.progress.toString()

        binding.seekWifi.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (binding.seekWifiVal.text.toString() != p.toString()) {
                    binding.seekWifiVal.text = p.toString()
                }
                if (fromUser) vm.setWifiSessions(p)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        binding.seekCell.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (binding.seekCellVal.text.toString() != p.toString()) {
                    binding.seekCellVal.text = p.toString()
                }
                if (fromUser) vm.setCellSessions(p)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.settings.collect { s ->
                    if (binding.inputMaxRetries.text.toString() != s.maxRetries.toString()) {
                        binding.inputMaxRetries.setText(s.maxRetries.toString())
                    }
                }
            }
        }

        binding.btnSaveLimits.setOnClickListener {
            val wifiGb = binding.inputWifiQuotaGb.text.toString().toIntOrNull() ?: 0
            val cellGb = binding.inputCellQuotaGb.text.toString().toIntOrNull() ?: 0
            val v = binding.inputMaxRetries.text.toString().toIntOrNull() ?: 0
            vm.setWifiQuotaGb(wifiGb)
            vm.setCellQuotaGb(cellGb)
            vm.setMaxRetries(v)
            Toast.makeText(requireContext(), "Saved!", Toast.LENGTH_SHORT).show()
        }

        binding.btnChangeDir.setOnClickListener { pickDir.launch(null) }
        binding.btnResetDir.setOnClickListener { vm.setDownloadDir(null) }
    }

    private fun simpleSeek(on: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) on(progress)
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }
}