package com.openremo.data

import android.content.Context
import android.util.Log
import com.openremo.ble.RemoBleClient
import com.openremo.protocol.AcState
import com.openremo.protocol.ConnectionInfo
import com.openremo.protocol.ControlFlags
import com.openremo.protocol.DeviceCapabilities
import com.openremo.protocol.FanSpeed
import com.openremo.protocol.LogEntry
import com.openremo.protocol.Louver
import com.openremo.protocol.Mode
import com.openremo.protocol.MoveEye
import com.openremo.protocol.ScannedDevice
import com.openremo.protocol.Vane
import com.openremo.protocol.Vent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RemoRepository {

    enum class Phase { DISCONNECTED, CONNECTING, AUTHENTICATING, READY, RECONNECTING, ERROR }

    data class UiState(
        val phase: Phase = Phase.DISCONNECTED,
        val device: ConnectionInfo = ConnectionInfo(),
        val savedDevice: ConnectionInfo = ConnectionInfo(),
        val ac: AcState = AcState(),
        val busy: Boolean = false,
        val error: String? = null,
        val scanning: Boolean = false,
        val scanDevices: List<ScannedDevice> = emptyList(),
        val pollSeconds: Int = 5,
        val autoReconnect: Boolean = true,
        val guestMode: Boolean = true,
        val password: String = "",
        val autoConnect: Boolean = true,
        val lastUpdate: Long = 0,
        /** 登录时读到的机型能力表；known=false 时按全量控件显示 */
        val capabilities: DeviceCapabilities = DeviceCapabilities(),
    ) {
        val connected: Boolean get() = phase == Phase.READY
    }

    private lateinit var prefs: Prefs
    private lateinit var ble: RemoBleClient
    private lateinit var engine: ProtocolEngine

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commandMutex = Mutex()

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs = _logs.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    private var connectionJob: Job? = null
    private var pollingJob: Job? = null
    private var scanTimeoutJob: Job? = null

    @Volatile
    private var readyDeferred: CompletableDeferred<Unit>? = null

    @Volatile
    private var lostDeferred: CompletableDeferred<String>? = null

    @Volatile
    private var userStopped = true

    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        prefs = Prefs(context.applicationContext)
        ble = RemoBleClient(context.applicationContext)
        engine = ProtocolEngine(ble) { tag, message -> log(tag, message) }

        ble.listener = object : RemoBleClient.Listener {
            override fun onBleReady() {
                readyDeferred?.complete(Unit)
            }

            override fun onBleDisconnected(reason: String) {
                log("连接", reason)
                readyDeferred?.completeExceptionally(ProtocolException(reason))
                lostDeferred?.complete(reason)
            }

            override fun onBleFrame(frame: com.openremo.protocol.L1Frame) {
                engine.onFrame(frame)
            }

            override fun onBleLog(tag: String, message: String) {
                log(tag, message)
            }
        }
        ble.scanListener = { device ->
            scope.launch {
                _uiState.update { state ->
                    val existing = state.scanDevices.filterNot { it.address == device.address }
                    state.copy(scanDevices = (existing + device).sortedByDescending { it.rssi })
                }
            }
        }

        _uiState.update {
            it.copy(
                savedDevice = ConnectionInfo(prefs.deviceName, prefs.deviceAddress),
                device = ConnectionInfo(prefs.deviceName, prefs.deviceAddress),
                pollSeconds = prefs.pollSeconds,
                autoReconnect = prefs.autoReconnect,
                guestMode = prefs.guestMode,
                password = prefs.password,
                autoConnect = prefs.autoConnect,
            )
        }

        if (prefs.autoConnect && prefs.deviceAddress.isNotBlank() && ble.hasPermissions()) {
            connectSaved()
        }
    }

    fun release() {
        disconnect()
        ble.release()
        initialized = false
    }

    // ---------------------------------------------------------------- 连接

    fun connectSaved() {
        val address = prefs.deviceAddress
        if (address.isBlank()) {
            message("还没有保存设备，请先在“设备”页扫描连接")
            return
        }
        connect(address, prefs.deviceName, prefs.guestMode, prefs.password)
    }

    fun connect(address: String, name: String, guest: Boolean, password: String) {
        prefs.deviceAddress = address
        prefs.deviceName = name
        prefs.guestMode = guest
        prefs.password = password
        _uiState.update {
            it.copy(
                savedDevice = ConnectionInfo(name, address),
                device = ConnectionInfo(name, address),
                guestMode = guest,
                password = password,
                error = null,
            )
        }
        stopScan()
        stopConnectionLoop()
        userStopped = false
        startConnectionLoop()
    }

    private fun startConnectionLoop() {
        if (connectionJob?.isActive == true) return
        connectionJob = scope.launch {
            var attempt = 0
            while (isActive && !userStopped) {
                val address = prefs.deviceAddress
                if (address.isBlank()) break

                setPhase(if (attempt == 0) Phase.CONNECTING else Phase.RECONNECTING)
                val ready = CompletableDeferred<Unit>()
                val lost = CompletableDeferred<String>()
                readyDeferred = ready
                lostDeferred = lost
                try {
                    ble.connect(address)
                    val ok = withTimeoutOrNull(20_000) { ready.await() }
                    if (ok == null) throw ProtocolException("连接超时")

                    setPhase(Phase.AUTHENTICATING)
                    val handshake = engine.handshake(prefs.guestMode, prefs.password)
                    _uiState.update {
                        it.copy(capabilities = handshake.capabilities ?: DeviceCapabilities())
                    }
                    handshake.state?.let { publishState(it) }
                    if (userStopped) break

                    setPhase(Phase.READY)
                    attempt = 0
                    log("连接", "已登录，开始 ${prefs.pollSeconds} 秒轮询")
                    startPolling()

                    val reason = lost.await()
                    stopPolling()
                    if (userStopped) break
                    log("连接", "链路断开：$reason")
                    setPhase(Phase.RECONNECTING)
                    ble.disconnect()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    stopPolling()
                    ble.disconnect()
                    val msg = e.message ?: "未知错误"
                    setError(msg)
                    log("连接", "连接失败：$msg")
                    if (e is ProtocolException && e.passwordError) {
                        setPhase(Phase.ERROR)
                        message(e.message ?: "密码错误，请在设置页检查 4 位密码")
                        break
                    }
                    if (e is ProtocolException && e.code == 2) {
                        // 设备要求从头开始任务：断开重连就能清掉设备侧的旧会话，别退避太久
                        attempt = 0
                    }
                } finally {
                    readyDeferred = null
                    lostDeferred = null
                }

                if (userStopped || !prefs.autoReconnect) break
                attempt++
                val wait = (1000L shl minOf(attempt, 4)).coerceAtMost(16_000L)
                setPhase(Phase.RECONNECTING)
                log("连接", "${wait / 1000} 秒后自动重连")
                delay(wait)
            }
            if (!userStopped && _uiState.value.phase != Phase.ERROR) {
                setPhase(Phase.DISCONNECTED)
            }
        }
    }

    fun disconnect() {
        userStopped = true
        stopConnectionLoop()
        stopPolling()
        stopScan()
        ble.disconnect()
        _uiState.update { it.copy(phase = Phase.DISCONNECTED, busy = false) }
    }

    private fun stopConnectionLoop() {
        connectionJob?.cancel()
        connectionJob = null
        readyDeferred = null
        lostDeferred = null
    }

    fun forgetDevice() {
        disconnect()
        prefs.deviceAddress = ""
        prefs.deviceName = ""
        _uiState.update {
            it.copy(savedDevice = ConnectionInfo(), device = ConnectionInfo())
        }
        message("已清除保存的设备")
    }

    // ---------------------------------------------------------------- 扫描

    fun startScan() {
        if (!ble.hasPermissions()) {
            message("请先授予蓝牙扫描权限")
            return
        }
        _uiState.update { it.copy(scanning = true, scanDevices = emptyList()) }
        ble.startScan()
        scanTimeoutJob?.cancel()
        scanTimeoutJob = scope.launch {
            delay(20_000)
            if (_uiState.value.scanning) {
                stopScan()
                log("扫描", "扫描已自动停止")
            }
        }
    }

    fun stopScan() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        ble.stopScan()
        _uiState.update { it.copy(scanning = false) }
    }

    // ---------------------------------------------------------------- 状态读取 / 控制

    fun refresh() {
        if (_uiState.value.phase != Phase.READY) {
            message("尚未连接设备")
            return
        }
        scope.launch { refreshInternal() }
    }

    private suspend fun refreshInternal() {
        if (!commandMutex.tryLock()) return
        try {
            _uiState.update { it.copy(busy = true) }
            val state = engine.readState()
            if (state != null) publishState(state)
        } catch (e: Exception) {
            handleCommandError("刷新状态", e)
        } finally {
            _uiState.update { it.copy(busy = false) }
            commandMutex.unlock()
        }
    }

    fun togglePower() {
        val state = _uiState.value.ac
        applyControl(state.copy(power = !state.power), ControlFlags.POWER)
    }

    fun setPower(on: Boolean) {
        val state = _uiState.value.ac
        if (state.power == on) return
        applyControl(state.copy(power = on), ControlFlags.POWER)
    }

    fun setMode(mode: Mode) {
        val state = _uiState.value.ac
        if (state.mode == mode) return
        applyControl(state.copy(mode = mode), ControlFlags.MODE)
    }

    fun adjustTemp(delta: Double) {
        val state = _uiState.value.ac
        if (!state.mode.supportsTemperature) {
            message("当前模式不支持温度调节")
            return
        }
        val current = state.activeTemp ?: state.activeRange.lower
        val next = state.activeRange.clamp(current + delta)
        if (next == current) return
        applyControl(state.withTemp(next), ControlFlags.forTemp(state.mode))
    }

    fun setTemp(value: Double) {
        val state = _uiState.value.ac
        if (!state.mode.supportsTemperature) {
            message("当前模式不支持温度调节")
            return
        }
        val next = state.activeRange.clamp(value)
        applyControl(state.withTemp(next), ControlFlags.forTemp(state.mode))
    }

    fun setFanSpeed(speed: FanSpeed) {
        val state = _uiState.value.ac
        if (state.fanSpeed == speed) return
        applyControl(state.copy(fanSpeed = speed), ControlFlags.FAN)
    }

    fun setVane(vane: Vane) {
        val state = _uiState.value.ac
        if (state.vane == vane) return
        applyControl(state.copy(vane = vane), ControlFlags.VANE)
    }

    fun toggleLouver() {
        val state = _uiState.value.ac
        val next = if (state.louver == Louver.ON) Louver.OFF else Louver.ON
        applyControl(state.copy(louver = next), ControlFlags.LOUVER)
    }

    fun setLouver(louver: Louver) {
        val state = _uiState.value.ac
        if (state.louver == louver) return
        applyControl(state.copy(louver = louver), ControlFlags.LOUVER)
    }

    fun cycleRightLeft() {
        val state = _uiState.value.ac
        // 按界面上的自然顺序循环，而不是按协议数值大小
        val order = listOf(2, 1, 3, 4, 0, 5, 6)
        val index = order.indexOf(state.rightLeft)
        val next = order[(index + 1) % order.size]
        applyControl(state.copy(rightLeft = next), ControlFlags.RIGHT_LEFT)
    }

    fun setRightLeft(value: Int) {
        val state = _uiState.value.ac
        if (state.rightLeft == value) return
        applyControl(state.copy(rightLeft = value), ControlFlags.RIGHT_LEFT)
    }

    fun cycleMoveEye() {
        val state = _uiState.value.ac
        val values = MoveEye.entries
        val next = values[(values.indexOf(state.moveEye) + 1) % values.size]
        applyControl(state.copy(moveEye = next), ControlFlags.MOVE_EYE)
    }

    fun setMoveEye(moveEye: MoveEye) {
        val state = _uiState.value.ac
        if (state.moveEye == moveEye) return
        applyControl(state.copy(moveEye = moveEye), ControlFlags.MOVE_EYE)
    }

    fun toggleHold() {
        val state = _uiState.value.ac
        applyControl(state.copy(hold = !state.hold), ControlFlags.HOLD)
    }

    fun setHold(on: Boolean) {
        val state = _uiState.value.ac
        if (state.hold == on) return
        applyControl(state.copy(hold = on), ControlFlags.HOLD)
    }

    fun toggleInnerClean() {
        val state = _uiState.value.ac
        applyControl(state.copy(innerClean = !state.innerClean), ControlFlags.INNER_CLEAN)
    }

    fun setInnerClean(on: Boolean) {
        val state = _uiState.value.ac
        if (state.innerClean == on) return
        applyControl(state.copy(innerClean = on), ControlFlags.INNER_CLEAN)
    }

    fun cycleVent() {
        val state = _uiState.value.ac
        val values = listOf(Vent.OFF, Vent.LOW, Vent.HIGH)
        val next = values[(values.indexOf(state.vent).let { if (it < 0) 0 else it } + 1) % values.size]
        applyControl(state.copy(vent = next), ControlFlags.VENT)
    }

    fun setVent(vent: Vent) {
        val state = _uiState.value.ac
        if (state.vent == vent) return
        applyControl(state.copy(vent = vent), ControlFlags.VENT)
    }

    private fun applyControl(next: AcState, flags: ControlFlags) {
        val phase = _uiState.value.phase
        if (phase != Phase.READY) {
            message(if (phase == Phase.CONNECTING || phase == Phase.AUTHENTICATING) {
                "正在连接设备，请稍候"
            } else {
                "尚未连接设备"
            })
            return
        }
        // 先同步做乐观更新，快速连点时后续操作基于最新状态计算而不是上一轮回填值
        _uiState.update { it.copy(ac = next, error = null) }
        scope.launch {
            var rejected = false
            commandMutex.withLock {
                _uiState.update { it.copy(busy = true, error = null) }
                try {
                    val state = engine.control(next, flags, _uiState.value.capabilities.vaneControl)
                    if (state != null) {
                        publishState(state)
                    } else {
                        _uiState.update { it.copy(lastUpdate = System.currentTimeMillis()) }
                    }
                } catch (e: Exception) {
                    // 设备拒绝设置（非链路、非密码问题）时马上读一次真实状态，把乐观更新纠正回去
                    rejected = e is ProtocolException && !e.passwordError && !isLinkFailure(e)
                    handleCommandError("发送控制", e)
                } finally {
                    _uiState.update { it.copy(busy = false) }
                }
            }
            if (rejected) refreshInternal()
        }
    }

    private fun handleCommandError(action: String, e: Exception) {
        val msg = e.message ?: "未知错误"
        log("错误", "$action：$msg")
        setError(msg)
        if (e is ProtocolException && e.passwordError) {
            message("密码错误，请在设置页检查密码")
        } else if (!isLinkFailure(e)) {
            // 设备拒绝设置之类的业务错误也要让用户看到，而不是只在日志里
            message(msg)
        }
        // 设备无响应 / 链路断开通常意味着连接已经不可用，主动断开让监督循环重连
        if (isLinkFailure(e)) {
            triggerReconnect("${action}失败：$msg")
        }
    }

    private fun isLinkFailure(e: Exception): Boolean {
        if (e !is ProtocolException) return false
        val msg = e.message ?: return false
        return msg.contains("无响应") || msg.contains("超时") || msg.contains("链路已断开")
    }

    private fun triggerReconnect(reason: String) {
        log("连接", "链路异常，准备重连：$reason")
        scope.launch {
            ble.disconnect()
            lostDeferred?.complete(reason)
        }
    }

    private fun publishState(state: AcState) {
        _uiState.update {
            it.copy(ac = state, lastUpdate = System.currentTimeMillis(), error = null)
        }
    }

    private fun startPolling() {
        stopPolling()
        pollingJob = scope.launch {
            while (isActive) {
                delay(prefs.pollSeconds * 1000L)
                if (_uiState.value.phase != Phase.READY) continue
                if (!commandMutex.tryLock()) continue
                try {
                    val state = engine.readState()
                    if (state != null) publishState(state)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    log("轮询", "读取状态失败：${e.message}")
                    if (isLinkFailure(e)) {
                        triggerReconnect("轮询失败：${e.message}")
                        return@launch
                    }
                } finally {
                    commandMutex.unlock()
                }
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    // ---------------------------------------------------------------- 设置

    fun setPollSeconds(seconds: Int) {
        prefs.pollSeconds = seconds
        _uiState.update { it.copy(pollSeconds = prefs.pollSeconds) }
        if (pollingJob != null) startPolling()
    }

    fun setAutoReconnect(enabled: Boolean) {
        prefs.autoReconnect = enabled
        _uiState.update { it.copy(autoReconnect = enabled) }
    }

    fun setAutoConnect(enabled: Boolean) {
        prefs.autoConnect = enabled
        _uiState.update { it.copy(autoConnect = enabled) }
    }

    fun saveCredentials(guest: Boolean, password: String) {
        prefs.guestMode = guest
        prefs.password = password
        _uiState.update { it.copy(guestMode = guest, password = password) }
    }

    // ---------------------------------------------------------------- 日志

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun log(tag: String, message: String) {
        Log.d("OpenRemo", "[$tag] $message")
        val entry = LogEntry(System.currentTimeMillis(), tag, message)
        _logs.update { (it + entry).takeLast(500) }
    }

    fun logText(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return _logs.value.joinToString("\n") {
            "${formatter.format(Date(it.time))} [${it.tag}] ${it.message}"
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun formatTime(time: Long): String = timeFormat.format(Date(time))

    private fun setPhase(phase: Phase) {
        _uiState.update { it.copy(phase = phase) }
    }

    private fun setError(error: String) {
        _uiState.update { it.copy(error = error) }
    }

    private fun message(text: String) {
        _messages.tryEmit(text)
    }

    val isInitialized: Boolean get() = initialized

    fun hasBluetoothPermission(): Boolean = initialized && ble.hasPermissions()

    fun isBluetoothEnabled(): Boolean = initialized && ble.isBluetoothEnabled()

    fun rightLeftLabel(value: Int): String = when (value) {
        2 -> "自动"
        1 -> "摆动"
        0 -> "中央"
        3 -> "左"
        4 -> "左中"
        5 -> "右中"
        6 -> "右"
        else -> "关"
    }
}
