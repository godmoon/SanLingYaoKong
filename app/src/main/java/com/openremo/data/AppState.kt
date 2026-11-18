package com.openremo.data

/**
 * 进程级的界面可见性标记，定时服务据此决定任务完成后是否断开 BLE，
 * 避免用户正在操作时被后台任务强制断链。
 */
object AppState {
    @Volatile
    var foreground: Boolean = false
}
