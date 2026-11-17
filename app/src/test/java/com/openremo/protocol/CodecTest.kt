package com.openremo.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协议编解码的回归测试。
 *
 * 这些测试不依赖 Android，主要用来防止位域顺序被改错。
 */
class CodecTest {

    @Test
    fun tempEncodeDecode() {
        assertArrayEquals(byteArrayOf(0x50, 0x02), TempCodec.encode(25.0))
        assertEquals(25.0, TempCodec.decode(byteArrayOf(0x50, 0x02), 0)!!, 0.001)
        assertEquals(30.0, TempCodec.decode(TempCodec.encode(30.0), 0)!!, 0.001)
        assertEquals(18.5, TempCodec.decode(TempCodec.encode(18.5), 0)!!, 0.001)
        assertEquals(-5.5, TempCodec.decode(TempCodec.encode(-5.5), 0)!!, 0.001)
        assertNull(TempCodec.decode(byteArrayOf(0xFF.toByte(), 0xFF.toByte()), 0))
    }

    @Test
    fun loginBlocks() {
        assertArrayEquals(
            byteArrayOf(0, 0, 0xAA.toByte(), 0xAA.toByte(), 0, 0, 0),
            LoginCodec.guest(0),
        )
        // 会话 ID 跟随 L2 会话，密码 "32BC" 按 nibble 反序打包成 BC 32
        assertArrayEquals(
            byteArrayOf(3, 1, 0xBC.toByte(), 0x32, 0, 0, 0),
            LoginCodec.user(3, "32bc"),
        )
    }

    @Test
    fun controlPayloadLayout() {
        val state = AcState(
            power = true,
            mode = Mode.COOL,
            coolTemp = 25.0,
            heatTemp = 20.0,
            autoTemp = 24.0,
            setbackUpperTemp = 28.0,
            setbackLowerTemp = 22.0,
            fanSpeed = FanSpeed.HIGH,
            vane = Vane.AUTO,
            louver = Louver.ON,
            vent = Vent.HIGH,
            moveEye = MoveEye.DIRECT,
            hold = true,
            innerClean = true,
            rightLeft = 6,
        )
        val flags = ControlFlags(power = true, mode = true, coolTemp = true, fanSpeed = true)
        val out = ControlPayload.build(state, flags)

        assertEquals(17, out.size)
        // byte0：电源标志 bit0、模式标志 bit1
        assertEquals(0x03, out[0].toInt() and 0xFF)
        // byte1：制冷温度标志 bit0
        assertEquals(0x01, out[1].toInt() and 0xFF)
        // byte2：风速标志 bit0
        assertEquals(0x01, out[2].toInt() and 0xFF)
        // byte3：模式(1)<<3 | 电源(1)
        assertEquals(0x09, out[3].toInt() and 0xFF)
        // 制冷 25.0
        assertEquals(0x50, out[4].toInt() and 0xFF)
        assertEquals(0x02, out[5].toInt() and 0xFF)
        // byte14：风向(6)<<4 | 风速(3)
        assertEquals(0x63, out[14].toInt() and 0xFF)
        // byte15：换气(2)<<4 | 百叶(1)
        assertEquals(0x21, out[15].toInt() and 0xFF)
        // byte16：hold bit0 | rightLeft(6)<<1 | moveEye(1)<<4 | innerClean bit7
        assertEquals(0x9D, out[16].toInt() and 0xFF)
    }

    @Test
    fun fourStepVaneUsesShiftedWireValue() {
        val state = AcState(power = true, vane = Vane.TWO)
        val normal = ControlPayload.build(state, ControlFlags(vane = true))
        val fourStep = ControlPayload.build(state, ControlFlags(vane = true), vaneControl = 1)
        // 5 档机型直接发 5
        assertEquals(0x50, normal[14].toInt() and 0xF0)
        // 4 档机型原 SDK 把 TWO 映射成 THREE(2)
        assertEquals(0x20, fourStep[14].toInt() and 0xF0)
    }

    @Test
    fun autoCoolingUsesAutoRequestValue() {
        val state = AcState(power = true, mode = Mode.AUTO_COOL)
        val out = ControlPayload.build(state, ControlFlags(mode = true))
        // 自动制冷在状态里是 3，但控制请求要归一成 19
        assertEquals(19, (out[3].toInt() and 0xFF) ushr 3)
    }

    @Test
    fun temperatureFieldFollowsMode() {
        val cool = TempRange(18.0, 30.0)
        val heat = TempRange(16.0, 28.0)
        val auto = TempRange(20.0, 26.0)
        val setback = TempRange(24.0, 30.0)
        val base = AcState(
            coolRange = cool,
            heatRange = heat,
            autoRange = auto,
            setbackUpperRange = setback,
            coolTemp = 25.0,
            heatTemp = 21.0,
            autoTemp = 23.0,
            setbackUpperTemp = 27.0,
        )

        fun check(mode: Mode, temp: Double, range: TempRange, flags: ControlFlags) {
            val state = base.copy(mode = mode)
            assertEquals(temp, state.activeTemp!!, 0.001)
            assertEquals(range, state.activeRange)
            val updated = state.withTemp(24.5)
            assertEquals(24.5, updated.activeTemp!!, 0.001)
            assertEquals(flags, ControlFlags.forTemp(mode))
        }

        check(Mode.COOL, 25.0, cool, ControlFlags(coolTemp = true))
        check(Mode.DRY, 25.0, cool, ControlFlags(coolTemp = true))
        check(Mode.DUAL, 25.0, cool, ControlFlags(coolTemp = true))
        check(Mode.HEAT, 21.0, heat, ControlFlags(heatTemp = true))
        check(Mode.BURNING, 21.0, heat, ControlFlags(heatTemp = true))
        check(Mode.AUTO, 23.0, auto, ControlFlags(autoTemp = true))
        check(Mode.AUTO_COOL, 23.0, auto, ControlFlags(autoTemp = true))
        check(Mode.AUTO_HEAT, 23.0, auto, ControlFlags(autoTemp = true))
        check(Mode.SETBACK, 27.0, setback, ControlFlags(setbackUpperTemp = true))
    }

