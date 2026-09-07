package com.suvye.unlockreminder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class MonitorService : Service() {

    companion object {
        const val ACTION_START_ROUND = "com.suvye.unlockreminder.action.START_ROUND"
        const val ACTION_CANCEL_ROUND = "com.suvye.unlockreminder.action.CANCEL_ROUND"

        private const val CH_MONITOR = "monitor"
        private const val CH_COUNTDOWN = "countdown"
        private const val CH_ALARM = "alarm"

        private const val NOTIF_MONITOR = 1
        private const val NOTIF_COUNTDOWN = 2
        const val NOTIF_ALARM = 3
    }

    private val handler = Handler(Looper.getMainLooper())
    private var roundStart = 0L
    private var receiverRegistered = false

    private val fireRunnable = Runnable { fire() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_USER_PRESENT -> startRound()
                Intent.ACTION_SCREEN_OFF -> cancelRound()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
        )
        receiverRegistered = true
        startForeground(NOTIF_MONITOR, buildMonitorNotification())
        Prefs.setRunning(this, true)

        // 服务被系统杀死后重启恢复机制：若之前有未完成的倒计时，继续接力
        val savedStart = Prefs.roundStart(this)
        if (savedStart > 0L) {
            val intervalMs = Prefs.intervalSeconds(this) * 1000L
            val deadline = savedStart + intervalMs
            val remaining = deadline - System.currentTimeMillis()
            if (remaining > 0L) {
                roundStart = savedStart
                handler.postDelayed(fireRunnable, remaining)
                showCountdownNotification(deadline)
            } else {
                Prefs.clearRound(this)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_ROUND -> startRound()
            ACTION_CANCEL_ROUND -> cancelRound()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        cancelRound()
        if (receiverRegistered) {
            unregisterReceiver(screenReceiver)
            receiverRegistered = false
        }
        Prefs.setRunning(this, false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startRound() {
        roundStart = System.currentTimeMillis()
        Prefs.setRoundStart(this, roundStart)
        handler.removeCallbacks(fireRunnable)
        val intervalMs = Prefs.intervalSeconds(this) * 1000L
        handler.postDelayed(fireRunnable, intervalMs)
        showCountdownNotification(roundStart + intervalMs)
    }

    private fun cancelRound() {
        handler.removeCallbacks(fireRunnable)
        roundStart = 0L
        Prefs.clearRound(this)
        val nm = NotificationManagerCompat.from(this)
        nm.cancel(NOTIF_COUNTDOWN)
        nm.cancel(NOTIF_ALARM)
    }

    private fun fire() {
        val now = System.currentTimeMillis()
        val elapsed = if (roundStart > 0L) now - roundStart else Prefs.intervalSeconds(this) * 1000L
        val usage = ArrayList(UsageStatsHelper.topUsage(this, roundStart, now))
        roundStart = 0L
        Prefs.clearRound(this)

        NotificationManagerCompat.from(this).cancel(NOTIF_COUNTDOWN)

        val content = Intent(this, ReminderActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(ReminderActivity.EXTRA_ELAPSED, elapsed)
            .putStringArrayListExtra(ReminderActivity.EXTRA_USAGE, usage)

        // 悬浮窗权限在手时，后台起 Activity 是被豁免的，直接全屏弹出
        if (Settings.canDrawOverlays(this)) {
            try {
                startActivity(content)
            } catch (_: Exception) {
            }
        }

        val fullScreenPending = PendingIntent.getActivity(
            this, 0, content,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CH_ALARM)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(getString(R.string.time_up))
            .setContentText(getString(R.string.tap_to_view))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setFullScreenIntent(fullScreenPending, true)
            .setContentIntent(fullScreenPending)
            .build()
        safeNotify(NOTIF_ALARM, notification)
    }

    private fun showCountdownNotification(fireAt: Long) {
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MonitorService::class.java).setAction(ACTION_CANCEL_ROUND),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CH_COUNTDOWN)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(getString(R.string.countdown_title))
            .setContentText(getString(R.string.countdown_body))
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(fireAt)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(stopIntent)
            .addAction(0, getString(R.string.btn_cancel), stopIntent)
            .build()
        safeNotify(NOTIF_COUNTDOWN, notification)
    }

    private fun buildMonitorNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH_MONITOR)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.monitor_hint))
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
    }

    private fun safeNotify(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            NotificationManagerCompat.from(this).notify(id, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_MONITOR, getString(R.string.ch_monitor), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_COUNTDOWN, getString(R.string.ch_countdown), NotificationManager.IMPORTANCE_LOW)
        )
        val alarmChannel = NotificationChannel(
            CH_ALARM,
            getString(R.string.ch_alarm),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 250, 500)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(alarmChannel)
    }
}
