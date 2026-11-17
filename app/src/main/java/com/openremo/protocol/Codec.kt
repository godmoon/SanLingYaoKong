package com.openremo.protocol

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 这套协议的位域有一个容易踩坑的地方：
 * 原实现先按“字段声明顺序累加，达到 8 bit 就分组”，然后把每组字段顺序反转，
 * 再按从 MSB 到 LSB 的顺序写入。净效果是：先声明的字段在字节里靠低位，
 * 而多字节数值字段是“低字节在前”的组顺序、组内大端。
 *
 * 下面是按字节显式实现的编解码，避免运行期反射，也方便调试。
 */
object TempCodec {

    fun encode(value: Double?): ByteArray {
        if (value == null) return byteArrayOf(0xFF.toByte(), 0xFF.toByte())
        val negative = value < 0
        val scaled = (abs(value) * 10).roundToInt()
        val tenths = scaled % 10
        val ones = (scaled / 10) % 10
        val tens = (scaled / 100) % 10
        val hundreds = scaled / 1000
        val b0 = ((ones shl 4) or tenths) and 0xFF
        val b1 = ((if (negative) 1 else 0) shl 7) or ((hundreds and 0x07) shl 4) or (tens and 0x0F)
        return byteArrayOf(b0.toByte(), b1.toByte())
    }

    fun decode(bytes: ByteArray, offset: Int): Double? {
        if (offset + 1 >= bytes.size) return null
        val b0 = bytes[offset].toInt() and 0xFF
        val b1 = bytes[offset + 1].toInt() and 0xFF
        if (b0 == 0xFF && b1 == 0xFF) return null
        val tenths = b0 and 0x0F
        val ones = (b0 ushr 4) and 0x0F
        val tens = b1 and 0x0F
        val hundreds = (b1 ushr 4) and 0x07
        val negative = (b1 and 0x80) != 0
        val value = hundreds * 100.0 + tens * 10.0 + ones + tenths / 10.0
        return if (negative) -value else value
    }
}

object LoginCodec {

    /*
     * 登录块（随每个 L2 会话的开始/结束帧发送）：
     *   byte0 = L2 会话 ID（0=任务开始，1=任务结束，3/4/5...=数据会话）
     *   byte1 = 用户类型（0=访客，1=普通用户，2=管理员/服务）
     *   后续为用户密码、license 类型、license 密码。
     *
     * 注意：会话 ID 不是固定值，必须与实际 L2 会话一致，否则设备会拒绝登录。
     */

    /** 普通用户：user_type=1, 用户密码, license_type=0 */
    fun user(sessionId: Int, password: String): ByteArray {
        val pass = normalizeHex(password)
        return build(sessionId, 1, pass, 0, "0000")
    }

    /** 访客：user_type=0, 用户密码 AAAA, license 0000 */
    fun guest(sessionId: Int): ByteArray = build(sessionId, 0, "AAAA", 0, "0000")

    private fun normalizeHex(password: String): String {
        val clean = password.trim().uppercase(Locale.US)
        require(isValidPassword(clean)) {
            "密码必须是 4 位十六进制字符"
        }
        return clean
    }

    /** 4 位十六进制密码（0-9、A-F），用于在连接前做友好校验。 */
    fun isValidPassword(password: String): Boolean {
        val clean = password.trim().uppercase(Locale.US)
        return clean.length == 4 && clean.all { it in '0'..'9' || it in 'A'..'F' }
    }

    private fun build(
        sessionId: Int,
        userType: Int,
        userPassword: String,
        licenseType: Int,
        licensePassword: String,
    ): ByteArray {
        val u = userPassword.padEnd(4, '0')
        val l = licensePassword.padEnd(4, '0')
        return byteArrayOf(
            (sessionId and 0x3F).toByte(),
            (userType and 0xFF).toByte(),
            (((hex(u[2]) shl 4) or hex(u[3])) and 0xFF).toByte(),
            (((hex(u[0]) shl 4) or hex(u[1])) and 0xFF).toByte(),
            (licenseType and 0xFF).toByte(),
            (((hex(l[2]) shl 4) or hex(l[3])) and 0xFF).toByte(),
            (((hex(l[0]) shl 4) or hex(l[1])) and 0xFF).toByte(),
        )
    }

