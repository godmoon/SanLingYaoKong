package com.openremo.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.openremo.protocol.L1Frame
import com.openremo.protocol.L1Framer
import com.openremo.protocol.ScannedDevice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * 三菱电机遥控器 BLE 传输层。
 *
 * 只负责：扫描、连接、服务发现、开通知、分片写、收包重组。
 * 协议会话（L2/L3 Job）由 RemoRepository 处理。
 */
class RemoBleClient(private val context: Context) {

    interface Listener {
        fun onBleReady()
        fun onBleDisconnected(reason: String)
        fun onBleFrame(frame: L1Frame)
        fun onBleLog(tag: String, message: String)
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0277df18-e796-11e6-bf01-fe55135034f3")
        val CHAR_VERSION: UUID = UUID.fromString("799e3b22-e797-11e6-bf01-fe55135034f3")
        val CHAR_PRODUCT_VERSION: UUID = UUID.fromString("def9382a-e795-11e6-bf01-fe55135034f3")
        val CHAR_WRITE: UUID = UUID.fromString("e48c1528-e795-11e6-bf01-fe55135034f3")
        val CHAR_NOTIFY: UUID = UUID.fromString("ea1ea690-e795-11e6-bf01-fe55135034f3")

        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val CHUNK_SIZE = 20

        /**
         * 节流参数参考原 SDK（BleSubLayer1）：分片固定 20 字节，
         * 每 30ms 的定时器最多发 3 片，收到响应后再发下一帧。
         * 三菱这套固件如果被连续猛写，ATT 层会直接卡死（后续写全部超时）。
         */
        private const val CHUNK_GAP_MS = 20L
        private const val FRAME_GAP_MS = 30L

        /** 无响应写入的回调一般几十毫秒内返回，等太久没有意义。 */
        private const val WRITE_CALLBACK_WAIT_MS = 300L

        /** writeCharacteristic 返回 false 说明协议栈还在忙上一笔，稍等重试。 */
        private const val WRITE_START_RETRY = 8
        private const val WRITE_START_RETRY_DELAY_MS = 25L

        private const val CONNECT_TIMEOUT_MS = 15_000L

        private val NAME_PREFIXES = listOf("M/R_", "G/R_", "M/R2")
    }

    var listener: Listener? = null
    var scanListener: ((ScannedDevice) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val framer = L1Framer()
    private val writeChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private var writeLoop: Job? = null

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null

    @Volatile
    private var ready = false
    @Volatile
    private var closing = false
    @Volatile
    private var writeResult: CompletableDeferred<Boolean>? = null

    /** 最近一次 BLE 收/发的时间点（elapsedRealtime），用于帧间节流。 */
    @Volatile
    private var lastBleActivity = 0L

    private var connectTimeout: Job? = null
    private var scanning = false

    private val scanned = LinkedHashMap<String, ScannedDevice>()

    init {
        writeLoop = scope.launch {
            for (frame in writeChannel) {
                if (!ready) continue
                sendChunks(frame)
            }
        }
    }

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    fun isConnected(): Boolean = ready

    @SuppressLint("MissingPermission")
    fun hasPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return has(Manifest.permission.BLUETOOTH_SCAN) && has(Manifest.permission.BLUETOOTH_CONNECT)
        }
        return has(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun startScan() {
        val adapter = adapter ?: run {
            listener?.onBleLog("BLE", "设备不支持蓝牙")
            return
        }
        if (!adapter.isEnabled) {
            listener?.onBleLog("BLE", "蓝牙未打开")
            return
        }
        if (!hasPermissions()) {
            listener?.onBleLog("BLE", "缺少蓝牙扫描权限")
            return
        }
        if (scanning) return
        val scanner = adapter.bluetoothLeScanner ?: return
        scanned.clear()
        scanning = true
        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(null, settings, scanCallback)
            listener?.onBleLog("BLE", "开始扫描附近的三菱电机遥控器")
        } catch (e: SecurityException) {
            scanning = false
            listener?.onBleLog("BLE", "扫描失败：${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = result.scanRecord?.deviceName
                ?: try {
                    device.name
                } catch (_: SecurityException) {
                    null
                }
                ?: ""
            val manufacturer = result.scanRecord?.getManufacturerSpecificData(0x0014)
            val looksLikeRemo = NAME_PREFIXES.any { name.startsWith(it) } ||
                (manufacturer != null && manufacturer.size >= 13)
            if (!looksLikeRemo) return
            val address = device.address ?: return
            val info = ScannedDevice(
                name = if (name.isBlank()) "未知设备" else name,
                address = address,
                rssi = result.rssi,
            )
            val previous = scanned[address]
            if (previous != null && previous.rssi == info.rssi && previous.name == info.name) return
            scanned[address] = info
            scanListener?.invoke(info)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            listener?.onBleLog("BLE", "扫描失败，错误码 $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        if (!hasPermissions()) {
            listener?.onBleDisconnected("缺少蓝牙权限")
            return
        }
        val adapter = adapter
        if (adapter == null || !adapter.isEnabled) {
            listener?.onBleDisconnected("蓝牙未打开")
            return
        }
        stopScan()
        closeGatt()
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            listener?.onBleDisconnected("无效的设备地址")
            return
        }
        closing = false
        ready = false
        framer.reset()
        drainWriteQueue()
        lastBleActivity = 0L
        listener?.onBleLog("BLE", "正在连接 $address")
        gatt = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }
        } catch (e: SecurityException) {
            listener?.onBleDisconnected("连接失败：${e.message}")
            return
        }
        connectTimeout?.cancel()
        connectTimeout = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (!ready && !closing) {
                listener?.onBleLog("BLE", "连接超时")
                disconnect()
                listener?.onBleDisconnected("连接超时")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        closing = true
        connectTimeout?.cancel()
        connectTimeout = null
        ready = false
        failPendingWrite()
        writeChar = null
        notifyChar = null
        drainWriteQueue()
        val current = gatt
        gatt = null
        if (current != null) {
            try {
                current.disconnect()
            } catch (_: Exception) {
            }
            try {
                current.close()
            } catch (_: Exception) {
            }
        }
        framer.reset()
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        ready = false
        failPendingWrite()
        writeChar = null
        notifyChar = null
        val current = gatt
        gatt = null
        if (current != null) {
            try {
                current.disconnect()
            } catch (_: Exception) {
            }
            try {
                current.close()
            } catch (_: Exception) {
            }
        }
    }

