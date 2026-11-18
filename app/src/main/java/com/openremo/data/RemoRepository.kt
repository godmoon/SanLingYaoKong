package com.openremo.data

import android.content.Context
import android.os.SystemClock
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
import com.openremo.timer.TimerScheduler
import com.openremo.timer.TimerService
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
import kotlinx.coroutines.flow.first
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
        /** 已保存的全部遥控器 */
        val records: List<RemoteRecord> = emptyList(),
        /** 当前选中的遥控器地址 */
        val selectedAddress: String = "",
        /** 最近一次失败是否因为密码/登录信息错误 */
        val passwordError: Boolean = false,
        /** 顶部卡片的显示顺序（按每轮扫描后的信号从强到弱），避免信号刷新时频繁重排 */
        val displayOrder: List<String> = emptyList(),
    ) {
        val connected: Boolean get() = phase == Phase.READY

        val selectedRecord: RemoteRecord?
            get() = records.firstOrNull { it.address == selectedAddress }
    }

    private lateinit var prefs: Prefs
    private lateinit var ble: RemoBleClient
    private lateinit var engine: ProtocolEngine
    private lateinit var appContext: Context

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

    /** 用户是否手动切换过遥控器，手动选择后不再自动改选中项 */
    @Volatile
    private var userSelected = false

    /** 本次进程是否已经做过启动时的 1 秒信号检测 */
    @Volatile
    private var initialSelectionDone = false

    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        prefs = Prefs(appContext)
        ble = RemoBleClient(appContext)
        engine = ProtocolEngine(ble) { tag, message -> log(tag, message) }

        ble.listener = object : RemoBleClient.Listener {
            override fun onBleReady() {
                readyDeferred?.complete(Unit)
                ble.readRssi()
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

            override fun onBleRssi(rssi: Int) {
                scope.launch { updateSelectedRssi(rssi) }
            }
        }
        ble.scanListener = { device ->
            // 窗口标记在回调线程上取一次，避免协程排队后标记已复位
            val buffering = trackingScan
            scope.launch {
                _uiState.update { state ->
                    // 已经添加过的遥控器不再出现在扫描列表里，但信号值仍会刷新
                    if (state.records.any { it.address == device.address }) {
                        state
                    } else {
                        val existing = state.scanDevices.filterNot { it.address == device.address }
                        state.copy(scanDevices = (existing + device).sortedByDescending { it.rssi })
                    }
                }
                if (buffering) {
                    // 信号扫描窗口内先攒着，等这一轮扫完再一次性刷新，避免数值每包都跳
                    scanSeen.add(device.address)
                    pendingRssi[device.address] = device.rssi
                } else {
                    // 用户手动扫描（添加遥控器）时直接刷新，列表需要实时反馈
                    updateRecordRssi(device.address, device.rssi)
                }
            }
        }

        prefs.migrateLegacyIfNeeded()
        val records = prefs.records
        val selected = prefs.selectedAddress
            .takeIf { address -> records.any { it.address == address } }
            ?: records.maxByOrNull { it.rssi }?.address
            ?: ""
        if (selected.isNotBlank()) prefs.selectedAddress = selected
        val selectedRecord = records.firstOrNull { it.address == selected }
        val legacySaved = ConnectionInfo(selectedRecord?.title ?: "", selected)

        _uiState.update {
            it.copy(
                savedDevice = legacySaved,
                device = ConnectionInfo(selectedRecord?.deviceName ?: "", selected),
                pollSeconds = prefs.pollSeconds,
                autoReconnect = prefs.autoReconnect,
                guestMode = selectedRecord?.guest ?: prefs.guestMode,
                password = selectedRecord?.password ?: prefs.password,
                autoConnect = prefs.autoConnect,
                records = records,
                selectedAddress = selected,
                displayOrder = records.sortedByDescending { it.signal }.map { it.address },
            )
        }
    }

    /** 手动切换遥控器（用户点击卡片），会阻止启动时的“自动选中信号最好”覆盖用户选择。 */
    fun selectRecord(address: String) {
        userSelected = true
        val record = prefs.records.firstOrNull { it.address == address } ?: return
        val state = _uiState.value
        if (state.selectedAddress == address) {
            when (state.phase) {
                Phase.READY, Phase.CONNECTING, Phase.AUTHENTICATING, Phase.RECONNECTING -> return
                else -> Unit
            }
        }
        connectRecord(record.address)
    }

    fun release() {
        disconnect()
        ble.release()
        initialized = false
    }

    // ---------------------------------------------------------------- 连接

    fun connectSaved() {
        val record = _uiState.value.selectedRecord ?: prefs.records.firstOrNull()
        if (record == null) {
            message("还没有保存遥控器，点击右上角 + 添加")
            return
        }
        connectRecord(record.address)
    }

    fun connectRecord(address: String) {
        val record = prefs.records.firstOrNull { it.address == address } ?: return
        if (_uiState.value.phase == Phase.READY &&
            _uiState.value.device.deviceAddress == address
        ) {
            prefs.selectedAddress = address
            _uiState.update { it.copy(selectedAddress = address) }
            return
        }
        connect(record.address, record.deviceName, record.guest, record.password)
    }

    fun connect(address: String, name: String, guest: Boolean, password: String) {
        prefs.deviceAddress = address
        prefs.deviceName = name
        prefs.guestMode = guest
        prefs.password = password
        prefs.selectedAddress = address

        val existing = prefs.records.firstOrNull { it.address == address }
        val record = (existing ?: RemoteRecord(address = address)).copy(
            deviceName = name.ifBlank { existing?.deviceName.orEmpty() },
            guest = guest,
            password = password,
        )
        val records = saveRecordInternal(record)

        _uiState.update {
            it.copy(
                savedDevice = ConnectionInfo(record.title, address),
                device = ConnectionInfo(record.deviceName, address),
                guestMode = guest,
                password = password,
                records = records,
                selectedAddress = address,
                phase = Phase.CONNECTING,
                busy = false,
                // 切换设备时先清空上一台的状态，避免误读旧数据
                ac = AcState(),
                capabilities = DeviceCapabilities(),
                passwordError = false,
                error = null,
            )
        }
        stopScan()
        // 切换遥控器时先停掉旧设备的轮询，避免旧任务对新链路发命令
        stopPolling()
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
                        _uiState.update { it.copy(passwordError = true) }
                        message("「${findRecord(prefs.deviceAddress)?.title ?: "遥控器"}」密码错误，请核对后重试")
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
        deleteRecord(_uiState.value.selectedAddress)
    }

    // ---------------------------------------------------------------- 遥控器记录

    fun findRecord(address: String): RemoteRecord? =
        _uiState.value.records.firstOrNull { it.address == address }

    fun pendingTimers(): List<RemoteRecord> =
        _uiState.value.records
            .filter { it.timerAt > System.currentTimeMillis() }
            .sortedBy { it.timerAt }

    /** 已经错过执行时间的任务。留 15 分钟宽限期，给 Doze 延迟触发的闹钟补执行机会。 */
    fun expiredTimers(): List<RemoteRecord> {
        val deadline = System.currentTimeMillis() - TIMER_GRACE_MS
        return _uiState.value.records.filter { it.timerAt in 1 until deadline }
    }

    /** 新增或更新一条记录；[connectNow] 为 true 时立即连接。 */
    fun upsertRecord(record: RemoteRecord, connectNow: Boolean = false) {
        val records = saveRecordInternal(record)
        _uiState.update {
            // 刚添加的设备同步从扫描列表里移除，避免仍显示“未添加”
            it.copy(
                records = records,
                scanDevices = it.scanDevices.filterNot { d -> d.address == record.address },
            )
        }
        if (connectNow) connectRecord(record.address)
    }

    fun renameRecord(address: String, alias: String) {
        updateRecord(address) { it.copy(alias = alias.trim()) }
    }

    fun deleteRecord(address: String) {
        if (address.isBlank()) return
        finishTimer(address)
        val records = prefs.records.filterNot { it.address == address }
        prefs.records = records
        if (prefs.selectedAddress == address) {
            disconnect()
            val next = records.firstOrNull()
            prefs.selectedAddress = next?.address.orEmpty()
            prefs.deviceAddress = next?.address.orEmpty()
            prefs.deviceName = next?.deviceName.orEmpty()
            _uiState.update {
                it.copy(
                    records = records,
                    selectedAddress = next?.address.orEmpty(),
                    savedDevice = ConnectionInfo(next?.title.orEmpty(), next?.address.orEmpty()),
                    device = ConnectionInfo(next?.deviceName.orEmpty(), next?.address.orEmpty()),
                )
            }
            if (next != null && prefs.autoConnect) connectRecord(next.address)
        } else {
            _uiState.update { it.copy(records = records) }
        }
        TimerService.sync(appContext)
        message("已删除遥控器")
    }

    // ---------------------------------------------------------------- 定时关闭

    fun setTimer(address: String, at: Long) {
        val record = updateRecord(address) { it.copy(timerAt = at) } ?: return
        TimerScheduler.schedule(appContext, record)
        TimerService.sync(appContext)
        message("已设置定时关闭")
    }

    /** 用户主动取消：同时停止前台保活服务。 */
    fun clearTimer(address: String) {
        if (findRecord(address)?.timerAt == 0L) return
        finishTimer(address)
        TimerService.sync(appContext)
        message("已取消定时关闭")
    }

    /** 只清理任务和闹钟，不启动服务；供定时服务内部使用，避免重复拉起。 */
    fun finishTimer(address: String) {
        updateRecord(address) { it.copy(timerAt = 0L) }
        TimerScheduler.cancel(appContext, address)
    }

    /**
     * 定时任务执行：连接目标遥控器并把空调关掉。
     * 失败会重试 3 轮，每轮 25 秒连接超时、间隔 10 秒。
     */
    suspend fun turnOffForTimer(address: String): Boolean {
        val record = findRecord(address) ?: return false
        repeat(3) { attempt ->
            log("定时", "第 ${attempt + 1} 次尝试关闭「${record.title}」")
            if (ensureConnectedForTimer(record)) {
                if (sendPowerOff()) {
                    log("定时", "「${record.title}」已关闭")
                    return true
                }
            }
            if (attempt < 2) delay(10_000)
        }
        log("定时", "关闭「${record.title}」失败")
        return false
    }

    private suspend fun ensureConnectedForTimer(record: RemoteRecord): Boolean {
        val state = _uiState.value
        if (state.phase == Phase.READY && state.device.deviceAddress == record.address) {
            return true
        }
        connect(record.address, record.deviceName, record.guest, record.password)
        return withTimeoutOrNull(30_000) {
            uiState.first { it.phase == Phase.READY && it.device.deviceAddress == record.address }
            true
        } ?: false
    }

    private suspend fun sendPowerOff(): Boolean = commandMutex.withLock {
        try {
            _uiState.update { it.copy(busy = true) }
            val current = engine.readState()
            if (current != null) {
                publishState(current)
                if (!current.power) return@withLock true
            }
            val next = engine.control(
                (current ?: _uiState.value.ac).copy(power = false),
                ControlFlags.POWER,
                _uiState.value.capabilities.vaneControl,
            )
            if (next != null) publishState(next)
            if (next?.power == false) return@withLock true
            val confirm = engine.readState()
            if (confirm != null) publishState(confirm)
            confirm?.power == false
        } catch (e: Exception) {
            log("定时", "发送关机命令失败：${e.message}")
            false
        } finally {
            _uiState.update { it.copy(busy = false) }
        }
    }

    // ---------------------------------------------------------------- 记录内部操作

    private fun saveRecordInternal(record: RemoteRecord): List<RemoteRecord> {
        val list = prefs.records.toMutableList()
        val index = list.indexOfFirst { it.address == record.address }
        if (index >= 0) list[index] = record else list.add(record)
        prefs.records = list
        return list
    }

    private fun updateRecord(
        address: String,
        transform: (RemoteRecord) -> RemoteRecord,
    ): RemoteRecord? {
        val list = prefs.records
        val index = list.indexOfFirst { it.address == address }
        if (index < 0) return null
        val updated = transform(list[index])
        val newList = list.toMutableList().apply { set(index, updated) }
        prefs.records = newList
        _uiState.update { it.copy(records = newList) }
        return updated
    }

    private var lastRssiSavedAt = 0L

    private fun updateSelectedRssi(rssi: Int) {
        val address = _uiState.value.selectedAddress
        if (address.isBlank()) return
        updateRecordRssi(address, rssi)
    }

    private fun updateRecordRssi(address: String, rssi: Int) {
        val records = _uiState.value.records
        val index = records.indexOfFirst { it.address == address }
        if (index < 0) return
        val updated = records[index].withRssi(rssi)
        if (updated == records[index]) return
        val newList = records.toMutableList().apply { set(index, updated) }
        _uiState.update { it.copy(records = newList) }

        // 信号只是展示用的瞬时值，最多每 30 秒落一次盘，避免扫描时频繁写 JSON
        val now = System.currentTimeMillis()
        if (now - lastRssiSavedAt > 30_000) {
            lastRssiSavedAt = now
            prefs.records = newList
        }
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

    // ---------------------------------------------------------------- 信号监测

    private var signalScanJob: Job? = null

    /** 本轮信号扫描期间扫到过的地址（只改主线程，无需加锁） */
    private val scanSeen = mutableSetOf<String>()

    /** 本轮扫描期间攒下的信号值，等轮末统一写入，避免卡片数值每包都跳 */
    private val pendingRssi = mutableMapOf<String, Int>()

    /** 是否正在做信号扫描窗口，用于判断 [scanSeen] 是否有效 */
    private var trackingScan = false

    /** 每个地址连续几轮没扫到，用于避免偶发漏扫导致卡片乱跳 */
    private val missedRounds = mutableMapOf<String, Int>()

    /**
     * 首页可见时的信号任务：
     * 1. 启动后先扫 1 秒，把信号最好的遥控器设为默认选中（整轮只做一次）；
     * 2. 之后每 5 秒扫一轮，只更新信号并刷新一次显示顺序，不再改动选中项。
     */
    fun startSignalMonitor() {
        if (signalScanJob?.isActive == true) return
        signalScanJob = scope.launch {
            // 等权限 / 等有遥控器记录，然后做一次启动检测
            while (isActive && !initialSelectionDone) {
                val state = _uiState.value
                if (state.records.isEmpty() || !ble.hasPermissions()) {
                    delay(300)
                    continue
                }
                selectBestBySignal()
            }
            while (isActive) {
                val startedAt = SystemClock.elapsedRealtime()
                scanSignalsOnce()
                // 固定 5 秒一轮：扣除本轮扫描耗时后再等待
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                delay((SIGNAL_SCAN_INTERVAL_MS - elapsed).coerceAtLeast(500L))
            }
        }
    }

    fun stopSignalMonitor() {
        signalScanJob?.cancel()
        signalScanJob = null
        if (!_uiState.value.scanning) ble.stopScan()
    }

    /** 启动时扫 1 秒，从本轮扫到的设备里挑信号最好的设为默认；用户已手动选择过则跳过。 */
    private suspend fun selectBestBySignal() {
        if (userSelected) {
            initialSelectionDone = true
            return
        }
        if (_uiState.value.records.isEmpty()) {
            initialSelectionDone = true
            return
        }
        runSignalScan(INITIAL_SCAN_MS)
        initialSelectionDone = true

        if (userSelected) return
        val state = _uiState.value
        // 已经在连接或已连接（例如定时任务刚拉起）就不要打断
        if (state.phase != Phase.DISCONNECTED) return
        // 只在本轮真正扫到的设备里挑，避免用上次留下的旧信号值
        val best = state.records
            .filter { it.address in scanSeen && it.signal > 0 }
            .maxByOrNull { it.signal }
        val target = best ?: state.selectedRecord ?: state.records.first()

        if (prefs.autoConnect) {
            connectRecord(target.address)
        } else {
            prefs.selectedAddress = target.address
            _uiState.update {
                it.copy(
                    selectedAddress = target.address,
                    savedDevice = ConnectionInfo(target.title, target.address),
                    device = ConnectionInfo(target.deviceName, target.address),
                )
            }
        }
    }

    private suspend fun scanSignalsOnce() {
        val state = _uiState.value
        if (state.records.isEmpty()) return
        if (!ble.hasPermissions()) return
        // 正在连接/登录时先让路；用户自己在扫描时等这一轮结束
        if (state.phase == Phase.CONNECTING || state.phase == Phase.AUTHENTICATING) return
        if (state.scanning) {
            delay(SIGNAL_SCAN_WINDOW_MS)
            return
        }
        runSignalScan(SIGNAL_SCAN_WINDOW_MS)
    }

    /** 打开一轮信号扫描窗口，结束后统一结算信号和显示顺序。 */
    private suspend fun runSignalScan(windowMs: Long) {
        scanSeen.clear()
        pendingRssi.clear()
        trackingScan = true
        try {
            ble.startScan(quiet = true)
            delay(windowMs)
        } finally {
            // 页面切走被取消时也要复位，否则后续扫描结果会一直往这里塞
            trackingScan = false
        }
        // 期间用户可能打开了“添加遥控器”，此时不能把它的扫描停掉
        val scanned = ble.isScanning()
        if (!_uiState.value.scanning) ble.stopScan()
        if (scanned) {
            applyScanResult()
            refreshDisplayOrder()
        }
    }

    /**
     * 用本轮扫到的结果更新信号：连续 [MISSED_ROUNDS_TO_CLEAR] 轮都没扫到的遥控器
     * 视为不在范围内，信号归零排到最后；已连接设备靠 GATT 读 RSSI，不参与清零。
     */
    private fun applyScanResult() {
        val seen = scanSeen.toSet()
        val rssiMap = pendingRssi.toMap()
        pendingRssi.clear()
        val state = _uiState.value
        val connectedAddress = state.device.deviceAddress
            .takeIf { it.isNotBlank() && state.phase == Phase.READY }
        var changed = false
        val updated = state.records.map { record ->
            if (record.address in seen) missedRounds[record.address] = 0
            val fresh = rssiMap[record.address]
            when {
                // 本轮扫到的设备：轮末一次性写入最新信号
                fresh != null && record.address != connectedAddress -> {
                    val next = record.withRssi(fresh)
                    if (next.rssi != record.rssi) changed = true
                    next
                }
                record.address in seen || record.address == connectedAddress -> record
                else -> {
                    val missed = (missedRounds[record.address] ?: 0) + 1
                    missedRounds[record.address] = missed
                    if (missed >= MISSED_ROUNDS_TO_CLEAR && record.rssi != RemoteRecord.RSSI_UNKNOWN) {
                        changed = true
                        record.copy(rssi = RemoteRecord.RSSI_UNKNOWN)
                    } else {
                        record
                    }
                }
            }
        }
        // 删掉的遥控器不再统计
        missedRounds.keys.retainAll(state.records.mapTo(mutableSetOf()) { it.address })
        if (changed) {
            _uiState.update { it.copy(records = updated) }
            prefs.records = updated
        }
    }

    /** 按当前信号值生成一次显示顺序快照；选中项和保存顺序都不变。 */
    private fun refreshDisplayOrder() {
        val state = _uiState.value
        val ordered = state.records
            .sortedByDescending { it.signal }
            .map { it.address }
        if (ordered == state.displayOrder) return
        _uiState.update { it.copy(displayOrder = ordered) }
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
            _uiState.update { it.copy(passwordError = true) }
            message("密码错误，长按顶部卡片可修改登录信息")
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
            it.copy(
                ac = state,
                lastUpdate = System.currentTimeMillis(),
                error = null,
                passwordError = false,
            )
        }
    }

    private fun startPolling() {
        stopPolling()
        pollingJob = scope.launch {
            // 后台期间不发任何命令（每次读状态都会和空调模块通信），
            // 切回前台时先立即补读一次，保证界面显示的仍是最新状态
            var wokeFromBackground = false
            while (isActive) {
                if (!AppState.foreground) {
                    wokeFromBackground = true
                    delay(1_000)
                    continue
                }
                if (!wokeFromBackground) {
                    delay(prefs.pollSeconds * 1000L)
                }
                wokeFromBackground = false
                if (!AppState.foreground) continue
                if (_uiState.value.phase != Phase.READY) continue
                if (!commandMutex.tryLock()) continue
                try {
                    val state = engine.readState()
                    if (state != null) publishState(state)
                    ble.readRssi()
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

    /** 定时任务错过后的补执行宽限期。 */
    const val TIMER_GRACE_MS = 15 * 60_000L

    /** 首页信号扫描：每 5 秒一轮，每轮持续 2 秒；启动时先扫 1 秒选默认设备。 */
    private const val SIGNAL_SCAN_INTERVAL_MS = 5_000L
    private const val SIGNAL_SCAN_WINDOW_MS = 2_000L
    private const val INITIAL_SCAN_MS = 1_000L

    /** 连续多少轮没扫到就认为信号为 0（避免偶发漏扫导致卡片乱跳）。 */
    private const val MISSED_ROUNDS_TO_CLEAR = 2

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
