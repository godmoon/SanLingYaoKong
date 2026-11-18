package com.openremo.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条遥控器记录。地址是唯一键，别名由用户自定义（卧室、客厅等）。
 */
data class RemoteRecord(
    val address: String,
    val alias: String = "",
    val deviceName: String = "",
    val guest: Boolean = true,
    val password: String = "",
    val rssi: Int = RSSI_UNKNOWN,
    /** 单次定时关闭的绝对时间戳，0 表示没有任务 */
    val timerAt: Long = 0L,
) {
    val title: String
        get() = alias.ifBlank {
            deviceName.ifBlank { "遥控器 ${address.takeLast(4).uppercase()}" }
        }

    val hasTimer: Boolean
        get() = timerAt > System.currentTimeMillis()

    /** 0~100，越大越近 */
    val signal: Int
        get() = rssiToQuality(rssi)

    fun withRssi(value: Int): RemoteRecord =
        if (value >= 0 || value == RSSI_UNKNOWN || value == rssi) this else copy(rssi = value)

    fun toJson(): JSONObject = JSONObject().apply {
        put("address", address)
        put("alias", alias)
        put("deviceName", deviceName)
        put("guest", guest)
        put("password", password)
        put("rssi", rssi)
        put("timerAt", timerAt)
    }

    companion object {
        const val RSSI_UNKNOWN = -127

        fun fromJson(json: JSONObject): RemoteRecord? {
            val address = json.optString("address").trim()
            if (address.isBlank()) return null
            return RemoteRecord(
                address = address,
                alias = json.optString("alias"),
                deviceName = json.optString("deviceName"),
                guest = json.optBoolean("guest", true),
                password = json.optString("password"),
                rssi = json.optInt("rssi", RSSI_UNKNOWN),
                timerAt = json.optLong("timerAt", 0L),
            )
        }

        fun decode(raw: String?): List<RemoteRecord> {
            if (raw.isNullOrBlank()) return emptyList()
            return try {
                val array = JSONArray(raw)
                (0 until array.length()).mapNotNull { index ->
                    array.optJSONObject(index)?.let { fromJson(it) }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun encode(records: List<RemoteRecord>): String =
            JSONArray().apply { records.forEach { put(it.toJson()) } }.toString()

        /** RSSI（-127 ~ -20 dBm）换算成 0~100，数值越大代表越近。 */
        fun rssiToQuality(rssi: Int): Int {
            if (rssi == RSSI_UNKNOWN || rssi >= 0) return 0
            return ((rssi + 100) * 2).coerceIn(0, 100)
        }
    }
}
