package com.openremo.protocol

/**
 * 登录时设备返回的机型能力表（原 SDK 的 `j.b`，78 字节）。
 *
 * 位域打包规则和状态/控制 payload 一样：先按字段声明顺序累加，满 8 bit 成一组，
 * 组内字段顺序反转，然后从每个字节的 **最高位** 开始依次占位。
 * 因此这里用 [BitReader] 按“位偏移”读取，比按字节裸解析更不容易出错。
 */
data class DeviceCapabilities(
    /** 是否真的从设备读到了能力表；false 时所有控件都按“可能支持”处理 */
    val known: Boolean = false,
    val coolMode: Boolean = true,
    val heatMode: Boolean = true,
    val dryMode: Boolean = true,
    val fanMode: Boolean = true,
    val autoMode: Boolean = true,
    val setback: Boolean = false,
    val burningHeat: Boolean = false,
    /** 上下风向档位数：0=不支持，1/2=4 档，3/4=5 档，5/6=4 档带自动 */
    val vaneControl: Int = 0,
    val louver: Boolean = true,
    val holdEnabled: Boolean = true,
    /** 左右风向档位数：0=不支持，1=3 档，2=7 档 */
    val rightLeftControl: Int = 0,
    val moveEye: Boolean = true,
    /** 是否接着 Lossnay 新风（决定换气控件是否可用） */
    val lossnay: Boolean = false,
    /** 任一室内机支持内部清洁 */
    val innerClean: Boolean = true,
    /** 任一室内机支持室外静音 */
    val outdoorSilent: Boolean = false,
    /** 风速档位数：1=2 档，2=3 档，3=4 档 */
    val fanSteps: Int = 3,
    val fanAuto: Boolean = true,
    val minTempRange: Double? = null,
    val celsius: Boolean = true,
    val zeroPointOneDegrees: Boolean = false,
    val savingAbility: Boolean = false,
    /** 制冷 / 制热的可调范围，登录时就能拿到，不必等状态回报 */
    val coolRange: TempRange? = null,
    val heatRange: TempRange? = null,
    val autoRange: TempRange? = null,
    val electricity: Boolean = false,
    val ai: Boolean = false,
    val ateyoke: Boolean = false,
    val bleSensor: Boolean = false,
    val ventIfType: Int = 0,
) {

    val supportsVane: Boolean get() = !known || vaneControl != 0

    val supportsVaneSwing: Boolean
        get() = !known || vaneControl == 2 || vaneControl == 4 || vaneControl == 6

    val supportsFiveStepVane: Boolean get() = !known || vaneControl == 3 || vaneControl == 4

    val supportsLouver: Boolean get() = !known || louver

    val supportsHold: Boolean get() = !known || holdEnabled

    val supportsRightLeft: Boolean get() = !known || rightLeftControl != 0

    val supportsMoveEye: Boolean get() = !known || moveEye

    val supportsInnerClean: Boolean get() = !known || innerClean

    /** 换气需要有 Lossnay 新风设备 */
    val supportsVent: Boolean get() = !known || lossnay

    val supportsSilentFan: Boolean get() = !known || fanSteps >= 3

    fun supportsMode(mode: Mode): Boolean {
        if (!known) return true
        return when (mode.uiMode) {
            Mode.COOL -> coolMode
            Mode.HEAT -> heatMode
            Mode.DRY -> dryMode
            Mode.FAN -> fanMode
            Mode.AUTO -> autoMode
            else -> true
        }
    }
}

object CapabilityReport {

    /** 基础能力表 76 字节；后面的电力/换气块由机型决定是否追加 */
    private const val EXPECTED_BYTES = 76

    // 各字段的位偏移（bit，从整个 payload 的第 0 位开始）
    private const val OFF_MODE = 8
    private const val OFF_RANGE = 24
    private const val OFF_LOUVER = 184
    private const val OFF_VANE = 185
    private const val OFF_FAN_AUTO = 188
    private const val OFF_FAN_STEPS = 189
    private const val OFF_HOLD = 192
    private const val OFF_ROOM_TEMP_01 = 193
    private const val OFF_SAVE = 194
    private const val OFF_TEMP_CF = 197
    private const val OFF_LOSSNAY = 199
    private const val OFF_MIN_RANGE = 204
    private const val OFF_RIGHT_LEFT = 202
    private const val OFF_MOVE_EYE = 201
    private const val OFF_FU_FUNC = 208
    private const val OFF_IC_INFO = 224
    private const val OFF_IC_INFO_BYTES = 24
    private const val OFF_ELECTRICITY = 615
    private const val OFF_AI = 614
    private const val OFF_ATEYOKE = 612
    private const val OFF_BLE_SENSOR = 611
    private const val OFF_VENT_TYPE = 617