    private fun hex(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'A'..'F' -> c - 'A' + 10
        else -> 0
    }
}

data class L1Frame(
    val seq: Int,
    val direction: Int,
    val payload: ByteArray,
    val raw: ByteArray = ByteArray(0),
)

object L1Protocol {

    private const val MAX_PAYLOAD = 2048

    /**
     * @param l2Pdu L2 头 + L2 负载（不含 L1 头、长度和校验）
     */
    fun build(seq: Int, l2Pdu: ByteArray): ByteArray {
        val payload = ByteArray(1 + l2Pdu.size)
        payload[0] = ((seq and 0x07)).toByte() // direction = 0（请求）
        System.arraycopy(l2Pdu, 0, payload, 1, l2Pdu.size)

        val len = payload.size + 2
        val out = ByteArray(2 + len)
        out[0] = (len and 0xFF).toByte()
        out[1] = ((len ushr 8) and 0xFF).toByte()
        System.arraycopy(payload, 0, out, 2, payload.size)
        val checksum = (len and 0xFF) + ((len ushr 8) and 0xFF) + payload.sumOf { it.toInt() and 0xFF }
        out[out.size - 2] = (checksum and 0xFF).toByte()
        out[out.size - 1] = ((checksum ushr 8) and 0xFF).toByte()
        return out
    }

    fun parse(frame: ByteArray): L1Frame? {
        if (frame.size < 5) return null
        val len = le16(frame, 0)
        if (len < 3 || len > MAX_PAYLOAD) return null
        if (frame.size != 2 + len) return null
        val body = frame.copyOfRange(2, 2 + len - 2)
        val expected = (frame[frame.size - 2].toInt() and 0xFF) or
            ((frame[frame.size - 1].toInt() and 0xFF) shl 8)
        val actual = (len and 0xFF) + ((len ushr 8) and 0xFF) + body.sumOf { it.toInt() and 0xFF }
        if (expected != actual || body.isEmpty()) return null
        val header = body[0].toInt() and 0xFF
        return L1Frame(
            seq = header and 0x07,
            direction = (header ushr 3) and 0x01,
            payload = body.copyOfRange(1, body.size),
            raw = frame,
        )
    }

    fun le16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    fun be16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
}

/**
 * 处理 BLE 通知的分包 / 粘包。
 */
class L1Framer {

    private var buffer = ByteArray(0)

    @Synchronized
    fun feed(chunk: ByteArray): List<L1Frame> {
        if (chunk.isEmpty()) return emptyList()
        val merged = ByteArray(buffer.size + chunk.size)
        System.arraycopy(buffer, 0, merged, 0, buffer.size)
        System.arraycopy(chunk, 0, merged, buffer.size, chunk.size)
        buffer = merged

        val frames = ArrayList<L1Frame>()
        while (buffer.size >= 2) {
            val len = L1Protocol.le16(buffer, 0)
            if (len < 3 || len > 2048) {
                // 长度不可信，丢一个字节重新同步
                buffer = buffer.copyOfRange(1, buffer.size)
                continue
            }
            val total = 2 + len
            if (buffer.size < total) break
            val raw = buffer.copyOfRange(0, total)
            buffer = buffer.copyOfRange(total, buffer.size)
            val frame = L1Protocol.parse(raw) ?: continue
            if (frame.direction == 1) {
                frames.add(frame)
            }
        }
        return frames
    }

    @Synchronized
    fun reset() {
        buffer = ByteArray(0)
    }
}

object L2Protocol {

    const val PHASE_START = 0
    const val PHASE_END = 1
    const val PHASE_DATA = 2

