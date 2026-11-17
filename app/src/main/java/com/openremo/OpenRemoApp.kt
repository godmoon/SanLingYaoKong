package com.openremo

import android.app.Application
import android.content.pm.ApplicationInfo
import com.openremo.data.RemoRepository
import com.openremo.debug.DebugBridge

class OpenRemoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RemoRepository.init(this)
        // 仅 Debug 包启动本地命令桥，release 包不会暴露调试端口
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            DebugBridge.start()
        }
    }
}