    fun write(frame: ByteArray) {
        writeChannel.trySend(frame)
    }

    /** 丢弃还没发出去的帧（断线/重连时调用），避免旧命令写到新连接上。 */
    private fun drainWriteQueue() {
        while (writeChannel.tryReceive().isSuccess) {
            // 直接丢弃
        }
    }

    /**
     * 一帧数据按 20 字节分片写出。
     *
     * 关键点：片与片、帧与帧之间都要留间隔。原 SDK 是 30ms 定时器里最多发 3 片，
     * 实测连续猛写会让这套固件的 ATT 层卡死（第一帧正常、第二帧起写回调永远不来）。
     */
    @SuppressLint("MissingPermission")
    private suspend fun sendChunks(frame: ByteArray) {
        pace(FRAME_GAP_MS)
        var offset = 0
        while (offset < frame.size) {
            if (!ready) return
            val end = minOf(offset + CHUNK_SIZE, frame.size)
            val chunk = frame.copyOfRange(offset, end)
            if (!writeChunk(chunk)) {
                listener?.onBleLog("BLE", "写入失败，放弃当前帧（${frame.size} 字节）")
                return
            }
            offset = end
            if (offset < frame.size) pace(CHUNK_GAP_MS)
        }
    }

    /** 保证两次 BLE 操作之间至少间隔 [gap] 毫秒。 */
    private suspend fun pace(gap: Long) {
        val wait = gap - (SystemClock.elapsedRealtime() - lastBleActivity)
        if (wait > 0) delay(wait)
    }

