package com.openremo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteRecordTest {

    @Test
    fun rssiToQuality_mapsDistanceToPercent() {
        assertEquals(100, RemoteRecord.rssiToQuality(-50))
        assertEquals(50, RemoteRecord.rssiToQuality(-75))
        assertEquals(0, RemoteRecord.rssiToQuality(-100))
        assertEquals(0, RemoteRecord.rssiToQuality(-127))
        assertEquals(0, RemoteRecord.rssiToQuality(10))
    }

    @Test
    fun title_prefersAliasThenNameThenAddress() {
        assertEquals("客厅", RemoteRecord("AA:BB:CC:DD:8D7E", alias = "客厅").title)
        assertEquals(
            "M/R_40MAAC",
            RemoteRecord("AA:BB:CC:DD:8D7E", deviceName = "M/R_40MAAC").title,
        )
        assertEquals("遥控器 8D7E", RemoteRecord("AA:BB:CC:DD:8D7E").title)
    }

    @Test
    fun hasTimer_onlyWhenFuture() {
        val now = System.currentTimeMillis()
        assertTrue(RemoteRecord("A", timerAt = now + 60_000).hasTimer)
        assertFalse(RemoteRecord("A", timerAt = now - 60_000).hasTimer)
        assertFalse(RemoteRecord("A", timerAt = 0).hasTimer)
    }

    @Test
    fun withRssi_ignoresInvalidValues() {
        val record = RemoteRecord("A", rssi = -60)
        assertEquals(-60, record.withRssi(-127).rssi)
        assertEquals(-60, record.withRssi(5).rssi)
        assertEquals(-70, record.withRssi(-70).rssi)
    }
}
