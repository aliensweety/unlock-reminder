package com.suvye.unlockreminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * 宏软件式双保险接收器：
 * 1. 到点闹钟——服务被冻结/杀掉时，由系统闹钟把 fire 叫醒；
 * 2. 看门狗——周期巡检监控服务心跳，死了就拉活。
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!Prefs.isRunning(context)) return
        when (intent.action) {
            MonitorService.ACTION_FIRE_NOW ->
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, MonitorService::class.java)
                        .setAction(MonitorService.ACTION_FIRE_NOW)
                )
            MonitorService.ACTION_WATCHDOG -> {
                val stale = System.currentTimeMillis() - Prefs.lastHeartbeat(context) > 3 * 60_000L
                if (stale) {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, MonitorService::class.java)
                    )
                }
            }
        }
    }
}