    fun parse(bytes: ByteArray): DeviceCapabilities? {
        if (bytes.size < EXPECTED_BYTES) return null
        val reader = BitReader(bytes)

        // 模式能力：mode_exist_mx 第 1 组（8 位）按位反转后依次是
        // 暖气 / 自动 / 节能 / 制热 / 送风 / 除湿 / 制冷
        val burningHeat = reader.bit(OFF_MODE)
        val autoType = reader.read(OFF_MODE + 1, 2)
        val setback = reader.bit(OFF_MODE + 3)
        val heat = reader.bit(OFF_MODE + 4)
        val fan = reader.bit(OFF_MODE + 5)
        val dry = reader.bit(OFF_MODE + 6)
        val cool = reader.bit(OFF_MODE + 7)

        // 5 组设定范围，每组 2 字节：先上限后下限
        val coolUpper = temp(reader, OFF_RANGE)
        val coolLower = temp(reader, OFF_RANGE + 16)
        val heatUpper = temp(reader, OFF_RANGE + 32)
        val heatLower = temp(reader, OFF_RANGE + 48)
        val autoUpper = temp(reader, OFF_RANGE + 64)
        val autoLower = temp(reader, OFF_RANGE + 80)

        val louver = reader.bit(OFF_LOUVER)
        val vane = reader.read(OFF_VANE, 3)
        val fanAuto = reader.bit(OFF_FAN_AUTO)
        val fanSteps = reader.read(OFF_FAN_STEPS, 3)

        val hold = reader.bit(OFF_HOLD)
        val roomTemp01 = reader.bit(OFF_ROOM_TEMP_01)
        val save = reader.bit(OFF_SAVE)
        val tempCf = reader.read(OFF_TEMP_CF, 2)
        val lossnay = reader.bit(OFF_LOSSNAY)
        val minRangeCode = reader.read(OFF_MIN_RANGE, 4)
        val rightLeft = reader.read(OFF_RIGHT_LEFT, 2)
        val moveEye = reader.bit(OFF_MOVE_EYE)
        // 26-27 字节是 fu_func_mx（夜间净化/湿度等），目前界面不涉及，先跳过

        var innerClean = false
        var outdoorSilent = false
        for (index in 0 until 16) {
            val base = OFF_IC_INFO + index * OFF_IC_INFO_BYTES
            val present = reader.bit(base + 7)
            if (!present) continue
            // 第 2 字节组内反转后，内部清洁在第 8 位、室外静音在第 6 位
            if (reader.bit(base + 15)) innerClean = true
            if (reader.bit(base + 13)) outdoorSilent = true
        }

        val electricity = if (bytes.size >= 77) reader.bit(OFF_ELECTRICITY) else false
        val ai = if (bytes.size >= 77) reader.bit(OFF_AI) else false
        val ateyoke = if (bytes.size >= 77) reader.bit(OFF_ATEYOKE) else false
        val bleSensor = if (bytes.size >= 77) reader.bit(OFF_BLE_SENSOR) else false
        val ventType = if (bytes.size >= 78) reader.read(OFF_VENT_TYPE, 2) else 0

        return DeviceCapabilities(
            known = true,
            coolMode = cool,
            heatMode = heat,
            dryMode = dry,
            fanMode = fan,
            autoMode = autoType != 0,
            setback = setback,
            burningHeat = burningHeat,
            vaneControl = vane,
            louver = louver,
            holdEnabled = hold,
            rightLeftControl = rightLeft,
            moveEye = moveEye,
            lossnay = lossnay,
            innerClean = innerClean,
            outdoorSilent = outdoorSilent,
            fanSteps = fanSteps,
            fanAuto = fanAuto,
            minTempRange = minTempRange(minRangeCode),
            celsius = tempCf == 0,
            zeroPointOneDegrees = roomTemp01,
            savingAbility = save,
            coolRange = range(coolLower, coolUpper),
            heatRange = range(heatLower, heatUpper),
            autoRange = range(autoLower, autoUpper),
            electricity = electricity,
            ai = ai,
            ateyoke = ateyoke,
            bleSensor = bleSensor,
            ventIfType = ventType,
        )
    }

    private fun range(lower: Double?, upper: Double?): TempRange? =
        if (lower == null || upper == null || upper <= lower) null else TempRange(lower, upper)

    private fun temp(reader: BitReader, offset: Int): Double? {
        val value = reader.read(offset, 16)
        if (value == 0) return null
        val high = (value ushr 8) and 0xFF
        val low = value and 0xFF
        return TempCodec.decode(byteArrayOf(high.toByte(), low.toByte()), 0)
    }

    private fun minTempRange(code: Int): Double? = when (code) {
        0 -> 1.5
        1 -> 2.0
        2 -> 2.5
        3 -> 3.0
        4 -> 3.5
        5 -> 4.0
        6 -> 4.5
        else -> null
    }
}

/**
 * 与 SDK 的 `s.a` 位读取逻辑一致：位偏移从字节最高位开始，
 * 跨字节时低位在前，最终拼成一个大端整数。
 */
internal class BitReader(private val data: ByteArray) {

    fun bit(offset: Int): Boolean = read(offset, 1) == 1

    fun read(offset: Int, count: Int): Int {
        var byteIndex = offset / 8
        var bitOffset = offset % 8
        var remaining = count
        var value = 0
        while (remaining > 0) {
            if (byteIndex >= data.size) return 0
            val take = minOf(8 - bitOffset, remaining)
            val mask = (1 shl take) - 1
            val chunk = ((data[byteIndex].toInt() and 0xFF) ushr (8 - bitOffset - take)) and mask
            value = (value shl take) or chunk
            remaining -= take
            byteIndex++
            bitOffset = 0
        }
        return value
    }
}