    @Suppress("UNUSED_PARAMETER")
    fun build(phase: Int, sessionId: Int, login: ByteArray?, l3: ByteArray? = null): ByteArray {
        val header = (1 or (phase shl 1)).toByte() // first_snd_flg = 1
        val extra = (login?.size ?: 0) + (l3?.size ?: 0)
        val out = ByteArray(1 + extra)
        out[0] = header
        var offset = 1
        if (login != null) {
            System.arraycopy(login, 0, out, offset, login.size)
            offset += login.size
        }
        if (l3 != null) {
            System.arraycopy(l3, 0, out, offset, l3.size)
        }
        return out
    }

    fun buildStart(sessionId: Int, login: ByteArray): ByteArray = build(PHASE_START, sessionId, login)

    fun buildEnd(sessionId: Int, login: ByteArray): ByteArray = build(PHASE_END, sessionId, login)

    fun buildData(l3: ByteArray): ByteArray = build(PHASE_DATA, 0, null, l3)
}

data class L2Reply(
    val l2Code: Int,
    val body: ByteArray,
)

object L3Protocol {

    const val CTRL_READ = 0L
    const val CTRL_WRITE = 1L
    const val CTRL_STATUS = 2L

    fun build(ctrl: Long, func: Long, payload: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArray(2 + payload.size)
        out[0] = (ctrl and 0x07).toByte()
        out[1] = (func and 0xFF).toByte()
        System.arraycopy(payload, 0, out, 2, payload.size)
        return out
    }

    fun parse(bytes: ByteArray): L3Reply? {
        if (bytes.size < 4) return null
        return L3Reply(
            ctrl = (bytes[0].toInt() and 0x07).toLong(),
            func = (bytes[1].toInt() and 0xFF).toLong(),
            code = L1Protocol.be16(bytes, 2),
            payload = if (bytes.size > 4) bytes.copyOfRange(4, bytes.size) else ByteArray(0),
        )
    }
}

data class L3Reply(
    val ctrl: Long,
    val func: Long,
    val code: Int,
    val payload: ByteArray,
) {
    val success: Boolean get() = code == 0
}

object L2ReplyParser {

    /** L2 响应：header(1) + respCode(1) + L3 响应（start/end 时是 4 字节，data 时完整） */
    fun parse(payload: ByteArray): L2Reply? {
        if (payload.size < 2) return null
        return L2Reply(
            l2Code = payload[1].toInt() and 0xFF,
            body = if (payload.size > 2) payload.copyOfRange(2, payload.size) else ByteArray(0),
        )
    }
}

object ControlPayload {

    fun build(state: AcState, flags: ControlFlags, vaneControl: Int = 0): ByteArray {
        val out = ByteArray(17)
        out[0] = (bit(flags.power, 0) or bit(flags.mode, 1)).toByte()
        out[1] = (
            bit(flags.coolTemp, 0) or bit(flags.heatTemp, 1) or bit(flags.autoTemp, 2) or
                bit(flags.setbackUpperTemp, 3) or bit(flags.setbackLowerTemp, 4)
            ).toByte()
        // 位域组内的字段顺序会整体反转：先声明的字段落在低位。
        out[2] = (
            bit(flags.fanSpeed, 0) or bit(flags.vane, 1) or bit(flags.louver, 2) or
                bit(flags.vent, 3) or bit(flags.hold, 4) or bit(flags.rightLeft, 5) or
                bit(flags.moveEye, 6) or bit(flags.innerClean, 7)
            ).toByte()
        out[3] = (((state.mode.requestValue and 0x1F) shl 3) or (if (state.power) 1 else 0)).toByte()

        val temps = listOf(
            state.coolTemp,
            state.heatTemp,
            state.autoTemp,
            state.setbackUpperTemp,
            state.setbackLowerTemp,
        )
        temps.forEachIndexed { index, value ->
            val encoded = TempCodec.encode(value)
            out[4 + index * 2] = encoded[0]
            out[5 + index * 2] = encoded[1]
        }

        // 4 档风向上/下类型的设备，协议值整体比 5 档机型前移一档：
        // 原 SDK（b/C.java）在发送前把 TWO→THREE、THREE→FOUR、FOUR→FIVE。
        val vaneWire = if (vaneControl == 1 || vaneControl == 2 || vaneControl == 5 || vaneControl == 6) {
            when (state.vane) {
                Vane.TWO -> Vane.THREE.wire
                Vane.THREE -> Vane.FOUR.wire
                Vane.FOUR -> Vane.FIVE.wire
                else -> state.vane.wire
            }
        } else {
            state.vane.wire
        }
        out[14] = (((vaneWire and 0x0F) shl 4) or (state.fanSpeed.wire and 0x0F)).toByte()
        out[15] = (((state.vent.requestValue and 0x0F) shl 4) or (state.louver.wire and 0x0F)).toByte()
        out[16] = (
            bit(state.hold, 0) or ((state.rightLeft and 0x07) shl 1) or
                ((state.moveEye.requestValue and 0x07) shl 4) or bit(state.innerClean, 7)
            ).toByte()
        return out
    }

