package com.suvye.unlockreminder

import android.Manifest
import android.app.AlarmManager
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
import android.net.Uri
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MonitorService : Service() {

    companion object {
        const val ACTION_START_ROUND = "com.suvye.unlockreminder.action.START_ROUND"
        const val ACTION_CANCEL_ROUND = "com.suvye.unlockreminder.action.CANCEL_ROUND"
        const val ACTION_STOP_MONITOR = "com.suvye.unlockreminder.action.STOP_MONITOR"
        const val ACTION_FIRE_NOW = "com.suvye.unlockreminder.action.FIRE_NOW"
        const val ACTION_WATCHDOG = "com.suvye.unlockreminder.action.WATCHDOG"

        /** 诊断用：临时覆盖本轮间隔（秒），>0 时生效 */
        const val EXTRA_INTERVAL_OVERRIDE = "interval_override"

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
    private var scheduledRoundId = 0L
    private var receiverRegistered = false
    private var userStop = false

    /** 心跳：看门狗闹钟据此判断服务是否被冻/被杀 */
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            Prefs.setLastHeartbeat(this@MonitorService, System.currentTimeMillis())
            handler.postDelayed(this, 60_000L)
        }
    }

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
        Prefs.setLastHeartbeat(this, System.currentTimeMillis())
        handler.postDelayed(heartbeatRunnable, 60_000L)
        scheduleWatchdog()
        recoverRoundIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_ROUND -> startRound(intent.getLongExtra(EXTRA_INTERVAL_OVERRIDE, 0L))
            ACTION_CANCEL_ROUND -> cancelRound()
            ACTION_FIRE_NOW -> {
                // 精确闹钟叫醒：服务侧做去重，闹钟与 Handler 谁先到都只 fire 一次
                handler.removeCallbacks(fireRunnable)
                fire()
            }
            ACTION_STOP_MONITOR -> {
                userStop = true
                cancelRound()
                Prefs.setRunning(this, false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(fireRunnable)
        handler.removeCallbacks(heartbeatRunnable)
        overlayReminder.close()
        // 通知无论如何都撤掉；本轮状态只在用户主动关闭时清，
        // 系统回收服务（会走 onDestroy 再 sticky 重启）时保留，交给 recoverRoundIfNeeded 恢复
        val nm = NotificationManagerCompat.from(this)
        nm.cancel(NOTIF_COUNTDOWN)
        nm.cancel(NOTIF_ALARM)
        if (receiverRegistered) unregisterReceiver(screenReceiver)
        if (userStop) {
            cancelWatchdog()
            Prefs.setRunning(this, false)
            Prefs.clearRound(this)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 服务被系统杀死后重建（走不到 onDestroy）：恢复未到期的一轮，过期脏数据直接清理 */
    private fun recoverRoundIfNeeded() {
        val start = Prefs.roundStart(this)
        if (start <= 0L) return
        val remaining = effectiveIntervalSeconds() * 1000L - (System.currentTimeMillis() - start)
        if (remaining > 0L) {
            roundStart = start
            scheduledRoundId = start
            handler.postDelayed(fireRunnable, remaining)
            showCountdownNotification(remaining)
        } else if (-remaining <= 120_000L) {
            // 精确闹钟刚叫醒、deadline 刚过（时序抖动在宽限内）：立即补 fire，别把这一轮吃掉
            roundStart = start
            scheduledRoundId = start
            handler.post(fireRunnable)
        } else {
            // 过期太久（长时间死亡后的陈旧轮）：丢弃
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

    private fun startRound(overrideSeconds: Long = 0L) {
        roundStart = System.currentTimeMillis()
        scheduledRoundId = roundStart
        Prefs.setRoundStart(this, roundStart)
        Prefs.setRoundIntervalOverride(this, if (overrideSeconds > 0) overrideSeconds else 0L)
        handler.removeCallbacks(fireRunnable)
        // 清掉上一轮可能残留的到点通知
        NotificationManagerCompat.from(this).cancel(NOTIF_ALARM)
        val seconds = if (overrideSeconds > 0) overrideSeconds else Prefs.intervalSeconds(this)
        val intervalMs = seconds * 1000L
        handler.postDelayed(fireRunnable, intervalMs)
        // 宏软件同款双保险：进程被冻/被杀时由系统闹钟叫醒到点
        val am = getSystemService(AlarmManager::class.java)
        if (am != null) {
            val fireAt = roundStart + intervalMs
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, fireAlarmPending())
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, fireAlarmPending())
            }
        }
        showCountdownNotification(intervalMs)
    }

    /** 本轮实际间隔：诊断测试的临时覆盖优先 */
    private fun effectiveIntervalSeconds(): Long {
        val ov = Prefs.roundIntervalOverride(this)
        return if (ov > 0) ov else Prefs.intervalSeconds(this)
    }

    private fun cancelRound() {
        handler.removeCallbacks(fireRunnable)
        overlayReminder.close()
        roundStart = 0L
        scheduledRoundId = 0L
        Prefs.clearRound(this)
        Prefs.setRoundIntervalOverride(this, 0L)
        getSystemService(AlarmManager::class.java)?.cancel(fireAlarmPending())
        val nm = NotificationManagerCompat.from(this)
        nm.cancel(NOTIF_COUNTDOWN)
        nm.cancel(NOTIF_ALARM)
    }

    private fun fireAlarmPending(): PendingIntent = PendingIntent.getBroadcast(
        this, 10,
        Intent(this, AlarmReceiver::class.java).setAction(ACTION_FIRE_NOW),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun watchdogPending(): PendingIntent = PendingIntent.getBroadcast(
        this, 11,
        Intent(this, AlarmReceiver::class.java).setAction(ACTION_WATCHDOG),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun scheduleWatchdog() {
        val am = getSystemService(AlarmManager::class.java) ?: return
        am.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 5 * 60_000L,
            15 * 60_000L,
            watchdogPending()
        )
    }

    private fun cancelWatchdog() {
        getSystemService(AlarmManager::class.java)?.cancel(watchdogPending())
    }

    private fun fire() {
        // 去重：Handler 与精确闹钟谁先到都只 fire 一次
        if (roundStart == 0L || roundStart != scheduledRoundId) return
        scheduledRoundId = 0L
        val now = System.currentTimeMillis()
        val elapsed = now - roundStart
        val usage = ArrayList(UsageStatsHelper.topUsage(this, roundStart, now))
        Prefs.clearRound(this)
        Prefs.setRoundIntervalOverride(this, 0L)
        Prefs.setLastFireAt(this, now)

        NotificationManagerCompat.from(this).cancel(NOTIF_COUNTDOWN)

        // ── 主路径：悬浮窗全屏浮层（不经过 Activity 启动栈，ROM 不拦）──────────
        val overlayGranted = Settings.canDrawOverlays(this)
        if (overlayGranted) {
            val (ok, error) = overlayReminder.show(elapsed, usage)
            if (ok) {
                markFire("全局浮层 ✓")
                return
            }
            markFire("浮层失败($error)，已走兜底")
        } else {
            markFire("仅通知（未开悬浮窗）")
        }

        // ── 兜底1：直接起页面（有悬浮窗权限时多数 ROM 允许；MIUI 还需「后台弹出界面」）──
        val content = Intent(this, ReminderActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(ReminderActivity.EXTRA_ELAPSED, elapsed)
            .putStringArrayListExtra(ReminderActivity.EXTRA_USAGE, usage)
        try {
            startActivity(content)
        } catch (e: Exception) {
            markFire(Prefs.lastFireResult(this) + " · 页面兜底被拦(" + e.javaClass.simpleName + ")")
        }

        // ── 兜底2：FSI 高优通知（亮屏解锁态是横幅，灭屏/锁屏才真全屏）────────────
        val fullScreenPending = PendingIntent.getActivity(
            this, 0, content,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CH_ALARM)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(getString(R.string.time_up))
            .setContentText(getString(R.string.tap_to_view))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            // 亮屏解锁态 FSI 会降级成横幅，点按横幅走 contentIntent 进提醒页
            .setContentIntent(fullScreenPending)
            .setFullScreenIntent(fullScreenPending, true)

        if (!overlayGranted) {
            // 全局弹窗的唯一合法前提就是悬浮窗权限：在到点通知里给一键修复入口
            val fixPending = PendingIntent.getActivity(
                this, 2,
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, getString(R.string.fix_overlay), fixPending)
        }
        val stopPending = PendingIntent.getService(
            this, 3,
            Intent(this, MonitorService::class.java).setAction(ACTION_CANCEL_ROUND),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(0, getString(R.string.btn_stop_short), stopPending)
        safeNotify(NOTIF_ALARM, builder.build())
    }

    /** 把到点链路的实际走向记到主界面（自诊断：用户无需 adb 即可反馈卡在哪一步） */
    private fun markFire(result: String) {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        Prefs.setLastFire(this, "$result · $time")
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