    @Test
    fun autoModeSendsAutoTemperatureField() {
        // 自动制冷模式下 coolTemp 与 autoTemp 故意不同，确认发送的是 auto 组
        val state = AcState(
            power = true,
            mode = Mode.AUTO_COOL,
            coolTemp = 20.0,
            autoTemp = 24.0,
        )
        val out = ControlPayload.build(state, ControlFlags.forTemp(Mode.AUTO_COOL))
        // byte1：autoTemp 标志 bit2
        assertEquals(0x04, out[1].toInt() and 0xFF)
        // byte8-9 是 auto 组，应编码 24.0（0x40 0x02）
        assertEquals(0x40, out[8].toInt() and 0xFF)
        assertEquals(0x02, out[9].toInt() and 0xFF)
    }

    @Test
    fun statusReportParse() {
        val b = ByteArray(44)
        // byte0：power=1（bit1）、unitmode=19（自动）
        b[0] = ((19 shl 3) or (1 shl 1)).toByte()
        // 5 组温度范围：制冷 18~30
        TempCodec.encode(30.0).copyInto(b, 1)
        TempCodec.encode(18.0).copyInto(b, 3)
        // 5 个设定温度全部 25.0
        for (i in 0 until 5) {
            TempCodec.encode(25.0).copyInto(b, 21 + i * 2)
        }
        // byte31：风速 HIGH(3)<<4 | 百叶 ON(1)
        b[31] = 0x31
        // byte32：换气 HIGH(2)<<4 | 风向 FIVE(0)
        b[32] = 0x20
        // byte33：电源锁 + 温度锁
        b[33] = 0x05
        // byte34：制冷/制热模式限制
        b[34] = 0x03
        b[35] = 0x01
        // byte36：rightLeft=6
        b[36] = (6 shl 4).toByte()
        // byte37：hold bit0 | moveEye(3)<<3 | innerClean bit7
        b[37] = (0x01 or (3 shl 3) or 0x80).toByte()
        TempCodec.encode(22.5).copyInto(b, 38)

        val state = StatusReport.parse(b)
        assertNotNull(state)
        state!!
        assertTrue(state.power)
        assertEquals(Mode.AUTO, state.mode)
        assertEquals(30.0, state.coolRange.upper, 0.001)
        assertEquals(18.0, state.coolRange.lower, 0.001)
        assertEquals(25.0, state.coolTemp!!, 0.001)
        assertEquals(FanSpeed.HIGH, state.fanSpeed)
        assertEquals(Louver.ON, state.louver)
        assertEquals(Vent.HIGH, state.vent)
        assertEquals(Vane.FIVE, state.vane)
        assertEquals(MoveEye.AREA, state.moveEye)
        assertTrue(state.hold)
        assertTrue(state.innerClean)
        assertEquals(6, state.rightLeft)
        assertEquals(22.5, state.roomTemp!!, 0.001)
        assertTrue(state.opLock.power)
        assertTrue(state.opLock.setTemp)
        assertTrue(state.modeLimits.cool)
        assertTrue(state.modeLimits.heat)
        assertTrue(!state.modeLimits.dry)
        assertTrue(state.rclock)
    }

    @Test
    fun l1FrameRoundTrip() {
        val frame = L1Protocol.build(0, byteArrayOf(0x01))
        assertArrayEquals(byteArrayOf(0x04, 0x00, 0x00, 0x01, 0x05, 0x00), frame)

        val parsed = L1Protocol.parse(frame)
        assertNotNull(parsed)
        assertEquals(0, parsed!!.seq)
        assertEquals(0, parsed.direction)
        assertArrayEquals(byteArrayOf(0x01), parsed.payload)
    }

    @Test
    fun framerHandlesSplitAndStickyFrames() {
        val framer = L1Framer()
        // 两条响应帧（direction=1），第二条故意和第一条粘在一起
        val payload = byteArrayOf(0x08, 0x01)
        val len = payload.size + 2
        val checksum = len + payload.sumOf { it.toInt() and 0xFF }
        val frame = byteArrayOf(
            len.toByte(), 0, payload[0], payload[1],
            checksum.toByte(), 0,
        )

        assertTrue(framer.feed(frame.copyOfRange(0, 3)).isEmpty())
        val frames = framer.feed(frame.copyOfRange(3, frame.size) + frame)
        assertEquals(2, frames.size)
        assertEquals(0, frames[0].seq)
        assertEquals(1, frames[0].direction)
    }
}
