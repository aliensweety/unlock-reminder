package com.suvye.unlockreminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            if (Prefs.isRunning(context)) {
                try {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, MonitorService::class.java)
                    )
                } catch (_: Exception) {
                    // 部分 ROM 限制开机起前台服务：静默放弃，用户打开 App 时会自愈
                }
            }
        }
    }
}
