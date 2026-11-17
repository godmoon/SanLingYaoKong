package com.openremo.protocol

/**
 * 设备协议中的枚举值。
 *
 * 说明：三菱电机这套 BLE 协议里，同一语义在不同方向上的 wire 值不一定相同。
 * 最典型的是模式：状态回报里会出现 3=自动制冷、4=自动制热，
 * 但原 SDK 在发送控制时会把这两种都归一成 19（自动），所以这里显式区分
 * [wire]（状态值）和 [requestValue]（控制值）。
 */
enum class Mode(val wire: Int, val requestValue: Int, val label: String) {
    COOL(1, 1, "制冷"),
    HEAT(2, 2, "制热"),
    FAN(0, 0, "送风"),
    DRY(6, 6, "除湿"),
    AUTO(19, 19, "自动"),
    AUTO_COOL(3, 19, "自动制冷"),
    AUTO_HEAT(4, 19, "自动制热"),
    BURNING(7, 7, "暖气"),
    SETBACK(14, 14, "节能"),
    DUAL(15, 15, "双自动");

    /** 用于界面上的大类归并 */
    val uiMode: Mode
        get() = when (this) {
            AUTO_COOL -> AUTO
            AUTO_HEAT -> AUTO
            else -> this
        }

    val supportsTemperature: Boolean
        get() = this != FAN

    companion object {
        fun fromWire(value: Int): Mode? = entries.firstOrNull { it.wire == value }
    }
}

enum class FanSpeed(val wire: Int, val label: String) {
    AUTO(4, "自动"),
    SILENT(0, "静音"),
    LOW(1, "低"),
    MIDDLE(2, "中"),
    HIGH(3, "高"),
    HIGH_POWER(8, "强力"),
    RAPID(9, "急速");

    companion object {
        fun fromWire(value: Int): FanSpeed? = entries.firstOrNull { it.wire == value }
    }
}

enum class Vane(val wire: Int, val label: String) {
    AUTO(6, "自动"),
    ONE(3, "1 档"),
    TWO(5, "2 档"),
    THREE(2, "3 档"),
    FOUR(1, "4 档"),
    FIVE(0, "5 档"),
    SWING(7, "摆动");

    companion object {
        fun fromWire(value: Int): Vane? = entries.firstOrNull { it.wire == value }
    }
}

enum class Louver(val wire: Int, val label: String) {
    OFF(0, "关闭"),
    ON(1, "开启");

    companion object {
        fun fromWire(value: Int): Louver = if (value == 1) ON else OFF
    }
}

enum class MoveEye(val statusValue: Int, val requestValue: Int, val label: String) {
    OFF(0, 0, "关闭"),
    DIRECT(1, 1, "直吹"),
    INDIRECT(2, 2, "避人"),
    AREA(3, 3, "区域"),
    AUTO(4, 4, "自动"),
    ATEYOKE(5, 5, "舒适");

    companion object {
        fun fromStatus(value: Int): MoveEye? = entries.firstOrNull { it.statusValue == value }
    }
}

enum class Vent(val statusValue: Int, val requestValue: Int, val label: String) {
    OFF(0, 0, "关闭"),
    LOW(1, 1, "弱"),
    HIGH(2, 2, "强"),
    COOPERATION_LOW(5, 5, "联动弱"),
    COOPERATION_HIGH(6, 6, "联动强"),
    MOVE_EYE_LOW(7, 7, "人感弱"),
    MOVE_EYE_HIGH(8, 8, "人感强"),
    MOVE_EYE_OFF(9, 9, "人感关闭");

    companion object {
        fun fromStatus(value: Int): Vent? = entries.firstOrNull { it.statusValue == value }
    }
}

data class TempRange(val lower: Double = 18.0, val upper: Double = 30.0) {
    fun clamp(value: Double): Double = value.coerceIn(lower, upper)
}

data class ModeLimits(
    val cool: Boolean = true,
    val heat: Boolean = true,
    val dry: Boolean = true,
    val auto: Boolean = true,
)

data class OpLock(
    val power: Boolean = false,
    val mode: Boolean = false,
    val setTemp: Boolean = false,
    val vane: Boolean = false,
    val menu: Boolean = false,
    val hold: Boolean = false,
    val fanSpeed: Boolean = false,
    val louver: Boolean = false,
)

/**
 * 对设备状态的一个快照。所有温度单位均为摄氏度。
 */
