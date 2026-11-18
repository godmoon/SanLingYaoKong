package com.openremo.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TimerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            TimerScheduler.ACTION_FIRE -> {
                val address = intent.getStringExtra(TimerScheduler.EXTRA_ADDRESS).orEmpty()
                if (address.isNotBlank()) {
                    TimerService.fire(context, address)
                }
            }

            TimerScheduler.ACTION_CANCEL -> {
                val address = intent.getStringExtra(TimerScheduler.EXTRA_ADDRESS).orEmpty()
                if (address.isNotBlank()) {
                    TimerService.cancelTimer(context, address)
                }
            }

            Intent.ACTION_BOOT_COMPLETED -> {
                TimerScheduler.rescheduleAll(context)
            }
        }
    }
}
