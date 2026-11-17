package com.openremo.ui.view

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.openremo.R
import com.openremo.data.RemoRepository
import com.openremo.databinding.FragmentRemoteBinding
import com.openremo.protocol.AcState
import com.openremo.protocol.DeviceCapabilities
import com.openremo.protocol.FanSpeed
import com.openremo.protocol.Louver
import com.openremo.protocol.Mode
import com.openremo.protocol.Vane
import kotlinx.coroutines.launch
import java.util.Locale

class RemoteFragment : Fragment() {

    private var _binding: FragmentRemoteBinding? = null
    private val binding get() = _binding!!

    private var rendering = false

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
        setupClicks()
        setupChips()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                RemoRepository.uiState.collect { render(it) }
            }
        }
    }

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
        binding.statusTitle.text = when (ui.phase) {
            RemoRepository.Phase.DISCONNECTED -> getString(R.string.status_disconnected)
            RemoRepository.Phase.CONNECTING -> getString(R.string.status_connecting)
            RemoRepository.Phase.AUTHENTICATING -> getString(R.string.status_authenticating)
            RemoRepository.Phase.READY -> getString(R.string.status_ready)
            RemoRepository.Phase.RECONNECTING -> getString(R.string.status_reconnecting)
            RemoRepository.Phase.ERROR -> getString(R.string.status_error)
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

        val deviceName = ui.device.deviceName.ifBlank { "未选择设备" }
        binding.statusSubtitle.text = if (ui.device.deviceAddress.isBlank()) {
            deviceName
        } else {
            "$deviceName · ${ui.device.deviceAddress}"
        }

        binding.roomTemp.text = ac.roomTemp?.let { String.format(Locale.getDefault(), "%.1f°C", it) } ?: "--"
        binding.powerButton.setPower(ac.power)

        binding.heroMode.text = ac.mode.uiMode.label
        binding.heroPower.text = if (ac.power) "运行中" else "已关机"
        binding.heroHint.text = when {
            !ui.connected -> "请先在“设备”页连接遥控器"
            ac.power -> "点击温度圆盘或左右按钮调节"
            else -> "已关机，可先预设温度和模式"
        }

        // 温度圆盘始终回填状态；是否可操作统一在 applyEnabledState 里判断
        // 先更新范围再更新温度，避免切模式时被旧范围裁掉
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

    /**
     * 按机型能力表隐藏设备根本不支持的控件，避免用户点了才发现“该机型不支持”。
     * 没读到能力表时（[DeviceCapabilities.known] = false）保持全量显示。
     */
    private fun applyCapabilities(ui: RemoRepository.UiState) {
        val cap = ui.capabilities

        binding.chipCool.setVisible(cap.supportsMode(Mode.COOL))
        binding.chipHeat.setVisible(cap.supportsMode(Mode.HEAT))
        binding.chipDry.setVisible(cap.supportsMode(Mode.DRY))
        binding.chipFanMode.setVisible(cap.supportsMode(Mode.FAN))
        binding.chipAuto.setVisible(cap.supportsMode(Mode.AUTO))

        val steps = if (cap.known) cap.fanSteps else 3
        binding.chipFanAuto.setVisible(cap.fanAuto)
        // 档位表：1=低/高，2=低/中/高，3=静音/低/中/高（真机 40MAAC 实测 3 档含静音）
        binding.chipFanSilent.setVisible(steps >= 3)
        binding.chipFanLow.setVisible(steps >= 1)
        binding.chipFanMiddle.setVisible(steps >= 2)
        binding.chipFanHigh.setVisible(steps >= 1)
        // 原 SDK 的 setSpeed 对 8/9（强力/急速）直接返回 INVALID_SET，界面不再提供
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

    /**
     * 统一根据连接状态、忙状态、设备操作锁和机型模式能力设置控件可用性，
     * 避免界面看起来可点、实际发送后必然失败。
     */
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

        // menu 锁对应原 App 的附加功能菜单
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
        super.onDestroyView()
        _binding = null
    }
}