data class AcState(
    val power: Boolean = false,
    val mode: Mode = Mode.COOL,
    val coolRange: TempRange = TempRange(),
    val heatRange: TempRange = TempRange(),
    val autoRange: TempRange = TempRange(),
    val setbackUpperRange: TempRange = TempRange(),
    val setbackLowerRange: TempRange = TempRange(),
    val coolTemp: Double? = 25.0,
    val heatTemp: Double? = 25.0,
    val autoTemp: Double? = 25.0,
    val setbackUpperTemp: Double? = 25.0,
    val setbackLowerTemp: Double? = 25.0,
    val fanSpeed: FanSpeed = FanSpeed.AUTO,
    val vane: Vane = Vane.AUTO,
    val louver: Louver = Louver.OFF,
    val vent: Vent = Vent.OFF,
    val moveEye: MoveEye = MoveEye.OFF,
    val hold: Boolean = false,
    val innerClean: Boolean = false,
    val rightLeft: Int = 0,
    val roomTemp: Double? = null,
    val modeLimits: ModeLimits = ModeLimits(),
    val opLock: OpLock = OpLock(),
    val rclock: Boolean = false,
) {
    /**
     * 当前界面需要显示 / 调节的设定温度。
     *
     * 映射依据原 SDK 的 getCurrentTempFromMode 与 setTempFollowByMode：
     * - 制冷、除湿用 cool
     * - 制热、暖气用 heat
     * - 自动、自动制冷、自动制热用 auto（自动制冷/制热只出现在状态回报里）
     * - 节能用 setback upper
     * - 双自动在 SDK 里按 isLower 分别改 cool / heat；本界面只有一个温度盘，
     *   统一先操作 cool，后续再做上下双温区。
     */
    val activeRange: TempRange
        get() = when (mode) {
            Mode.COOL, Mode.DRY, Mode.DUAL -> coolRange
            Mode.HEAT, Mode.BURNING -> heatRange
            Mode.AUTO, Mode.AUTO_COOL, Mode.AUTO_HEAT -> autoRange
            Mode.SETBACK -> setbackUpperRange
            else -> coolRange
        }

    val activeTemp: Double?
        get() = when (mode) {
            Mode.COOL, Mode.DRY, Mode.DUAL -> coolTemp
            Mode.HEAT, Mode.BURNING -> heatTemp
            Mode.AUTO, Mode.AUTO_COOL, Mode.AUTO_HEAT -> autoTemp
            Mode.SETBACK -> setbackUpperTemp
            else -> coolTemp
        }

    fun withTemp(value: Double): AcState = when (mode) {
        Mode.COOL, Mode.DRY, Mode.DUAL -> copy(coolTemp = value)
        Mode.HEAT, Mode.BURNING -> copy(heatTemp = value)
        Mode.AUTO, Mode.AUTO_COOL, Mode.AUTO_HEAT -> copy(autoTemp = value)
        Mode.SETBACK -> copy(setbackUpperTemp = value)
        else -> this
    }
}

data class ControlFlags(
    val power: Boolean = false,
    val mode: Boolean = false,
    val coolTemp: Boolean = false,
    val heatTemp: Boolean = false,
    val autoTemp: Boolean = false,
    val setbackUpperTemp: Boolean = false,
    val setbackLowerTemp: Boolean = false,
    val fanSpeed: Boolean = false,
    val vane: Boolean = false,
    val louver: Boolean = false,
    val vent: Boolean = false,
    val hold: Boolean = false,
    val rightLeft: Boolean = false,
    val moveEye: Boolean = false,
    val innerClean: Boolean = false,
) {
    companion object {
        val POWER = ControlFlags(power = true)
        val MODE = ControlFlags(mode = true)
        val FAN = ControlFlags(fanSpeed = true)
        val VANE = ControlFlags(vane = true)
        val LOUVER = ControlFlags(louver = true)
        val VENT = ControlFlags(vent = true)
        val HOLD = ControlFlags(hold = true)
        val RIGHT_LEFT = ControlFlags(rightLeft = true)
        val MOVE_EYE = ControlFlags(moveEye = true)
        val INNER_CLEAN = ControlFlags(innerClean = true)

        fun forTemp(mode: Mode): ControlFlags = when (mode) {
            Mode.COOL, Mode.DRY, Mode.DUAL -> ControlFlags(coolTemp = true)
            Mode.HEAT, Mode.BURNING -> ControlFlags(heatTemp = true)
            Mode.AUTO, Mode.AUTO_COOL, Mode.AUTO_HEAT -> ControlFlags(autoTemp = true)
            Mode.SETBACK -> ControlFlags(setbackUpperTemp = true)
            else -> ControlFlags()
        }
    }
}

data class ConnectionInfo(
    val deviceName: String = "",
    val deviceAddress: String = "",
)

data class ScannedDevice(
    val name: String,
    val address: String,
    val rssi: Int,
)

data class LogEntry(
    val time: Long,
    val tag: String,
    val message: String,
)
