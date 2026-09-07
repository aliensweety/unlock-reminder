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
import android.os.SystemClock
import android.provider.Settings
import android.widget.RemoteViews
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
    private val overlayReminder = OverlayReminder(this)
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
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
        val monitorNotif = buildMonitorNotification()
        // Android 14 显式声明 specialUse 类型，避免 MissingForegroundServiceTypeException
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_MONITOR, monitorNotif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_MONITOR, monitorNotif)
        }
        Prefs.setRunning(this, true)
        recoverRoundIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_ROUND -> startRound()
            ACTION_CANCEL_ROUND -> cancelRound()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        overlayReminder.close()
        cancelRound()
        if (receiverRegistered) unregisterReceiver(screenReceiver)
        Prefs.setRunning(this, false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 服务被系统杀死后重建（走不到 onDestroy）：恢复未到期的一轮，过期脏数据直接清理 */
    private fun recoverRoundIfNeeded() {
        val start = Prefs.roundStart(this)
        if (start <= 0L) return
        val remaining = Prefs.intervalSeconds(this) * 1000L - (System.currentTimeMillis() - start)
        if (remaining > 0L) {
            roundStart = start
            handler.postDelayed(fireRunnable, remaining)
            showCountdownNotification(remaining)
        } else {
            Prefs.clearRound(this)
        }
    }

    /** 浮层「确定 · 再来一轮」回调 */
    fun onOverlayConfirm() {
        overlayReminder.close()
        startRound()
    }

    /** 浮层「取消 · 停止」/返回键回调 */
    fun onOverlayCancel() {
        overlayReminder.close()
        cancelRound()
    }

    private fun startRound() {
        roundStart = System.currentTimeMillis()
        Prefs.setRoundStart(this, roundStart)
        handler.removeCallbacks(fireRunnable)
        // 清掉上一轮可能残留的到点通知
        NotificationManagerCompat.from(this).cancel(NOTIF_ALARM)
        val intervalMs = Prefs.intervalSeconds(this) * 1000L
        handler.postDelayed(fireRunnable, intervalMs)
        showCountdownNotification(intervalMs)
    }

    private fun cancelRound() {
        handler.removeCallbacks(fireRunnable)
        overlayReminder.close()
        roundStart = 0L
        Prefs.clearRound(this)
        val nm = NotificationManagerCompat.from(this)
        nm.cancel(NOTIF_COUNTDOWN)
        nm.cancel(NOTIF_ALARM)
    }

    private fun fire() {
        val now = System.currentTimeMillis()
        val elapsed = now - roundStart
        val usage = ArrayList(UsageStatsHelper.topUsage(this, roundStart, now))
        Prefs.clearRound(this)

        NotificationManagerCompat.from(this).cancel(NOTIF_COUNTDOWN)

        // 主路径：悬浮窗全屏浮层（不经过 Activity 启动栈，ROM 不拦）
        if (Settings.canDrawOverlays(this) && overlayReminder.show(elapsed, usage)) {
            return
        }

        // 兜底 1：部分 ROM 允许后台直接起 Activity
        val content = Intent(this, ReminderActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(ReminderActivity.EXTRA_ELAPSED, elapsed)
            .putStringArrayListExtra(ReminderActivity.EXTRA_USAGE, usage)
        if (Settings.canDrawOverlays(this)) {
            try {
                startActivity(content)
            } catch (_: Exception) {
            }
        }

        // 兜底 2：全屏意图通知（亮屏解锁态是横幅，灭屏/锁屏才真全屏）
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
            .setAutoCancel(true)
            // 亮屏解锁态 FSI 会降级成横幅，点按横幅走 contentIntent 进提醒页
            .setContentIntent(fullScreenPending)
            .setFullScreenIntent(fullScreenPending, true)
            .build()
        safeNotify(NOTIF_ALARM, notification)
    }

    /** 倒计时通知：RemoteViews + Chronometer（DeskClock 同款），比系统模板的 chronometer extras 更耐 ROM 定制 */
    private fun showCountdownNotification(intervalMs: Long) {
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MonitorService::class.java).setAction(ACTION_CANCEL_ROUND),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val views = RemoteViews(packageName, R.layout.notification_countdown)
        views.setChronometer(
            R.id.notifChronometer,
            SystemClock.elapsedRealtime() + intervalMs,
            null,
            true
        )
        views.setTextViewText(R.id.notifHint, getString(R.string.countdown_body))
        val notification = NotificationCompat.Builder(this, CH_COUNTDOWN)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(views)
            .setCustomBigContentView(views)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(stopIntent)
            .addAction(0, getString(R.string.btn_stop_short), stopIntent)
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
        val alarmChannel = NotificationChannel(CH_ALARM, getString(R.string.ch_alarm), NotificationManager.IMPORTANCE_HIGH)
        alarmChannel.enableVibration(true)
        alarmChannel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(alarmChannel)
    }
}
