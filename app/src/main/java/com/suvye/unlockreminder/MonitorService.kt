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
import android.content.pm.ServiceInfo
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

        const val NOTIF_ALARM = 3

        private const val CH_MONITOR = "monitor"
        private const val CH_COUNTDOWN = "countdown2"
        private const val CH_ALARM = "alarm"

        private const val NOTIF_MONITOR = 1
        private const val NOTIF_COUNTDOWN = 2
    }

    private val handler = Handler(Looper.getMainLooper())
    private var roundStart = 0L
    private var roundArmed = false
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
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_EXPORTED
        )
        receiverRegistered = true
        startAsForeground()
        Prefs.setRunning(this, true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_ROUND -> startRound()
            ACTION_CANCEL_ROUND -> cancelRound()
            else -> restoreRoundIfNeeded()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(fireRunnable)
        roundArmed = false
        if (receiverRegistered) {
            unregisterReceiver(screenReceiver)
            receiverRegistered = false
        }
        NotificationManagerCompat.from(this).cancel(NOTIF_COUNTDOWN)
        NotificationManagerCompat.from(this).cancel(NOTIF_ALARM)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground() {
        val notification = buildMonitorNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIF_MONITOR,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_MONITOR, notification)
        }
    }

    private fun startRound() {
        handler.removeCallbacks(fireRunnable)
        roundStart = System.currentTimeMillis()
        val intervalMs = Prefs.intervalSeconds(this) * 1000L
        val fireAt = roundStart + intervalMs
        Prefs.setRound(this, roundStart, fireAt)
        roundArmed = true
        handler.postDelayed(fireRunnable, intervalMs)
        NotificationManagerCompat.from(this).cancel(NOTIF_ALARM)
        showCountdownNotification(fireAt)
    }

    private fun cancelRound() {
        handler.removeCallbacks(fireRunnable)
        roundStart = 0L
        roundArmed = false
        Prefs.clearRound(this)
        val nm = NotificationManagerCompat.from(this)
        nm.cancel(NOTIF_COUNTDOWN)
        nm.cancel(NOTIF_ALARM)
    }

    private fun restoreRoundIfNeeded() {
        if (roundArmed) return
        val start = Prefs.roundStart(this)
        if (start <= 0L) return
        val storedFireAt = Prefs.fireAt(this)
        val fireAt = if (storedFireAt > 0L) {
            storedFireAt
        } else {
            start + Prefs.intervalSeconds(this) * 1000L
        }
        roundStart = start
        val remaining = fireAt - System.currentTimeMillis()
        if (remaining <= 0L) {
            if (remaining > -120_000L) {
                fire()
            } else {
                Prefs.clearRound(this)
                roundStart = 0L
            }
        } else {
            roundArmed = true
            handler.removeCallbacks(fireRunnable)
            handler.postDelayed(fireRunnable, remaining)
            showCountdownNotification(fireAt)
        }
    }

    private fun fire() {
        roundArmed = false
        val now = System.currentTimeMillis()
        val elapsed = (now - roundStart).coerceAtLeast(0L)
        val usage = ArrayList(UsageStatsHelper.topUsage(this, roundStart, now))
        Prefs.clearRound(this)

        NotificationManagerCompat.from(this).cancel(NOTIF_COUNTDOWN)

        val content = Intent(this, ReminderActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(ReminderActivity.EXTRA_ELAPSED, elapsed)
            .putStringArrayListExtra(ReminderActivity.EXTRA_USAGE, usage)

        var launched = false
        if (Settings.canDrawOverlays(this)) {
            try {
                startActivity(content)
                launched = true
            } catch (_: Exception) {
            }
        }

        if (launched) return

        val fullScreenPending = PendingIntent.getActivity(
            this, 2, content,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CH_ALARM)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(getString(R.string.time_up))
            .setContentText(getString(R.string.tap_to_view))
            .setContentIntent(fullScreenPending)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setFullScreenIntent(fullScreenPending, true)
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
            .setShowWhen(true)
            .setWhen(fireAt)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(stopIntent)
            .addAction(R.drawable.ic_stat_timer, getString(R.string.countdown_stop_action), stopIntent)
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
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
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
        val countdown = NotificationChannel(
            CH_COUNTDOWN,
            getString(R.string.ch_countdown),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        nm.createNotificationChannel(countdown)
        nm.createNotificationChannel(
            NotificationChannel(CH_ALARM, getString(R.string.ch_alarm), NotificationManager.IMPORTANCE_HIGH)
        )
    }
}
