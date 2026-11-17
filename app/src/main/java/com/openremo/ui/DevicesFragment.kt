package com.openremo.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.openremo.data.RemoRepository
import com.openremo.databinding.DialogConnectBinding
import com.openremo.databinding.FragmentDevicesBinding
import com.openremo.protocol.ScannedDevice
import kotlinx.coroutines.launch
import java.util.Locale

class DevicesFragment : Fragment(), MainActivity.PermissionsAware {

    private var _binding: FragmentDevicesBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: DeviceAdapter
    private var autoScanned = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentDevicesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = DeviceAdapter { device -> showConnectDialog(device.address, device.name) }
        binding.deviceList.layoutManager = LinearLayoutManager(requireContext())
        binding.deviceList.adapter = adapter
        binding.deviceList.itemAnimator = null

        binding.btnScan.setOnClickListener {
            if (RemoRepository.uiState.value.scanning) {
                RemoRepository.stopScan()
            } else {
                val activity = activity as? MainActivity
                if (activity != null && !activity.hasBlePermissions()) {
                    activity.ensureBlePermissions()
                } else {
                    RemoRepository.startScan()
                }
            }
        }

        binding.btnPermission.setOnClickListener {
            (activity as? MainActivity)?.ensureBlePermissions()
        }

        binding.btnConnectSaved.setOnClickListener {
            val saved = RemoRepository.uiState.value.savedDevice
            if (saved.deviceAddress.isBlank()) {
                Toast.makeText(requireContext(), "请先扫描并连接一台设备", Toast.LENGTH_SHORT).show()
            } else {
                showConnectDialog(saved.deviceAddress, saved.deviceName)
            }
        }

        binding.btnDisconnect.setOnClickListener {
            RemoRepository.disconnect()
        }

        binding.btnForget.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("忘记这台设备？")
                .setMessage("将清除保存的设备地址和登录设置。")
                .setNegativeButton("取消", null)
                .setPositiveButton("忘记") { _, _ -> RemoRepository.forgetDevice() }
                .show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                RemoRepository.uiState.collect { render(it) }
            }
        }

        updatePermissionState()
        maybeAutoScan()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionState()
    }

    override fun onPermissionsResult() {
        updatePermissionState()
        if ((activity as? MainActivity)?.hasBlePermissions() == true &&
            RemoRepository.uiState.value.scanDevices.isEmpty()
        ) {
            RemoRepository.startScan()
        }
    }

    private fun updatePermissionState() {
        val granted = (activity as? MainActivity)?.hasBlePermissions() ?: false
        binding.btnPermission.isVisible = !granted
        binding.btnScan.isEnabled = granted
        if (!granted) {
            binding.scanHint.text = "需要蓝牙权限才能扫描附近的遥控器"
        } else {
            binding.scanHint.text = "靠近中央空调遥控器后点击扫描"
        }
    }

    private fun maybeAutoScan() {
        if (autoScanned) return
        val ui = RemoRepository.uiState.value
        if ((activity as? MainActivity)?.hasBlePermissions() != true) return
        if (ui.connected || ui.savedDevice.deviceAddress.isNotBlank()) return
        autoScanned = true
        binding.root.postDelayed({
            if (!isAdded) return@postDelayed
            if (RemoRepository.uiState.value.scanDevices.isEmpty()) {
                RemoRepository.startScan()
            }
        }, 350)
    }

    private fun render(ui: RemoRepository.UiState) {
        val saved = ui.savedDevice
        binding.savedName.text = saved.deviceName.ifBlank {
            if (saved.deviceAddress.isBlank()) "尚未保存设备" else "未命名遥控器"
        }
        binding.savedAddress.text = saved.deviceAddress.ifBlank { "连接一次后会自动记住" }
        binding.savedState.text = when (ui.phase) {
            RemoRepository.Phase.READY -> "已连接，状态自动刷新中"
            RemoRepository.Phase.CONNECTING -> "正在连接…"
            RemoRepository.Phase.AUTHENTICATING -> "正在登录…"
            RemoRepository.Phase.RECONNECTING -> "连接断开，正在自动重连…"
            RemoRepository.Phase.ERROR -> ui.error ?: "连接异常"
            RemoRepository.Phase.DISCONNECTED ->
                if (saved.deviceAddress.isBlank()) "未连接" else "已保存，点击连接"
        }

        binding.btnConnectSaved.isEnabled = saved.deviceAddress.isNotBlank() &&
            ui.phase != RemoRepository.Phase.READY
        binding.btnConnectSaved.alpha = if (binding.btnConnectSaved.isEnabled) 1f else 0.45f
        binding.btnDisconnect.isEnabled = ui.phase == RemoRepository.Phase.READY ||
            ui.phase == RemoRepository.Phase.CONNECTING ||
            ui.phase == RemoRepository.Phase.AUTHENTICATING ||
            ui.phase == RemoRepository.Phase.RECONNECTING
        binding.btnForget.isEnabled = saved.deviceAddress.isNotBlank()

        binding.btnScan.text = if (ui.scanning) "停止" else "扫描"
        binding.scanProgress.isVisible = ui.scanning
        binding.scanHint.text = if (ui.scanning) {
            "正在扫描，请让遥控器靠近手机…"
        } else if (ui.scanDevices.isEmpty()) {
            "靠近中央空调遥控器后点击扫描"
        } else {
            "共发现 ${ui.scanDevices.size} 个设备，点击连接"
        }
        binding.emptyHint.isVisible = ui.scanDevices.isEmpty() && !ui.scanning

        adapter.submitList(ui.scanDevices)
    }

    private fun showConnectDialog(address: String, name: String) {
        val dialogBinding = DialogConnectBinding.inflate(layoutInflater)
        val state = RemoRepository.uiState.value
        dialogBinding.dialogDevice.text = "${name.ifBlank { "遥控器" }}\n$address"
        dialogBinding.dialogGuest.isChecked = state.guestMode
        dialogBinding.dialogPassword.setText(state.password)
        updatePasswordVisibility(dialogBinding)

        dialogBinding.dialogGuest.setOnCheckedChangeListener { _, _ ->
            updatePasswordVisibility(dialogBinding)
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("连接遥控器")
            .setView(dialogBinding.root)
            .setNegativeButton("取消", null)
            .setPositiveButton("连接", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val guest = dialogBinding.dialogGuest.isChecked
                val password = dialogBinding.dialogPassword.text?.toString()
                    ?.trim()?.uppercase(Locale.US) ?: ""
                if (!guest && !Regex("[0-9A-F]{4}").matches(password)) {
                    dialogBinding.dialogPasswordLayout.error = "请输入 4 位十六进制密码，例如 32BC"
                    return@setOnClickListener
                }
                dialogBinding.dialogPasswordLayout.error = null
                dialog.dismiss()
                RemoRepository.connect(address, name, guest, password)
                (activity as? MainActivity)?.openRemote()
            }
        }
        dialog.show()
    }

    private fun updatePasswordVisibility(binding: DialogConnectBinding) {
        binding.dialogPasswordLayout.isVisible = !binding.dialogGuest.isChecked
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
