package com.openremo.timer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.openremo.data.Prefs
import com.openremo.data.RemoteRecord
import com.openremo.data.RemoRepository

/**
 * 用 AlarmManager 注册单次关闭任务。
 *
 * 优先使用精确闹钟；没有 SCHEDULE_EXACT_ALARM 权限时退化为
 * setAndAllowWhileIdle，系统可能延迟几分钟执行。
 */
object TimerScheduler {

    const val ACTION_FIRE = "com.openremo.timer.action.FIRE"
    const val ACTION_CANCEL = "com.openremo.timer.action.CANCEL"
    const val EXTRA_ADDRESS = "com.openremo.timer.extra.ADDRESS"

    fun schedule(context: Context, record: RemoteRecord) {
        if (record.timerAt <= 0L) {
            cancel(context, record.address)
            return
        }
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = fireIntent(context, record.address)
        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    !manager.canScheduleExactAlarms() ->
                    manager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        record.timerAt,
                        pending,
                    )

                else ->
                    manager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        record.timerAt,
                        pending,
                    )
            }
        } catch (_: SecurityException) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, record.timerAt, pending)
        }
    }

    fun cancel(context: Context, address: String) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        manager.cancel(fireIntent(context, address))
    }

    /** 开机后重新注册所有未执行的任务。 */
    fun rescheduleAll(context: Context) {
        val now = System.currentTimeMillis()
        val grace = RemoRepository.TIMER_GRACE_MS
        val prefs = Prefs(context.applicationContext)
        val records = prefs.records
        records.filter { it.timerAt > 0L }.forEach { record ->
            if (record.timerAt > now - grace) {
                // 未来任务正常注册；刚错过（15 分钟内）的任务会立即触发补执行
                schedule(context, record)
            } else {
                // 超过宽限期：清掉记录里的任务，避免界面一直显示“已设定”
                val updated = records.map {
                    if (it.address == record.address) it.copy(timerAt = 0L) else it
                }
                prefs.records = updated
            }
        }
        if (records.any { it.timerAt > now }) {
            TimerService.sync(context)
        }
    }

    private fun fireIntent(context: Context, address: String): PendingIntent {
        val intent = Intent(context, TimerReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_ADDRESS, address)
        }
        return PendingIntent.getBroadcast(
            context,
            address.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
