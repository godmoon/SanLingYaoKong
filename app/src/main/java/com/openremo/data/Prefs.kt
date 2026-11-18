package com.openremo.data

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("openremo", Context.MODE_PRIVATE)

    var deviceAddress: String
        get() = sp.getString(KEY_ADDRESS, "") ?: ""
        set(value) = sp.edit().putString(KEY_ADDRESS, value).apply()

    var deviceName: String
        get() = sp.getString(KEY_NAME, "") ?: ""
        set(value) = sp.edit().putString(KEY_NAME, value).apply()

    var guestMode: Boolean
        get() = sp.getBoolean(KEY_GUEST, true)
        set(value) = sp.edit().putBoolean(KEY_GUEST, value).apply()

    var password: String
        get() = sp.getString(KEY_PASSWORD, "") ?: ""
        set(value) = sp.edit().putString(KEY_PASSWORD, value).apply()

    var pollSeconds: Int
        get() = sp.getInt(KEY_POLL, 5)
        set(value) = sp.edit().putInt(KEY_POLL, value.coerceIn(2, 30)).apply()

    var autoReconnect: Boolean
        get() = sp.getBoolean(KEY_RECONNECT, true)
        set(value) = sp.edit().putBoolean(KEY_RECONNECT, value).apply()

    var autoConnect: Boolean
        get() = sp.getBoolean(KEY_AUTO_CONNECT, true)
        set(value) = sp.edit().putBoolean(KEY_AUTO_CONNECT, value).apply()

    // ---------------------------------------------------------------- 多设备记录

    var records: List<RemoteRecord>
        get() = RemoteRecord.decode(sp.getString(KEY_RECORDS, ""))
        set(value) = sp.edit().putString(KEY_RECORDS, RemoteRecord.encode(value)).apply()

    var selectedAddress: String
        get() = sp.getString(KEY_SELECTED, "") ?: ""
        set(value) = sp.edit().putString(KEY_SELECTED, value).apply()

    /** 把旧版本的单设备配置迁移成一条记录。 */
    fun migrateLegacyIfNeeded() {
        if (records.isNotEmpty()) return
        val address = deviceAddress
        if (address.isBlank()) return
        val record = RemoteRecord(
            address = address,
            alias = "",
            deviceName = deviceName,
            guest = guestMode,
            password = password,
        )
        records = listOf(record)
        selectedAddress = address
    }

    private companion object {
        const val KEY_ADDRESS = "device_address"
        const val KEY_NAME = "device_name"
        const val KEY_GUEST = "guest_mode"
        const val KEY_PASSWORD = "password"
        const val KEY_POLL = "poll_seconds"
        const val KEY_RECONNECT = "auto_reconnect"
        const val KEY_AUTO_CONNECT = "auto_connect"
        const val KEY_RECORDS = "records"
        const val KEY_SELECTED = "selected_address"
    }
}
