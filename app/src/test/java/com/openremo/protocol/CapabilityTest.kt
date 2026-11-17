package com.openremo.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 机型能力表（原 SDK j.b，基础 76 字节）解析回归测试。 */
class CapabilityTest {

    /** 真机 M/R_40MAAC 登录时 session 3 ctrl=0 func=0 返回的原始 payload（已去掉 L1 校验和） */
    private val realPayload = hex(
        "00 2f 00 00 03 90 01 80 02 70 01 80 02 90 01 00 00 00 00 00 00 00 00 0b 20 00 00 00 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00",
    )

    @Test
    fun parsesRealDevice() {
        val cap = CapabilityReport.parse(realPayload)
        assertNotNull(cap)
        cap!!

        assertTrue(cap.known)
        assertTrue(cap.coolMode)
        assertTrue(cap.heatMode)
        assertTrue(cap.dryMode)
        assertTrue(cap.fanMode)
        assertTrue(cap.autoMode)
        assertFalse(cap.setback)
        assertFalse(cap.burningHeat)

        // 40MAAC 是基础多联机面板：没有左右/上下扫风、百叶、人感、保持、内部清洁
        assertEquals(0, cap.vaneControl)
        assertFalse(cap.supportsVane)
        assertFalse(cap.louver)
        assertFalse(cap.supportsLouver)
        assertFalse(cap.holdEnabled)
        assertFalse(cap.supportsHold)
        assertEquals(0, cap.rightLeftControl)
        assertFalse(cap.supportsRightLeft)
        assertFalse(cap.moveEye)
        assertFalse(cap.supportsInnerClean)
        assertFalse(cap.lossnay)
        assertFalse(cap.supportsVent)

        assertEquals(3, cap.fanSteps)
        assertTrue(cap.fanAuto)
        assertTrue(cap.supportsSilentFan)

        assertEquals(19.0, cap.coolRange!!.lower, 0.001)
        assertEquals(30.0, cap.coolRange!!.upper, 0.001)
        assertEquals(17.0, cap.heatRange!!.lower, 0.001)
        assertEquals(28.0, cap.heatRange!!.upper, 0.001)
        assertEquals(19.0, cap.autoRange!!.lower, 0.001)
        assertEquals(28.0, cap.autoRange!!.upper, 0.001)

        // 该机型只回基础 76 字节，没有追加的电力/换气条件块
        assertFalse(cap.electricity)
        assertFalse(cap.ai)
        assertFalse(cap.ateyoke)
        assertFalse(cap.bleSensor)
        assertEquals(0, cap.ventIfType)
    }

    @Test
    fun parsesConditionalBlocksWhenPresent() {
        // 77 字节：基础表 + 电力/AI 块
        val electric = ByteArray(77)
        setBits(electric, 615, 1, 1) // electricity
        setBits(electric, 614, 1, 1) // ai
        val cap1 = CapabilityReport.parse(electric)
        assertNotNull(cap1)
        assertTrue(cap1!!.electricity)
        assertTrue(cap1.ai)
        assertEquals(0, cap1.ventIfType)

        // 78 字节：再追加换气类型块（vent_if_type 位于 bit 617）
        val vent = ByteArray(78)
        setBits(vent, 617, 2, 1)
        val cap2 = CapabilityReport.parse(vent)
        assertNotNull(cap2)
        assertEquals(1, cap2!!.ventIfType)
    }

    @Test
    fun shortPayloadIsNotKnown() {
        assertEquals(null, CapabilityReport.parse(ByteArray(10)))
        val fallback = DeviceCapabilities()
        assertFalse(fallback.known)
        // 没读到能力表时不能误隐藏控件
        assertTrue(fallback.supportsVane)
        assertTrue(fallback.supportsLouver)
        assertTrue(fallback.supportsHold)
        assertTrue(fallback.supportsRightLeft)
        assertTrue(fallback.supportsMoveEye)
        assertTrue(fallback.supportsInnerClean)
        assertTrue(fallback.supportsVent)
        assertTrue(fallback.supportsMode(Mode.COOL))
    }

    private fun hex(text: String): ByteArray =
        text.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

    /** 按协议位序写入：偏移 0 对应字节 0 的最高位 */
    private fun setBits(bytes: ByteArray, offset: Int, count: Int, value: Int) {
        var remaining = count
        var bitOffset = offset
        var source = value
        while (remaining > 0) {
            val byteIndex = bitOffset / 8
            val start = bitOffset % 8
            val take = minOf(8 - start, remaining)
            val chunk = (source ushr (remaining - take)) and ((1 shl take) - 1)
            val shift = 8 - start - take
            bytes[byteIndex] = (bytes[byteIndex].toInt() or (chunk shl shift)).toByte()
            remaining -= take
            bitOffset += take
        }
    }
}