    /**
     * 分片写入，等 onCharacteristicWrite 回调做流控。
     *
     * 写类型跟原 SDK 一致用 WITHOUT_RESPONSE：这套固件对带响应写入并不总是回 ATT 应答，
     * 用 DEFAULT 时第二帧会一直等不到回调、协议栈卡住，最后 133 断链。
     */
    @SuppressLint("MissingPermission")
    private suspend fun writeChunk(chunk: ByteArray): Boolean {
        val characteristic = writeChar ?: return false
        val result = CompletableDeferred<Boolean>()
        var attempt = 0
        var started = false
        while (!started) {
            if (!ready) return false
            val currentGatt = gatt ?: return false
            writeResult = result
            started = startWrite(currentGatt, characteristic, chunk)
            if (!started) {
                attempt++
                if (attempt >= WRITE_START_RETRY) {
                    writeResult = null
                    listener?.onBleLog("BLE", "写入连续 $attempt 次启动失败，重建链路")
                    // 协议栈已经卡死，干等没有意义，直接断开交给上层重连
                    disconnect()
                    listener?.onBleDisconnected("蓝牙写入卡住")
                    return false
                }
                delay(WRITE_START_RETRY_DELAY_MS)
            }
        }
        lastBleActivity = SystemClock.elapsedRealtime()
        val acked = withTimeoutOrNull(WRITE_CALLBACK_WAIT_MS) { result.await() }
        // 只有自己还是当前等待者时才清理，避免覆盖下一片的 deferred
        if (writeResult === result) writeResult = null
        if (acked == null) {
            // 无响应写入本来就不依赖 ATT 应答，回调偶尔迟到/丢失，继续发下一片
            listener?.onBleLog("BLE", "写入回调超时（${WRITE_CALLBACK_WAIT_MS}ms），继续")
            return true
        }
        return acked
    }

    @SuppressLint("MissingPermission")
    private fun startWrite(
        currentGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        chunk: ByteArray,
    ): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            currentGatt.writeCharacteristic(
                characteristic,
                chunk,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            ) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            characteristic.value = chunk
            @Suppress("DEPRECATION")
            currentGatt.writeCharacteristic(characteristic)
        }
    } catch (e: SecurityException) {
        false
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (gatt !== this@RemoBleClient.gatt) {
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS && newState != BluetoothProfile.STATE_CONNECTED) {
                listener?.onBleLog("BLE", "连接状态异常：status=$status")
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    listener?.onBleLog("BLE", "已连接，正在发现服务")
                    try {
                        gatt.discoverServices()
                    } catch (e: SecurityException) {
                        listener?.onBleDisconnected("服务发现失败")
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    val wasReady = ready
                    ready = false
                    failPendingWrite()
                    writeChar = null
                    notifyChar = null
                    try {
                        gatt.close()
                    } catch (_: Exception) {
                    }
                    if (this@RemoBleClient.gatt === gatt) {
                        this@RemoBleClient.gatt = null
                    }
                    if (!closing) {
                        listener?.onBleLog("BLE", "连接已断开（status=$status）")
                        listener?.onBleDisconnected(
                            if (wasReady) "设备断开连接" else "无法连接设备（$status）"
                        )
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt !== this@RemoBleClient.gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener?.onBleDisconnected("服务发现失败（$status）")
                return
            }
            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                listener?.onBleDisconnected("未找到三菱电机服务，可能不是兼容的遥控器")
                return
            }
            writeChar = service.getCharacteristic(CHAR_WRITE)
            notifyChar = service.getCharacteristic(CHAR_NOTIFY)
            if (writeChar == null || notifyChar == null) {
                listener?.onBleDisconnected("设备缺少必要特征")
                return
            }
            val notify = notifyChar!!
            try {
                gatt.setCharacteristicNotification(notify, true)
            } catch (e: SecurityException) {
                listener?.onBleDisconnected("无法启用通知")
                return
            }
            val descriptor = notify.getDescriptor(CCCD_UUID)
            if (descriptor != null) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(
                            descriptor,
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(descriptor)
                    }
                } catch (e: SecurityException) {
                    listener?.onBleDisconnected("无法配置通知")
                }
            } else {
                markReady()
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                markReady()
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (characteristic.uuid != CHAR_WRITE) return
            val result = writeResult
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener?.onBleLog("BLE", "写入回调失败：status=$status")
            }
            result?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid != CHAR_NOTIFY) return
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            handleNotify(value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid != CHAR_NOTIFY) return
            handleNotify(value)
        }
    }

    /** 链路断开时让正在等待回调的写入立即失败，避免白等 2 秒。 */
    private fun failPendingWrite() {
        writeResult?.complete(false)
        writeResult = null
    }

    private fun markReady() {
        if (ready) return
        ready = true
        connectTimeout?.cancel()
        connectTimeout = null
        listener?.onBleLog("BLE", "通知已开启，链路就绪")
        listener?.onBleReady()
    }

    private fun handleNotify(value: ByteArray) {
        lastBleActivity = SystemClock.elapsedRealtime()
        val frames = framer.feed(value)
        if (frames.isEmpty()) return
        mainHandler.post {
            frames.forEach { listener?.onBleFrame(it) }
        }
    }

    fun release() {
        disconnect()
        writeChannel.close()
        writeLoop?.cancel()
    }
}