    private fun bit(value: Boolean, index: Int): Int = if (value) 1 shl index else 0
}

object StatusReport {

    /**
     * 解析状态回报（o/a）。基础字段需要 30 字节，房间温度需要 40 字节。
     * 设备通常会回 44 字节。
     */
    fun parse(bytes: ByteArray): AcState? {
        if (bytes.size < 30) return null
        val b = ByteArray(44)
        System.arraycopy(bytes, 0, b, 0, minOf(bytes.size, b.size))

        val b0 = b[0].toInt() and 0xFF
        val power = ((b0 ushr 1) and 0x03) == 1
        val mode = Mode.fromWire((b0 ushr 3) and 0x1F) ?: Mode.COOL

        fun range(index: Int): TempRange {
            val base = 1 + index * 4
            val upper = TempCodec.decode(b, base) ?: 30.0
            val lower = TempCodec.decode(b, base + 2) ?: 18.0
            return TempRange(lower, upper)
        }

        fun temp(index: Int): Double? = TempCodec.decode(b, 21 + index * 2)

        val fan = FanSpeed.fromWire((b[31].toInt() ushr 4) and 0x0F) ?: FanSpeed.AUTO
        val louver = Louver.fromWire(b[31].toInt() and 0x0F)
        val vent = Vent.fromStatus((b[32].toInt() ushr 4) and 0x0F) ?: Vent.OFF
        val vane = Vane.fromWire(b[32].toInt() and 0x0F) ?: Vane.AUTO

        val op = b[33].toInt() and 0xFF
        val limitsByte = b[34].toInt() and 0xFF
        val rclock = (b[35].toInt() and 0x01) != 0
        val s36 = b[36].toInt() and 0xFF
        val s37 = b[37].toInt() and 0xFF
        val roomTemp = TempCodec.decode(b, 38)

        return AcState(
            power = power,
            mode = mode,
            coolRange = range(0),
            heatRange = range(1),
            autoRange = range(2),
            setbackUpperRange = range(3),
            setbackLowerRange = range(4),
            coolTemp = temp(0),
            heatTemp = temp(1),
            autoTemp = temp(2),
            setbackUpperTemp = temp(3),
            setbackLowerTemp = temp(4),
            fanSpeed = fan,
            vane = vane,
            louver = louver,
            vent = vent,
            moveEye = MoveEye.fromStatus((s37 ushr 3) and 0x07) ?: MoveEye.OFF,
            hold = (s37 and 0x01) != 0,
            innerClean = (s37 and 0x80) != 0,
            rightLeft = (s36 ushr 4) and 0x07,
            roomTemp = roomTemp,
            modeLimits = ModeLimits(
                cool = (limitsByte and 0x01) != 0,
                heat = (limitsByte and 0x02) != 0,
                dry = (limitsByte and 0x04) != 0,
                auto = (limitsByte and 0x08) != 0,
            ),
            opLock = OpLock(
                power = (op and 0x01) != 0,
                mode = (op and 0x02) != 0,
                setTemp = (op and 0x04) != 0,
                vane = (op and 0x08) != 0,
                menu = (op and 0x10) != 0,
                hold = (op and 0x20) != 0,
                fanSpeed = (op and 0x40) != 0,
                louver = (op and 0x80) != 0,
            ),
            rclock = rclock,
        )
    }
}
