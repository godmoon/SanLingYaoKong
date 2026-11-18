package com.openremo.ui.view

import android.app.AlarmManager
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.openremo.R
import com.openremo.data.RemoRepository
import com.openremo.data.RemoteRecord
import com.openremo.databinding.DialogRemoteEditBinding
import com.openremo.databinding.DialogScanDevicesBinding
import com.openremo.databinding.FragmentRemoteBinding
import com.openremo.protocol.AcState
import com.openremo.protocol.DeviceCapabilities
import com.openremo.protocol.FanSpeed
import com.openremo.protocol.Louver
import com.openremo.protocol.Mode
import com.openremo.protocol.Vane
import com.openremo.ui.DeviceAdapter
import com.openremo.ui.LogActivity
import com.openremo.ui.MainActivity
import com.openremo.ui.RemoteTab
import com.openremo.ui.RemoteTabAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class RemoteFragment : Fragment(), MainActivity.PermissionsAware {

    private var _binding: FragmentRemoteBinding? = null
    private val binding get() = _binding!!

    private var rendering = false

    private lateinit var remoteAdapter: RemoteTabAdapter
    private var scanJob: Job? = null
    private var pendingAdd = false
    private var passwordPromptedFor: String? = null

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentRemoteBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRemoteList()
        setupClicks()
        setupChips()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                RemoRepository.uiState.collect { render(it) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 页面可见时每 5 秒扫描一次信号，用于卡片排序
        RemoRepository.startSignalMonitor()
    }

    override fun onStop() {
        RemoRepository.stopSignalMonitor()
        super.onStop()
    }

    // ---------------------------------------------------------------- 顶部切换条

    private fun setupRemoteList() {
        remoteAdapter = RemoteTabAdapter(
            onClick = { record -> RemoRepository.selectRecord(record.address) },
            onLongClick = { anchor, record -> showTabMenu(anchor, record) },
        )
        binding.remoteList.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.remoteList.adapter = remoteAdapter
        binding.remoteList.itemAnimator = null
    }

    private fun showTabMenu(anchor: View, record: RemoteRecord) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, MENU_RENAME, 0, "重命名")
        popup.menu.add(0, MENU_EDIT, 1, "编辑连接信息")
        popup.menu.add(0, MENU_TIMER, 2, "设定时关闭")
        if (record.hasTimer) {
            popup.menu.add(0, MENU_CANCEL_TIMER, 3, "取消定时")
        }
        popup.menu.add(0, MENU_DELETE, 4, "删除")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_RENAME -> showRenameDialog(record)
                MENU_EDIT -> showEditDialog(record.address, record.deviceName, record)
                MENU_TIMER -> showTimerPicker(record)
                MENU_CANCEL_TIMER -> RemoRepository.clearTimer(record.address)
                MENU_DELETE -> confirmDelete(record)
            }
            true
        }
        popup.show()
    }

    private fun showRenameDialog(record: RemoteRecord) {
        val input = android.widget.EditText(requireContext()).apply {
            setText(record.alias)
            hint = "例如：主卧、客厅"
            setSelection(text.length)
            setPadding(48, 32, 48, 16)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("重命名遥控器")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val alias = input.text?.toString()?.trim().orEmpty()
                RemoRepository.renameRecord(record.address, alias)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showPasswordErrorDialog(record: RemoteRecord) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("「${record.title}」密码错误")
            .setMessage(
                "遥控器拒绝了当前密码，所以登录失败。\n\n" +
                    "请核对遥控器机身或说明书上的 4 位十六进制密码（0-9、A-F）；" +
                    "如果这台遥控器没有设置用户密码，请改用访客模式。",
            )
            .setNegativeButton("知道了", null)
            .setPositiveButton("修改密码") { _, _ ->
                showEditDialog(record.address, record.deviceName, record)
            }
            .show()
    }

    private fun confirmDelete(record: RemoteRecord) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("删除「${record.title}」？")
            .setMessage("将同时清除这台遥控器的保存记录和定时任务。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> RemoRepository.deleteRecord(record.address) }
            .show()
    }

    // ---------------------------------------------------------------- 添加 / 编辑设备

    private fun addRemote() {
        val activity = activity as? MainActivity ?: return
        if (!activity.hasBlePermissions()) {
            pendingAdd = true
            activity.ensureBlePermissions()
            return
        }
        showAddDialog()
    }

    override fun onPermissionsResult() {
        val granted = (activity as? MainActivity)?.hasBlePermissions() == true
        if (pendingAdd) {
            pendingAdd = false
            if (granted) showAddDialog()
        }
    }

    private fun showAddDialog() {
        val dialogBinding = DialogScanDevicesBinding.inflate(layoutInflater)
        val adapter = DeviceAdapter { device ->
            RemoRepository.stopScan()
            scanDialog?.dismiss()
            showEditDialog(device.address, device.name, null, device.rssi)
        }
        dialogBinding.scanList.layoutManager = LinearLayoutManager(requireContext())
        dialogBinding.scanList.adapter = adapter

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("添加遥控器")
            .setView(dialogBinding.root)
            .setNegativeButton("关闭", null)
            .setNeutralButton("重新扫描", null)
            .create()
        scanDialog = dialog

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                RemoRepository.startScan()
            }
        }
        dialog.setOnDismissListener {
            scanJob?.cancel()
            scanJob = null
            scanDialog = null
            RemoRepository.stopScan()
        }
        dialog.show()

        scanJob?.cancel()
        scanJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                RemoRepository.uiState.collect { ui ->
                    adapter.submitList(ui.scanDevices)
                    dialogBinding.scanProgress.visibility =
                        if (ui.scanning) View.VISIBLE else View.GONE
                    dialogBinding.scanEmpty.visibility =
                        if (ui.scanDevices.isEmpty() && !ui.scanning) View.VISIBLE else View.GONE
                    dialogBinding.scanTip.text = when {
                        ui.scanning -> "正在扫描新遥控器…（已添加的不再显示）"
                        ui.scanDevices.isEmpty() ->
                            "没有发现新遥控器：已添加过的不再显示，可点“重新扫描”"
                        else -> "发现 ${ui.scanDevices.size} 台新遥控器，点击即可添加"
                    }
                }
            }
        }
        RemoRepository.startScan()
    }

    private fun showEditDialog(
        address: String,
        deviceName: String,
        record: RemoteRecord?,
        scanRssi: Int = RemoteRecord.RSSI_UNKNOWN,
    ) {
        val editBinding = DialogRemoteEditBinding.inflate(layoutInflater)
        editBinding.editDevice.text = "${deviceName.ifBlank { "遥控器" }}\n$address"
        editBinding.editAlias.setText(record?.alias.orEmpty())
        editBinding.editGuest.isChecked = record?.guest ?: true
        editBinding.editPassword.setText(record?.password.orEmpty())
        updatePasswordVisibility(editBinding)
        editBinding.editGuest.setOnCheckedChangeListener { _, _ ->
            updatePasswordVisibility(editBinding)
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (record == null) "添加遥控器" else "编辑遥控器")
            .setView(editBinding.root)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val guest = editBinding.editGuest.isChecked
                val password = editBinding.editPassword.text?.toString()
                    ?.trim()?.uppercase(Locale.US).orEmpty()
                if (!guest && !Regex("[0-9A-F]{4}").matches(password)) {
                    editBinding.editPasswordLayout.error = "请输入 4 位十六进制密码，例如 32BC"
                    return@setOnClickListener
                }
                editBinding.editPasswordLayout.error = null

                val merged = RemoteRecord(
                    address = address,
                    alias = editBinding.editAlias.text?.toString()?.trim().orEmpty(),
                    deviceName = deviceName.ifBlank {
                        record?.deviceName.orEmpty()
                    },
                    guest = guest,
                    password = password,
                    rssi = record?.rssi ?: scanRssi,
                    timerAt = record?.timerAt ?: 0L,
                )
                if (record == null) {
                    RemoRepository.upsertRecord(merged, connectNow = true)
                } else {
                    RemoRepository.upsertRecord(merged)
                    if (RemoRepository.uiState.value.selectedAddress == address) {
                        // 凭据变了需要重新登录
                        RemoRepository.connect(
                            address, merged.deviceName, merged.guest, merged.password,
                        )
                    }
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun updatePasswordVisibility(binding: DialogRemoteEditBinding) {
        binding.editPasswordLayout.visibility =
            if (binding.editGuest.isChecked) View.GONE else View.VISIBLE
    }

    // ---------------------------------------------------------------- 定时

    private fun showTimerPicker(record: RemoteRecord) {
        (activity as? MainActivity)?.ensureNotificationPermission()
        val calendar = Calendar.getInstance().apply {
            timeInMillis = if (record.hasTimer) record.timerAt else defaultTimerTime()
        }
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(calendar.get(Calendar.HOUR_OF_DAY))
            .setMinute(calendar.get(Calendar.MINUTE))
            .setTitleText("选择关闭时间")
            .build()
        picker.addOnPositiveButtonClickListener {
            val at = nextTrigger(picker.hour, picker.minute)
            RemoRepository.setTimer(record.address, at)
            warnIfExactAlarmUnavailable()
        }
        picker.show(childFragmentManager, "timer_picker")
    }

    private fun defaultTimerTime(): Long {
        val calendar = Calendar.getInstance().apply {
            add(Calendar.HOUR_OF_DAY, 1)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    private fun nextTrigger(hour: Int, minute: Int): Long {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (calendar.timeInMillis <= System.currentTimeMillis() + 5_000) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        return calendar.timeInMillis
    }

    private fun warnIfExactAlarmUnavailable() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val manager = requireContext().getSystemService(AlarmManager::class.java) ?: return
        if (manager.canScheduleExactAlarms()) return
        Snackbar.make(binding.root, "未授予“精确闹钟”权限，定时可能延后几分钟", Snackbar.LENGTH_LONG)
            .setAction("去授权") {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:${requireContext().packageName}")
                }
                runCatching { startActivity(intent) }
            }
            .show()
    }

    // ---------------------------------------------------------------- 原有遥控操作

    private fun setupClicks() {
        binding.powerButton.setOnClickListener { RemoRepository.togglePower() }
        binding.refreshButton.setOnClickListener { RemoRepository.refresh() }

        binding.tempDial.onStep = { delta -> RemoRepository.adjustTemp(delta) }
        binding.tempDial.onCommit = { value -> RemoRepository.setTemp(value) }

        binding.btnLouver.setOnClickListener { RemoRepository.toggleLouver() }
        binding.btnRightLeft.setOnClickListener { RemoRepository.cycleRightLeft() }
        binding.btnMoveEye.setOnClickListener { RemoRepository.cycleMoveEye() }
        binding.btnHold.setOnClickListener { RemoRepository.toggleHold() }
        binding.btnInnerClean.setOnClickListener { RemoRepository.toggleInnerClean() }
        binding.btnVent.setOnClickListener { RemoRepository.cycleVent() }

        binding.btnAddRemote.setOnClickListener { addRemote() }
        binding.btnMenu.setOnClickListener { showOverflowMenu() }

        binding.btnTimerSet.setOnClickListener {
            val record = RemoRepository.uiState.value.selectedRecord ?: return@setOnClickListener
            showTimerPicker(record)
        }
        binding.btnTimerCancel.setOnClickListener {
            val record = RemoRepository.uiState.value.selectedRecord ?: return@setOnClickListener
            RemoRepository.clearTimer(record.address)
        }
    }

    private fun showOverflowMenu() {
        val popup = PopupMenu(requireContext(), binding.btnMenu)
        popup.menu.add(0, MENU_LOGS, 0, "查看日志")
        popup.menu.add(0, MENU_DISCONNECT, 1, "断开连接")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_LOGS -> startActivity(Intent(requireContext(), LogActivity::class.java))
                MENU_DISCONNECT -> RemoRepository.disconnect()
            }
            true
        }
        popup.show()
    }

    private fun setupChips() {
        binding.chipCool.setOnCheckedChangeListener { _, checked ->
            if (checked && !rendering) RemoRepository.setMode(Mode.COOL)
        }
        binding.chipHeat.setOnCheckedChangeListener { _, checked ->
            if (checked && !rendering) RemoRepository.setMode(Mode.HEAT)
        }
        binding.chipDry.setOnCheckedChangeListener { _, checked ->
            if (checked && !rendering) RemoRepository.setMode(Mode.DRY)
        }
        binding.chipFanMode.setOnCheckedChangeListener { _, checked ->
            if (checked && !rendering) RemoRepository.setMode(Mode.FAN)
        }
        binding.chipAuto.setOnCheckedChangeListener { _, checked ->
            if (checked && !rendering) RemoRepository.setMode(Mode.AUTO)
        }

        binding.chipFanAuto.setOnCheckedChangeListener { _, checked ->
            if (checked && !rendering) RemoRepository.setFanSpeed(FanSpeed.AUTO)
        }
        binding.chipFanSilent.setOnCheckedChangeListener { _, checked ->
            if (checked && !rendering) RemoRepository.setFanSpeed(FanSpeed.SILENT)
        }
        binding.chipFanLow.setOnCheckedChangeListener { _, checked ->
            if (checked && !rendering) RemoRepository.setFanSpeed(FanSpeed.LOW)
        }
        binding.chipFanMiddle.setOnCheckedChangeListener { _, checked ->
            if (checked && !rendering) RemoRepository.setFanSpeed(FanSpeed.MIDDLE)
        }
        binding.chipFanHigh.setOnCheckedChangeListener { _, checked ->
            if (checked && !rendering) RemoRepository.setFanSpeed(FanSpeed.HIGH)
        }
        binding.chipFanHighPower.setOnCheckedChangeListener { _, checked ->
            if (checked && !rendering) RemoRepository.setFanSpeed(FanSpeed.HIGH_POWER)
        }
        binding.chipFanRapid.setOnCheckedChangeListener { _, checked ->
            if (checked && !rendering) RemoRepository.setFanSpeed(FanSpeed.RAPID)
        }

        binding.chipVaneAuto.setOnCheckedChangeListener { _, checked ->
            if (checked && !rendering) RemoRepository.setVane(Vane.AUTO)
        }
        binding.chipVaneOne.setOnCheckedChangeListener { _, checked ->
            if (checked && !rendering) RemoRepository.setVane(Vane.ONE)
        }
        binding.chipVaneTwo.setOnCheckedChangeListener { _, checked ->
            if (checked && !rendering) RemoRepository.setVane(Vane.TWO)
        }
        binding.chipVaneThree.setOnCheckedChangeListener { _, checked ->
            if (checked && !rendering) RemoRepository.setVane(Vane.THREE)
        }
        binding.chipVaneFour.setOnCheckedChangeListener { _, checked ->
            if (checked && !rendering) RemoRepository.setVane(Vane.FOUR)
        }
        binding.chipVaneFive.setOnCheckedChangeListener { _, checked ->
            if (checked && !rendering) RemoRepository.setVane(Vane.FIVE)
        }
        binding.chipVaneSwing.setOnCheckedChangeListener { _, checked ->
            if (checked && !rendering) RemoRepository.setVane(Vane.SWING)
        }
    }

    private fun render(ui: RemoRepository.UiState) {
        val ac = ui.ac
        val record = ui.selectedRecord

        binding.statusTitle.text = when (ui.phase) {
            RemoRepository.Phase.DISCONNECTED -> getString(R.string.status_disconnected)
            RemoRepository.Phase.CONNECTING -> getString(R.string.status_connecting)
            RemoRepository.Phase.AUTHENTICATING -> getString(R.string.status_authenticating)
            RemoRepository.Phase.READY -> getString(R.string.status_ready)
            RemoRepository.Phase.RECONNECTING -> getString(R.string.status_reconnecting)
            RemoRepository.Phase.ERROR ->
                if (ui.passwordError) "密码错误，登录失败" else getString(R.string.status_error)
        }

        val dotColor = when (ui.phase) {
            RemoRepository.Phase.READY -> R.color.success
            RemoRepository.Phase.CONNECTING,
            RemoRepository.Phase.AUTHENTICATING,
            RemoRepository.Phase.RECONNECTING -> R.color.warning

            RemoRepository.Phase.ERROR -> R.color.danger
            RemoRepository.Phase.DISCONNECTED -> R.color.text_tertiary
        }
        binding.statusDot.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(requireContext(), dotColor))

        binding.statusSubtitle.text = when {
            // 连接失败或重连时直接把原因显示在顶部，而不是只在日志里
            !ui.error.isNullOrBlank() &&
                (ui.phase == RemoRepository.Phase.ERROR ||
                    ui.phase == RemoRepository.Phase.RECONNECTING) -> ui.error
            record == null -> "点击右上角 + 添加遥控器"
            record.address.isBlank() -> record.title
            else -> "${record.title} · ${record.address}"
        }
        // 密码错误时弹一次对话框，给出原因和修改入口
        if (ui.passwordError) {
            val target = record
            if (target != null && passwordPromptedFor != target.address) {
                passwordPromptedFor = target.address
                showPasswordErrorDialog(target)
            }
        } else if (ui.phase == RemoRepository.Phase.CONNECTING) {
            passwordPromptedFor = null
        }

        // 按每轮扫描后生成的顺序快照展示，避免信号刷新时卡片频繁跳动
        val order = ui.displayOrder
        val orderedRecords = if (order.isEmpty()) {
            ui.records
        } else {
            ui.records.sortedBy { item ->
                val index = order.indexOf(item.address)
                if (index < 0) Int.MAX_VALUE else index
            }
        }
        remoteAdapter.submitList(
            orderedRecords.map { item ->
                RemoteTab(
                    record = item,
                    selected = item.address == ui.selectedAddress,
                    phase = ui.phase,
                    connected = ui.connected && item.address == ui.device.deviceAddress,
                )
            },
        )

        renderTimer(record)

        binding.roomTemp.text = ac.roomTemp?.let { String.format(Locale.getDefault(), "%.1f°C", it) } ?: "--"
        binding.powerButton.setPower(ac.power)

        binding.heroMode.text = ac.mode.uiMode.label
        binding.heroPower.text = if (ac.power) "运行中" else "已关机"
        binding.heroHint.text = when {
            ui.records.isEmpty() -> "点击右上角 + 添加遥控器"
            ui.phase == RemoRepository.Phase.DISCONNECTED -> "未连接，点击上方卡片重试"
            ui.phase == RemoRepository.Phase.ERROR -> ui.error ?: "连接异常"
            !ui.connected -> "正在连接 ${record?.title ?: "遥控器"}…"
            ac.power -> "点击温度圆盘或左右按钮调节"
            else -> "已关机，可先预设温度和模式"
        }

        binding.tempDial.range = ac.activeRange
        binding.tempDial.temperature = ac.activeTemp
        binding.tempDial.powered = ac.power && ui.connected
        binding.tempRangeHint.text = when {
            !ac.mode.supportsTemperature -> "当前模式无需设定温度"
            !ui.connected -> "连接后可调节温度"
            !ac.power -> String.format(
                Locale.getDefault(),
                "已关机 · 可预设 %.1f ~ %.1f °C",
                ac.activeRange.lower,
                ac.activeRange.upper,
            )

            else -> String.format(
                Locale.getDefault(),
                "可调范围 %.1f ~ %.1f °C",
                ac.activeRange.lower,
                ac.activeRange.upper,
            )
        }

        rendering = true
        binding.chipCool.isChecked = ac.mode == Mode.COOL
        binding.chipHeat.isChecked = ac.mode == Mode.HEAT
        binding.chipDry.isChecked = ac.mode == Mode.DRY
        binding.chipFanMode.isChecked = ac.mode == Mode.FAN
        binding.chipAuto.isChecked = ac.mode.uiMode == Mode.AUTO

        binding.chipFanAuto.isChecked = ac.fanSpeed == FanSpeed.AUTO
        binding.chipFanSilent.isChecked = ac.fanSpeed == FanSpeed.SILENT
        binding.chipFanLow.isChecked = ac.fanSpeed == FanSpeed.LOW
        binding.chipFanMiddle.isChecked = ac.fanSpeed == FanSpeed.MIDDLE
        binding.chipFanHigh.isChecked = ac.fanSpeed == FanSpeed.HIGH
        binding.chipFanHighPower.isChecked = ac.fanSpeed == FanSpeed.HIGH_POWER
        binding.chipFanRapid.isChecked = ac.fanSpeed == FanSpeed.RAPID

        binding.chipVaneAuto.isChecked = ac.vane == Vane.AUTO
        binding.chipVaneOne.isChecked = ac.vane == Vane.ONE
        binding.chipVaneTwo.isChecked = ac.vane == Vane.TWO
        binding.chipVaneThree.isChecked = ac.vane == Vane.THREE
        binding.chipVaneFour.isChecked = ac.vane == Vane.FOUR
        binding.chipVaneFive.isChecked = ac.vane == Vane.FIVE
        binding.chipVaneSwing.isChecked = ac.vane == Vane.SWING
        rendering = false

        binding.btnLouver.text = "百叶 ${if (ac.louver == Louver.ON) "开" else "关"}"
        binding.btnRightLeft.text = "左右 ${RemoRepository.rightLeftLabel(ac.rightLeft)}"
        binding.btnMoveEye.text = "人感 ${ac.moveEye.label}"
        binding.btnHold.text = "保持 ${if (ac.hold) "开" else "关"}"
        binding.btnInnerClean.text = "内部清洁 ${if (ac.innerClean) "开" else "关"}"
        binding.btnVent.text = "换气 ${ac.vent.label}"

        val error = ui.error
        binding.errorText.visibility = if (error.isNullOrBlank()) View.GONE else View.VISIBLE
        binding.errorText.text = error

        binding.refreshButton.isEnabled = ui.connected && !ui.busy
        binding.refreshButton.alpha = if (ui.connected && !ui.busy) 1f else 0.4f

        applyCapabilities(ui)
        applyEnabledState(ui, ac)
    }

    private fun renderTimer(record: RemoteRecord?) {
        val context = requireContext()
        if (record == null) {
            binding.timerStatus.text = "未选择"
            binding.timerStatus.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            binding.timerHint.text = "先添加一台遥控器"
            binding.btnTimerSet.text = "设定时关闭"
            binding.btnTimerSet.isEnabled = false
            binding.btnTimerSet.alpha = 0.45f
            binding.btnTimerCancel.visibility = View.GONE
            return
        }

        binding.btnTimerSet.isEnabled = true
        binding.btnTimerSet.alpha = 1f

        if (record.hasTimer) {
            binding.timerStatus.text =
                "${dayLabel(record.timerAt)} ${timeFormat.format(Date(record.timerAt))}"
            binding.timerStatus.setTextColor(ContextCompat.getColor(context, R.color.primary))
            binding.timerHint.text =
                "还有 ${remainingText(record.timerAt)}，到点自动关闭「${record.title}」"
            binding.btnTimerSet.text = "修改时间"
            binding.btnTimerCancel.visibility = View.VISIBLE
        } else {
            binding.timerStatus.text = "未设置"
            binding.timerStatus.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            binding.timerHint.text = "只执行一次。设定后应用会在后台保持运行，到点自动关闭空调。"
            binding.btnTimerSet.text = "设定时关闭"
            binding.btnTimerCancel.visibility = View.GONE
        }
    }

    private fun dayLabel(at: Long): String {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply { timeInMillis = at }
        val sameDay = now.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
        return if (sameDay) "今天" else "明天"
    }

    private fun remainingText(at: Long): String {
        val minutes = ((at - System.currentTimeMillis()) / 60_000L).coerceAtLeast(1)
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours > 0 && rest > 0 -> "${hours} 小时 ${rest} 分钟"
            hours > 0 -> "${hours} 小时"
            else -> "${rest} 分钟"
        }
    }

    private fun applyCapabilities(ui: RemoRepository.UiState) {
        val cap = ui.capabilities

        binding.chipCool.setVisible(cap.supportsMode(Mode.COOL))
        binding.chipHeat.setVisible(cap.supportsMode(Mode.HEAT))
        binding.chipDry.setVisible(cap.supportsMode(Mode.DRY))
        binding.chipFanMode.setVisible(cap.supportsMode(Mode.FAN))
        binding.chipAuto.setVisible(cap.supportsMode(Mode.AUTO))

        val steps = if (cap.known) cap.fanSteps else 3
        binding.chipFanAuto.setVisible(cap.fanAuto)
        binding.chipFanSilent.setVisible(steps >= 3)
        binding.chipFanLow.setVisible(steps >= 1)
        binding.chipFanMiddle.setVisible(steps >= 2)
        binding.chipFanHigh.setVisible(steps >= 1)
        binding.chipFanHighPower.setVisible(false)
        binding.chipFanRapid.setVisible(false)

        binding.vaneSection.setVisible(cap.supportsVane)
        binding.chipVaneFive.setVisible(cap.supportsFiveStepVane)
        binding.chipVaneSwing.setVisible(cap.supportsVaneSwing)

        val hasLouver = cap.supportsLouver
        val hasRightLeft = cap.supportsRightLeft
        val hasMoveEye = cap.supportsMoveEye
        val hasHold = cap.supportsHold
        val hasInnerClean = cap.supportsInnerClean
        val hasVent = cap.supportsVent

        binding.btnLouver.setVisible(hasLouver)
        binding.btnRightLeft.setVisible(hasRightLeft)
        binding.btnMoveEye.setVisible(hasMoveEye)
        binding.btnHold.setVisible(hasHold)
        binding.btnInnerClean.setVisible(hasInnerClean)
        binding.btnVent.setVisible(hasVent)

        binding.rowExtra1.setVisible(hasLouver || hasRightLeft || hasMoveEye)
        binding.rowExtra2.setVisible(hasHold || hasInnerClean || hasVent)
        binding.additionalTitle.setVisible(
            hasLouver || hasRightLeft || hasMoveEye || hasHold || hasInnerClean || hasVent,
        )
    }

    private fun View.setVisible(visible: Boolean) {
        visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun applyEnabledState(ui: RemoRepository.UiState, ac: AcState) {
        val idle = ui.connected && !ui.busy
        val lock = ac.opLock
        val modeEnabled = idle && !lock.mode

        binding.powerButton.isEnabled = idle && !lock.power
        binding.powerButton.alpha = if (idle && !lock.power) 1f else 0.45f

        setChipEnabled(binding.chipCool, modeEnabled && !ac.modeLimits.cool)
        setChipEnabled(binding.chipHeat, modeEnabled && !ac.modeLimits.heat)
        setChipEnabled(binding.chipDry, modeEnabled && !ac.modeLimits.dry)
        setChipEnabled(binding.chipFanMode, modeEnabled)
        setChipEnabled(binding.chipAuto, modeEnabled && !ac.modeLimits.auto)

        val fanEnabled = idle && !lock.fanSpeed
        setChipEnabled(binding.chipFanAuto, fanEnabled)
        setChipEnabled(binding.chipFanSilent, fanEnabled)
        setChipEnabled(binding.chipFanLow, fanEnabled)
        setChipEnabled(binding.chipFanMiddle, fanEnabled)
        setChipEnabled(binding.chipFanHigh, fanEnabled)
        setChipEnabled(binding.chipFanHighPower, fanEnabled)
        setChipEnabled(binding.chipFanRapid, fanEnabled)

        val vaneEnabled = idle && !lock.vane
        setChipEnabled(binding.chipVaneAuto, vaneEnabled)
        setChipEnabled(binding.chipVaneOne, vaneEnabled)
        setChipEnabled(binding.chipVaneTwo, vaneEnabled)
        setChipEnabled(binding.chipVaneThree, vaneEnabled)
        setChipEnabled(binding.chipVaneFour, vaneEnabled)
        setChipEnabled(binding.chipVaneFive, vaneEnabled)
        setChipEnabled(binding.chipVaneSwing, vaneEnabled)

        val tempEnabled = idle && ac.mode.supportsTemperature && !lock.setTemp
        binding.tempDial.isEnabled = tempEnabled
        binding.tempDial.alpha = if (tempEnabled) 1f else 0.55f

        val menuEnabled = idle && !lock.menu
        setButtonEnabled(binding.btnRightLeft, menuEnabled)
        setButtonEnabled(binding.btnMoveEye, menuEnabled)
        setButtonEnabled(binding.btnInnerClean, menuEnabled)
        setButtonEnabled(binding.btnVent, menuEnabled)
        setButtonEnabled(binding.btnLouver, idle && !lock.louver)
        setButtonEnabled(binding.btnHold, idle && !lock.hold)
    }

    private fun setChipEnabled(chip: com.google.android.material.chip.Chip, enabled: Boolean) {
        chip.isEnabled = enabled
        chip.alpha = if (enabled) 1f else 0.45f
    }

    private fun setButtonEnabled(button: android.view.View, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.45f
    }

    override fun onDestroyView() {
        scanJob?.cancel()
        scanJob = null
        scanDialog?.dismiss()
        scanDialog = null
        super.onDestroyView()
        _binding = null
    }

    private var scanDialog: AlertDialog? = null

    private companion object {
        const val MENU_RENAME = 1
        const val MENU_EDIT = 2
        const val MENU_TIMER = 3
        const val MENU_CANCEL_TIMER = 4
        const val MENU_DELETE = 5
        const val MENU_LOGS = 6
        const val MENU_DISCONNECT = 7
    }
}
