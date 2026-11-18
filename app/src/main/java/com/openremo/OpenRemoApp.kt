package com.openremo

import android.app.Application
import android.content.pm.ApplicationInfo
import com.openremo.data.RemoRepository
import com.openremo.debug.DebugBridge
import com.openremo.timer.TimerScheduler

class OpenRemoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RemoRepository.init(this)
        // 冷启动时恢复定时任务与前台保活服务（进程被杀后 Alarm 仍在，但常驻通知需要重建）
        TimerScheduler.rescheduleAll(this)
        // 仅 Debug 包启动本地命令桥，release 包不会暴露调试端口
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            DebugBridge.start()
        }
    }
}
