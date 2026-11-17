package com.openremo.debug

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.openremo.data.RemoRepository
import com.openremo.protocol.AcState
import com.openremo.protocol.DeviceCapabilities
import com.openremo.protocol.FanSpeed
import com.openremo.protocol.Louver
import com.openremo.protocol.Mode
import com.openremo.protocol.MoveEye
import com.openremo.protocol.OpLock
import com.openremo.protocol.TempRange
import com.openremo.protocol.Vane
import com.openremo.protocol.Vent
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * 仅 Debug 构建启用的本地命令桥，方便用 adb / curl 直接驱动 App。
 *
 * 用法：
 *   adb forward tcp:18080 tcp:18080
 *   curl -s http://127.0.0.1:18080/state
 *   curl -s "http://127.0.0.1:18080/control?action=temp&value=24.5"
 *
 * 服务只监听 127.0.0.1，不对外网暴露；release 包不会启动。
 */
object DebugBridge {

    private const val TAG = "OpenRemoBridge"
    private const val PORT = 18080

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        Thread({
            try {
                val server = ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1"))
                Log.i(TAG, "debug bridge listening: http://127.0.0.1:$PORT")
                while (true) {
                    val socket = try {
                        server.accept()
                    } catch (e: Exception) {
                        Log.w(TAG, "accept stopped: ${e.message}")
                        break
                    }
                    try {
                        serve(socket)
                    } catch (e: Exception) {
                        Log.w(TAG, "request failed: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "debug bridge failed to start", e)
            }
        }, "openremo-debug-bridge").apply {
            isDaemon = true
            start()
        }
    }

    // ---------------------------------------------------------------- HTTP

    private fun serve(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 5_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            // 读完请求头，避免客户端侧连接被 reset
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }

            val target = requestLine.split(' ').getOrNull(1).orEmpty()
            val split = target.indexOf('?')
            val path = (if (split >= 0) target.substring(0, split) else target).trimEnd('/')
            val query = parseQuery(if (split >= 0) target.substring(split + 1) else "")

            val response = try {
                handle(path.ifEmpty { "/" }, query)
            } catch (e: Exception) {
                JSONObject()
                    .put("ok", false)
                    .put("error", e.message ?: e.javaClass.simpleName)
            }

            val bytes = response.toString().toByteArray(Charsets.UTF_8)
            val header = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: application/json; charset=utf-8\r\n")
                append("Content-Length: ${bytes.size}\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            client.getOutputStream().apply {
                write(header.toByteArray(Charsets.UTF_8))
                write(bytes)
                flush()
            }
        }
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in raw.split('&')) {
            if (pair.isBlank()) continue
            val i = pair.indexOf('=')
            val key = if (i >= 0) pair.substring(0, i) else pair
            val value = if (i >= 0) pair.substring(i + 1) else ""
            out[decode(key)] = decode(value)
        }
        return out
    }

    private fun decode(value: String): String =
        try {
            URLDecoder.decode(value, "UTF-8")
        } catch (e: Exception) {
            value
        }

    // ---------------------------------------------------------------- 路由

    private fun handle(path: String, q: Map<String, String>): JSONObject = when (path) {
        "/", "/help" -> help()
        "/ping" -> JSONObject().put("ok", true).put("port", PORT).put("time", System.currentTimeMillis())
        "/state" -> JSONObject().put("ok", true).put("state", stateJson())
        "/logs" -> logs(q)
        "/scan/start", "/scan" -> scanStart()
        "/scan/stop" -> scanStop()
        "/connect" -> connect(q)
        "/disconnect" -> onMain {
            RemoRepository.disconnect()
        }.let { ok("disconnect") }
        "/forget" -> onMain {
            RemoRepository.forgetDevice()
        }.let { ok("forget") }
        "/refresh" -> onMain {
            RemoRepository.refresh()
        }.let { ok("refresh") }
        "/control" -> control(q)
        "/settings" -> settings(q)
        "/credentials" -> credentials(q)
        "/wait" -> waitIdle(q)
        else -> JSONObject().put("ok", false).put("error", "未知命令：$path")
    }

    private fun help(): JSONObject {
        val endpoints = JSONArray()
        listOf(
            "GET /state",
            "GET /logs?limit=100&tag=&grep=",
            "GET /scan/start",
            "GET /scan/stop",
            "GET /connect?address=40:xx:xx:xx:xx:xx&name=40xxxx&guest=0&password=1752",
            "GET /connect（使用已保存设备）",
            "GET /disconnect",
            "GET /forget",
            "GET /refresh",
            "GET /control?action=power&value=on|off",
            "GET /control?action=mode&value=cool|heat|dry|fan|auto",
            "GET /control?action=temp&value=24.5 或 &delta=0.5",
            "GET /control?action=fan&value=auto|silent|low|middle|high|high_power|rapid",
            "GET /control?action=vane&value=auto|one|two|three|four|five|swing",
            "GET /control?action=louver&value=on|off",
            "GET /control?action=rightleft&value=0..6",
            "GET /control?action=moveeye&value=off|direct|indirect|area|auto|ateyoke",
            "GET /control?action=hold&value=on|off",
            "GET /control?action=innerclean&value=on|off",
            "GET /control?action=vent&value=off|low|high|cooperation_low|cooperation_high|move_eye_low|move_eye_high|move_eye_off",
            "GET /settings?poll=5&autoReconnect=1&autoConnect=0",
            "GET /credentials?guest=0&password=1752",
            "GET /wait?timeoutMs=15000",
        ).forEach { endpoints.put(it) }
        return JSONObject()
            .put("ok", true)
            .put("port", PORT)
            .put("endpoints", endpoints)
    }

    // ---------------------------------------------------------------- 状态

    private fun stateJson(): JSONObject {
        val ui = RemoRepository.uiState.value
        val devices = JSONArray()
        ui.scanDevices.forEach { device ->
            devices.put(
                JSONObject()
                    .put("name", device.name)
                    .put("address", device.address)
                    .put("rssi", device.rssi)
            )
        }
        return JSONObject()
            .put("phase", ui.phase.name.lowercase())
            .put("connected", ui.connected)
            .put("busy", ui.busy)
            .put("error", ui.error ?: JSONObject.NULL)
            .put("scanning", ui.scanning)
            .put("lastUpdate", ui.lastUpdate)
            .put("pollSeconds", ui.pollSeconds)
            .put("autoReconnect", ui.autoReconnect)
            .put("autoConnect", ui.autoConnect)
            .put("guestMode", ui.guestMode)
            .put("password", ui.password)
            .put(
                "device",
                JSONObject()
                    .put("name", ui.device.deviceName)
                    .put("address", ui.device.deviceAddress)
            )
            .put(
                "savedDevice",
                JSONObject()
                    .put("name", ui.savedDevice.deviceName)
                    .put("address", ui.savedDevice.deviceAddress)
            )
            .put("scanDevices", devices)
            .put("capabilities", capabilitiesJson(ui.capabilities))
            .put("ac", acJson(ui.ac))
    }

    private fun capabilitiesJson(cap: DeviceCapabilities): JSONObject = JSONObject()
        .put("known", cap.known)
        .put("modes", JSONObject()
            .put("cool", cap.coolMode)
            .put("heat", cap.heatMode)
            .put("dry", cap.dryMode)
            .put("fan", cap.fanMode)
            .put("auto", cap.autoMode)
            .put("setback", cap.setback)
            .put("burningHeat", cap.burningHeat))
        .put("vaneControl", cap.vaneControl)
        .put("supportsVane", cap.supportsVane)
        .put("supportsVaneSwing", cap.supportsVaneSwing)
        .put("louver", cap.louver)
        .put("hold", cap.holdEnabled)
        .put("rightLeftControl", cap.rightLeftControl)
        .put("moveEye", cap.moveEye)
        .put("lossnay", cap.lossnay)
        .put("innerClean", cap.innerClean)
        .put("outdoorSilent", cap.outdoorSilent)
        .put("fanSteps", cap.fanSteps)
        .put("fanAuto", cap.fanAuto)
        .putNullable("minTempRange", cap.minTempRange)
        .put("celsius", cap.celsius)
        .putNullable("coolRange", cap.coolRange?.let { rangeJson(it) })
        .putNullable("heatRange", cap.heatRange?.let { rangeJson(it) })
        .putNullable("autoRange", cap.autoRange?.let { rangeJson(it) })
        .put("electricity", cap.electricity)
        .put("ai", cap.ai)
        .put("ateyoke", cap.ateyoke)
        .put("bleSensor", cap.bleSensor)
        .put("ventIfType", cap.ventIfType)

    private fun acJson(ac: AcState): JSONObject = JSONObject()
        .put("power", ac.power)
        .put("mode", ac.mode.name.lowercase())
        .put("modeWire", ac.mode.wire)
        .put("modeLabel", ac.mode.label)
        .putNullable("activeTemp", ac.activeTemp)
        .put("activeRange", rangeJson(ac.activeRange))
        .put("coolRange", rangeJson(ac.coolRange))
        .put("heatRange", rangeJson(ac.heatRange))
        .put("autoRange", rangeJson(ac.autoRange))
        .put("setbackUpperRange", rangeJson(ac.setbackUpperRange))
        .put("setbackLowerRange", rangeJson(ac.setbackLowerRange))
        .putNullable("coolTemp", ac.coolTemp)
        .putNullable("heatTemp", ac.heatTemp)
        .putNullable("autoTemp", ac.autoTemp)
        .putNullable("setbackUpperTemp", ac.setbackUpperTemp)
        .putNullable("setbackLowerTemp", ac.setbackLowerTemp)
        .put("fanSpeed", ac.fanSpeed.name.lowercase())
        .put("fanLabel", ac.fanSpeed.label)
        .put("vane", ac.vane.name.lowercase())
        .put("vaneLabel", ac.vane.label)
        .put("louver", ac.louver.name.lowercase())
        .put("vent", ac.vent.name.lowercase())
        .put("ventLabel", ac.vent.label)
        .put("moveEye", ac.moveEye.name.lowercase())
        .put("moveEyeLabel", ac.moveEye.label)
        .put("hold", ac.hold)
        .put("innerClean", ac.innerClean)
        .put("rightLeft", ac.rightLeft)
        .putNullable("roomTemp", ac.roomTemp)
        .put("rclock", ac.rclock)
        .put("modeLimits", modeLimitsJson(ac.modeLimits))
        .put("opLock", opLockJson(ac.opLock))

    private fun rangeJson(range: TempRange): JSONObject =
        JSONObject().put("lower", range.lower).put("upper", range.upper)

    private fun modeLimitsJson(limits: com.openremo.protocol.ModeLimits): JSONObject =
        JSONObject()
            .put("cool", limits.cool)
            .put("heat", limits.heat)
            .put("dry", limits.dry)
            .put("auto", limits.auto)

    private fun opLockJson(lock: OpLock): JSONObject =
        JSONObject()
            .put("power", lock.power)
            .put("mode", lock.mode)
            .put("setTemp", lock.setTemp)
            .put("vane", lock.vane)
            .put("menu", lock.menu)
            .put("hold", lock.hold)
            .put("fanSpeed", lock.fanSpeed)
            .put("louver", lock.louver)

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject =
        if (value == null) put(key, JSONObject.NULL) else put(key, value)

    // ---------------------------------------------------------------- 命令

    private fun logs(q: Map<String, String>): JSONObject {
        val limit = q["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
        val tag = q["tag"]?.takeIf { it.isNotBlank() }
        val grep = q["grep"]?.takeIf { it.isNotBlank() }
        val entries = RemoRepository.logs.value
            .filter { tag == null || it.tag.equals(tag, ignoreCase = true) }
            .filter { grep == null || it.message.contains(grep, ignoreCase = true) }
            .takeLast(limit)
        val array = JSONArray()
        entries.forEach {
            array.put(
                JSONObject()
                    .put("time", it.time)
                    .put("timeText", RemoRepository.formatTime(it.time))
                    .put("tag", it.tag)
                    .put("message", it.message)
            )
        }
        return JSONObject().put("ok", true).put("count", entries.size).put("logs", array)
    }

    private fun scanStart(): JSONObject {
        onMain { RemoRepository.startScan() }
        return ok("scan.start")
    }

    private fun scanStop(): JSONObject {
        onMain { RemoRepository.stopScan() }
        return ok("scan.stop")
    }

    private fun connect(q: Map<String, String>): JSONObject {
        val guest = q["guest"]?.toBooleanFlag() ?: RemoRepository.uiState.value.guestMode
        val password = q["password"] ?: RemoRepository.uiState.value.password
        val address = q["address"]
        val name = q["name"] ?: ""
        onMain {
            if (address.isNullOrBlank()) {
                RemoRepository.connectSaved()
            } else {
                RemoRepository.connect(address, name, guest, password)
            }
        }
        return JSONObject()
            .put("ok", true)
            .put("accepted", true)
            .put("guest", guest)
            .put("address", address ?: RemoRepository.uiState.value.savedDevice.deviceAddress)
    }

    private fun control(q: Map<String, String>): JSONObject {
        val action = q["action"]?.lowercase()
            ?: return JSONObject().put("ok", false).put("error", "缺少 action")
        val rawValue = q["value"]
        val rawDelta = q["delta"]

        when (action) {
            "power" -> {
                val on = parseOnOff(rawValue) ?: return badValue(rawValue, "on|off")
                onMain { RemoRepository.setPower(on) }
            }

            "mode" -> {
                val mode = parseMode(rawValue) ?: return badValue(rawValue, "cool|heat|dry|fan|auto")
                onMain { RemoRepository.setMode(mode) }
            }

            "temp" -> {
                val delta = rawDelta?.toDoubleOrNull()
                val value = rawValue?.toDoubleOrNull()
                when {
                    delta != null -> onMain { RemoRepository.adjustTemp(delta) }
                    value != null -> onMain { RemoRepository.setTemp(value) }
                    else -> return badValue(rawValue, "value 或 delta")
                }
            }

            "fan" -> {
                val fan = parseFan(rawValue) ?: return badValue(rawValue, "auto|silent|low|middle|high|high_power|rapid")
                onMain { RemoRepository.setFanSpeed(fan) }
            }

            "vane" -> {
                val vane = parseVane(rawValue) ?: return badValue(rawValue, "auto|one|two|three|four|five|swing")
                onMain { RemoRepository.setVane(vane) }
            }

            "louver" -> {
                val on = parseOnOff(rawValue) ?: return badValue(rawValue, "on|off")
                onMain { RemoRepository.setLouver(if (on) Louver.ON else Louver.OFF) }
            }

            "rightleft" -> {
                val value = rawValue?.toIntOrNull()
                    ?: return badValue(rawValue, "0..6")
                if (value !in 0..6) return badValue(rawValue, "0..6")
                onMain { RemoRepository.setRightLeft(value) }
            }

            "moveeye" -> {
                val eye = parseMoveEye(rawValue) ?: return badValue(rawValue, "off|direct|indirect|area|auto|ateyoke")
                onMain { RemoRepository.setMoveEye(eye) }
            }

            "hold" -> {
                val on = parseOnOff(rawValue) ?: return badValue(rawValue, "on|off")
                onMain { RemoRepository.setHold(on) }
            }

            "innerclean" -> {
                val on = parseOnOff(rawValue) ?: return badValue(rawValue, "on|off")
                onMain { RemoRepository.setInnerClean(on) }
            }

            "vent" -> {
                val vent = parseVent(rawValue) ?: return badValue(
                    rawValue,
                    "off|low|high|cooperation_low|cooperation_high|move_eye_low|move_eye_high|move_eye_off",
                )
                onMain { RemoRepository.setVent(vent) }
            }

            else -> return JSONObject().put("ok", false).put("error", "未知 action：$action")
        }

        return JSONObject()
            .put("ok", true)
            .put("accepted", true)
            .put("action", action)
            .put("state", stateJson())
    }

    private fun settings(q: Map<String, String>): JSONObject {
        onMain {
            q["poll"]?.toIntOrNull()?.let { RemoRepository.setPollSeconds(it.coerceIn(2, 30)) }
            q["autoReconnect"]?.toBooleanFlag()?.let { RemoRepository.setAutoReconnect(it) }
            q["autoConnect"]?.toBooleanFlag()?.let { RemoRepository.setAutoConnect(it) }
        }
        return ok("settings")
    }

    private fun credentials(q: Map<String, String>): JSONObject {
        val guest = q["guest"]?.toBooleanFlag() ?: RemoRepository.uiState.value.guestMode
        val password = q["password"] ?: RemoRepository.uiState.value.password
        onMain { RemoRepository.saveCredentials(guest, password) }
        return JSONObject().put("ok", true).put("guest", guest).put("password", password)
    }

    /**
     * 等到设备空闲（非 busy、非连接中）再返回，方便脚本串行执行命令。
     */
    private fun waitIdle(q: Map<String, String>): JSONObject {
        val timeoutMs = (q["timeoutMs"]?.toLongOrNull() ?: 15_000L).coerceIn(500L, 60_000L)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val ui = RemoRepository.uiState.value
            val transitional = ui.phase == RemoRepository.Phase.CONNECTING ||
                ui.phase == RemoRepository.Phase.AUTHENTICATING ||
                ui.phase == RemoRepository.Phase.RECONNECTING
            if (!ui.busy && !transitional && !ui.scanning) break
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        val ui = RemoRepository.uiState.value
        return JSONObject()
            .put("ok", true)
            .put("idle", !ui.busy && !ui.scanning)
            .put("state", stateJson())
    }

    private fun ok(name: String): JSONObject =
        JSONObject().put("ok", true).put("accepted", true).put("command", name)

    private fun badValue(value: String?, expected: String): JSONObject =
        JSONObject()
            .put("ok", false)
            .put("error", "参数不正确：$value，期望 $expected")

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    // ---------------------------------------------------------------- 参数解析

    private fun parseOnOff(value: String?): Boolean? = when (value?.trim()?.lowercase()) {
        null, "toggle" -> null
        "1", "on", "true", "开", "打开" -> true
        "0", "off", "false", "关", "关闭" -> false
        else -> null
    }

    private fun String.toBooleanFlag(): Boolean =
        this == "1" || equals("true", true) || equals("on", true) || equals("yes", true)

    private fun parseMode(value: String?): Mode? {
        val v = value?.trim()?.lowercase() ?: return null
        return Mode.entries.firstOrNull {
            it.name.lowercase() == v || it.label == value || it.wire.toString() == v
        }
    }

    private fun parseFan(value: String?): FanSpeed? {
        val v = value?.trim()?.lowercase() ?: return null
        return FanSpeed.entries.firstOrNull {
            it.name.lowercase() == v || it.label == value || it.wire.toString() == v
        }
    }

    private fun parseVane(value: String?): Vane? {
        val v = value?.trim()?.lowercase() ?: return null
        return Vane.entries.firstOrNull {
            it.name.lowercase() == v || it.label == value || it.wire.toString() == v
        }
    }

    private fun parseMoveEye(value: String?): MoveEye? {
        val v = value?.trim()?.lowercase() ?: return null
        return MoveEye.entries.firstOrNull {
            it.name.lowercase() == v || it.label == value || it.requestValue.toString() == v
        }
    }

    private fun parseVent(value: String?): Vent? {
        val v = value?.trim()?.lowercase() ?: return null
        return Vent.entries.firstOrNull {
            it.name.lowercase() == v || it.label == value || it.requestValue.toString() == v
        }
    }
}
