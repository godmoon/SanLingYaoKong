package com.openremo.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.openremo.data.RemoRepository
import com.openremo.databinding.FragmentSettingsBinding
import kotlinx.coroutines.launch
import java.util.Locale

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private var rendering = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.switchGuest.setOnCheckedChangeListener { _, checked ->
            if (rendering) return@setOnCheckedChangeListener
            binding.passwordLayout.isVisible = !checked
            RemoRepository.saveCredentials(checked, binding.inputPassword.text?.toString() ?: "")
        }

        binding.switchAutoConnect.setOnCheckedChangeListener { _, checked ->
            if (!rendering) RemoRepository.setAutoConnect(checked)
        }

        binding.switchAutoReconnect.setOnCheckedChangeListener { _, checked ->
            if (!rendering) RemoRepository.setAutoReconnect(checked)
        }

        binding.seekPoll.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val seconds = progress + 2
                binding.pollLabel.text = "状态刷新间隔：$seconds 秒"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val seconds = (seekBar?.progress ?: 3) + 2
                RemoRepository.setPollSeconds(seconds)
            }
        })

        binding.btnSaveLogin.setOnClickListener { saveLogin() }

        binding.btnOpenLogs.setOnClickListener {
            startActivity(Intent(requireContext(), LogActivity::class.java))
        }

        binding.btnDisconnectSettings.setOnClickListener {
            RemoRepository.disconnect()
            Toast.makeText(requireContext(), "已断开连接", Toast.LENGTH_SHORT).show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                RemoRepository.uiState.collect { render(it) }
            }
        }
    }

    private fun render(ui: RemoRepository.UiState) {
        rendering = true
        binding.switchGuest.isChecked = ui.guestMode
        binding.passwordLayout.isVisible = !ui.guestMode
        if (binding.inputPassword.text?.toString() != ui.password) {
            binding.inputPassword.setText(ui.password)
        }
        binding.switchAutoConnect.isChecked = ui.autoConnect
        binding.switchAutoReconnect.isChecked = ui.autoReconnect

        binding.seekPoll.progress = (ui.pollSeconds - 2).coerceIn(0, 28)
        binding.pollLabel.text = "状态刷新间隔：${ui.pollSeconds} 秒"

        binding.settingsDevice.text = if (ui.savedDevice.deviceAddress.isBlank()) {
            "当前设备：未选择"
        } else {
            "当前设备：${ui.savedDevice.deviceName.ifBlank { "遥控器" }}\n${ui.savedDevice.deviceAddress}"
        }
        binding.btnDisconnectSettings.isEnabled = ui.phase != RemoRepository.Phase.DISCONNECTED
        binding.btnDisconnectSettings.alpha =
            if (binding.btnDisconnectSettings.isEnabled) 1f else 0.45f
        rendering = false
    }

    private fun saveLogin() {
        val guest = binding.switchGuest.isChecked
        val password = binding.inputPassword.text?.toString()?.trim()?.uppercase(Locale.US) ?: ""
        if (!guest && !Regex("[0-9A-F]{4}").matches(password)) {
            binding.passwordLayout.error = "请输入 4 位十六进制密码，例如 32BC"
            return
        }
        binding.passwordLayout.error = null
        RemoRepository.saveCredentials(guest, password)

        val saved = RemoRepository.uiState.value.savedDevice
        if (saved.deviceAddress.isNotBlank()) {
            RemoRepository.connect(saved.deviceAddress, saved.deviceName, guest, password)
            Toast.makeText(requireContext(), "登录设置已保存，正在重新连接", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), "登录设置已保存", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
