package com.openremo.timer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.openremo.R
import com.openremo.data.AppState
import com.openremo.data.Prefs
import com.openremo.data.RemoRepository
import com.openremo.data.RemoteRecord
import com.openremo.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 定时任务前台服务。
 *
 * - 只要还有未执行的定时任务，就常驻前台通知，避免进程被系统回收；
 * - 到点后由 [TimerReceiver] 拉起本服务，连接目标遥控器并执行关机；
 * - 任务执行完或全部取消后自动退出。
 */
class TimerService : Service() {

    companion object {
        private const val ACTION_SYNC = "com.openremo.timer.action.SYNC"
        private const val ACTION_FIRE = "com.openremo.timer.action.FIRE"
        private const val ACTION_CANCEL = "com.openremo.timer.action.CANCEL"
        private const val EXTRA_ADDRESS = "com.openremo.timer.extra.ADDRESS"

        private const val CHANNEL_ID = "timer"
        private const val NOTIFICATION_ID = 0x5449
        private const val RESULT_NOTIFICATION_ID = 0x544A

        fun sync(context: Context) {
            val now = System.currentTimeMillis()
            val hasPending = Prefs(context.applicationContext)
                .records.any { it.timerAt > now }
            if (hasPending) {
                start(context, Intent(context, TimerService::class.java).setAction(ACTION_SYNC))
            } else {
                // 没有待执行任务时直接停服务，避免空拉前台服务
                context.stopService(Intent(context, TimerService::class.java))
            }
        }

        fun fire(context: Context, address: String) {
            start(
                context,
                Intent(context, TimerService::class.java)
                    .setAction(ACTION_FIRE)
                    .putExtra(EXTRA_ADDRESS, address),
            )
        }

        fun cancelTimer(context: Context, address: String) {
            start(
                context,
                Intent(context, TimerService::class.java)
                    .setAction(ACTION_CANCEL)
                    .putExtra(EXTRA_ADDRESS, address),
            )
        }

        private fun start(context: Context, intent: Intent) {
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {
                // 系统限制后台启动前台服务时，Alarm 仍会在到点后再次尝试
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val executionMutex = Mutex()

    override fun onCreate() {
        super.onCreate()
        RemoRepository.init(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_FIRE) {
            val address = intent?.getStringExtra(EXTRA_ADDRESS).orEmpty()
            val record = RemoRepository.findRecord(address)
            if (address.isBlank() || record == null || record.timerAt == 0L) {
                // 任务已被取消或不存在，不再拉起常驻通知
                stopSelf()
                return START_NOT_STICKY
            }
            if (!ensureForeground("正在关闭「${record.title}」…")) return START_NOT_STICKY
            scope.launch {
                executionMutex.withLock { execute(address) }
            }
            return START_NOT_STICKY
        }

        if (action == ACTION_CANCEL) {
            val address = intent.getStringExtra(EXTRA_ADDRESS).orEmpty()
            if (address.isNotBlank()) {
                RemoRepository.finishTimer(address)
                TimerScheduler.cancel(this, address)
            }
        } else {
            cleanupExpired()
        }

        if (RemoRepository.pendingTimers().isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!ensureForeground()) return START_NOT_STICKY
        refreshOrStop()
        return START_NOT_STICKY
    }

    private suspend fun execute(address: String) {
        val record = RemoRepository.findRecord(address)
        if (record == null || record.timerAt == 0L) {
            refreshOrStop()
            return
        }
        updateNotification("正在关闭「${record.title}」…")
        val ok = try {
            RemoRepository.turnOffForTimer(address)
        } catch (e: Exception) {
            RemoRepository.log("定时", "执行异常：${e.message}")
            false
        }
        // 执行期间用户可能改了或取消了任务，只在任务没变时清理，避免覆盖新设定
        val stillSame = RemoRepository.findRecord(address)?.timerAt == record.timerAt
        if (stillSame) {
            RemoRepository.finishTimer(address)
            TimerScheduler.cancel(this, address)
        }
        RemoRepository.log("定时", if (ok) "已按计划关闭「${record.title}」" else "关闭「${record.title}」失败")
        notifyResult(record.title, ok)
        if (!AppState.foreground) {
            RemoRepository.disconnect()
        }
        refreshOrStop()
    }

    /** 清理已经过期但没执行的任务（例如关机期间错过）。 */
    private fun cleanupExpired() {
        RemoRepository.expiredTimers()
            .forEach { RemoRepository.finishTimer(it.address) }
    }

    private fun refreshOrStop() {
        val pending = RemoRepository.pendingTimers()
        if (pending.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            updateNotification()
        }
    }

    private fun ensureForeground(overrideText: String? = null): Boolean {
        return try {
            createChannel()
            startForeground(NOTIFICATION_ID, buildNotification(overrideText))
            true
        } catch (e: Exception) {
            // 缺少蓝牙/通知权限或系统限制时不要崩溃，任务留待下次唤醒重试
            RemoRepository.log("定时", "前台服务启动失败：${e.message}")
            stopSelf()
            false
        }
    }

    private fun updateNotification(overrideText: String? = null) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(overrideText))
    }

    private fun buildNotification(overrideText: String? = null): Notification {
        val pending = RemoRepository.pendingTimers()
        val next = pending.firstOrNull()
        val text = overrideText ?: when {
            next == null -> "没有待执行的任务"
            pending.size == 1 -> "${next.title} · ${timeText(next.timerAt)} 自动关闭"
            else -> "${pending.size} 个任务 · 最近 ${next.title} ${timeText(next.timerAt)}"
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle("定时关闭")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (next != null) {
            val cancelIntent = PendingIntent.getBroadcast(
                this,
                next.address.hashCode() + 1,
                Intent(this, TimerReceiver::class.java).apply {
                    action = TimerScheduler.ACTION_CANCEL
                    putExtra(TimerScheduler.EXTRA_ADDRESS, next.address)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(R.drawable.ic_timer, "取消最近任务", cancelIntent)
        }
        return builder.build()
    }

    private fun notifyResult(title: String, ok: Boolean) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val contentIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(if (ok) "已定时关闭" else "定时关闭失败")
            .setContentText(
                if (ok) "「$title」已关闭" else "无法连接「$title」，请打开应用检查",
            )
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        manager.notify(RESULT_NOTIFICATION_ID, notification)
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "定时任务", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "定时关闭空调时的常驻通知"
                setShowBadge(false)
            },
        )
    }

    private fun timeText(at: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
