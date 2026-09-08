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
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ContextThemeWrapper
import android.view.WindowManager
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
    private var keepAliveDot: View? = null
    private var roundStart = 0L
    private var scheduledRoundId = 0L
    private var receiverRegistered = false
    private var userStop = false
    private var pendingElapsed = 0L
    private var pendingUsage: List<String> = emptyList()
    private var pendingLateTag = ""
    private var fireAtWall = 0L
    private var fireAtElapsed = 0L
    private var pendingHeading = ""
    private var statsCache: Map<String, Long> = emptyMap()
    private val remindedApps = HashSet<String>()

    /** 心跳：看门狗闹钟据此判断服务是否被冻/被杀 */
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            Prefs.setLastHeartbeat(this@MonitorService, System.currentTimeMillis())
            handler.postDelayed(this, 60_000L)
        }
    }

    /** 倒计时通知周期刷新：剩余时间文本 + 绝对到点时刻（ROM 不渲染走字控件时靠它） */
    private val countdownUpdater = object : Runnable {
        override fun run() {
            if (roundStart == 0L || scheduledRoundId == 0L || fireAtElapsed == 0L) return
            // 剩余时间一律用单调时钟（elapsedRealtime）计算，墙钟会被 NTP 跳动
            val remainingMs = fireAtElapsed - SystemClock.elapsedRealtime()
            if (remainingMs <= 0L) return
            updateCountdownNotification(remainingMs)
            handler.postDelayed(this, if (remainingMs <= 15_000L) 1_000L else 10_000L)
        }
    }

    /**
     * 使用事件入账有延迟（ColorOS 可滞后数秒~更久），10 秒的短轮次到点时
     * 最近事件可能还没入账 → 一次性查询会漏掉整轮。
     * 对策：轮次进行中每 2 秒滚动重算并缓存，到点时取「新鲜 ∨ 缓存」中更全的一份。
     */
    private val statsPoller = object : Runnable {
        override fun run() {
            if (roundStart == 0L || scheduledRoundId == 0L) return
            val totals = UsageStatsHelper.totalsFor(this@MonitorService, roundStart, System.currentTimeMillis())
            statsCache = totals
            if (RulesStore.rulesActive(this@MonitorService)) updateRulesNotification(totals)
            checkPerAppRules(totals)
            handler.postDelayed(this, 2_000L)
        }
    }

    /** 分应用规则：本轮内某应用累计使用 ≥ 生效阈值 → 全屏提醒（每应用每轮一次） */
    private fun checkPerAppRules(totals: Map<String, Long>) {
        if (roundStart == 0L) return
        if (System.currentTimeMillis() - roundStart < 3_000L) return
        for ((pkg, ms) in totals) {
            if (pkg in remindedApps) continue
            val sec = RulesStore.effectiveSec(this, pkg)
            if (sec <= 0 || ms < sec * 1000L) continue
            remindedApps.add(pkg)
            StatsStore.onReminder(this)
            val label = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) {
                pkg
            }
            android.util.Log.d("URFire", "perAppRule hit $label ms=$ms sec=$sec")
            presentReminder("$label 用了不少", ms, mapOf(pkg to ms))
            break
        }
    }

    private val fireRunnable = Runnable { fire() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_USER_PRESENT -> {
                    StatsStore.onUnlock(context)
                    startRound()
                }
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
        addKeepAliveDot()
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
        handler.removeCallbacks(countdownUpdater)
        handler.removeCallbacks(statsPoller)
        overlayReminder.close()
        removeKeepAliveDot()
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
            handler.postDelayed(statsPoller, 2_000L)
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
        remindedApps.clear()
        Prefs.setRoundStart(this, roundStart)
        Prefs.setRoundIntervalOverride(this, if (overrideSeconds > 0) overrideSeconds else 0L)
        handler.removeCallbacks(fireRunnable)
        // 清掉上一轮可能残留的到点通知
        NotificationManagerCompat.from(this).cancel(NOTIF_ALARM)
        val seconds = if (overrideSeconds > 0) overrideSeconds else Prefs.intervalSeconds(this)
        val intervalMs = seconds * 1000L
        handler.postDelayed(fireRunnable, intervalMs)
        statsCache = emptyMap()
        handler.removeCallbacks(statsPoller)
        handler.postDelayed(statsPoller, 2_000L)

        // 规则模式（设置了分应用规则）下，全局倒计时让位：到点弹窗由规则引擎驱动，
        // 通知栏直接显示每个规则应用的剩余倒计时
        if (overrideSeconds <= 0 && RulesStore.rulesActive(this)) {
            updateRulesNotification(UsageStatsHelper.totalsFor(this, roundStart, System.currentTimeMillis()))
            return
        }

        // 宏软件同款双保险：setAlarmClock 被系统视为真实闹钟，ColorOS/MIUI 不做闹钟对齐延迟，
        // 且无需 SCHEDULE_EXACT_ALARM 权限；进程被速冻时由系统闹钟破冻叫醒到点
        val am = getSystemService(AlarmManager::class.java)
        if (am != null) {
            val fireAt = roundStart + intervalMs
            val showIntent = PendingIntent.getActivity(
                this, 4, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(fireAt, showIntent), fireAlarmPending())
        }
        showCountdownNotification(intervalMs)
    }

    /** 规则模式下的通知：逐行显示每个规则应用的剩余倒计时（每 2 秒随轮询刷新） */
    private fun updateRulesNotification(totals: Map<String, Long>) {
        val rules = RulesStore.overrides(this)
        val sb = StringBuilder()
        for (r in rules) {
            val used = totals[r.pkg] ?: 0L
            val remaining = (r.thresholdSec * 1000L - used).coerceAtLeast(0L)
            sb.append("${r.label} 剩余 ${UsageStatsHelper.formatDuration(remaining)}\n")
        }
        if (rules.isEmpty() && RulesStore.defaultSec(this) > 0) {
            sb.append("全局默认 · ${UsageStatsHelper.formatDuration(RulesStore.defaultSec(this) * 1000L)} 后提醒\n")
        }
        if (sb.isEmpty()) return

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MonitorService::class.java).setAction(ACTION_CANCEL_ROUND),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val views = RemoteViews(packageName, R.layout.notification_countdown)
        views.setTextViewText(R.id.notifTime, sb.toString().trim())
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

    /** 本轮实际间隔：诊断测试的临时覆盖优先 */
    private fun effectiveIntervalSeconds(): Long {
        val ov = Prefs.roundIntervalOverride(this)
        return if (ov > 0) ov else Prefs.intervalSeconds(this)
    }

    private fun cancelRound() {
        handler.removeCallbacks(fireRunnable)
        handler.removeCallbacks(countdownUpdater)
        handler.removeCallbacks(statsPoller)
        statsCache = emptyMap()
        overlayReminder.close()
        roundStart = 0L
        scheduledRoundId = 0L
        fireAtWall = 0L
        fireAtElapsed = 0L
        Prefs.clearRound(this)
        Prefs.setRoundIntervalOverride(this, 0L)
        if (statsCache.isNotEmpty()) StatsStore.addAppUsage(this, statsCache)
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

    /**
     * 保活小浮标（MacroDroid 浮字的缩小版）：监控期间常驻一颗半透明小圆点。
     * 挂着「可见窗口」的进程通常不进 ROM 速冻名单——这是防冻结最省事的形态级手段。
     * 不可触摸、不抢焦点，仅 12dp，位于屏幕左上角。
     */
    private fun addKeepAliveDot() {
        if (keepAliveDot != null) return
        if (!Settings.canDrawOverlays(this)) return
        val wm = getSystemService(WindowManager::class.java) ?: return
        val v = View(this)
        v.setBackgroundResource(R.drawable.keepalive_dot)
        val density = resources.displayMetrics.density
        val params = WindowManager.LayoutParams(
            (12 * density).toInt(),
            (12 * density).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = (6 * density).toInt()
        params.y = (6 * density).toInt()
        try {
            wm.addView(v, params)
            keepAliveDot = v
        } catch (_: Exception) {
        }
    }

    private fun removeKeepAliveDot() {
        val v = keepAliveDot ?: return
        keepAliveDot = null
        try {
            getSystemService(WindowManager::class.java)?.removeView(v)
        } catch (_: Exception) {
        }
    }

    private fun fire() {
        // 去重：Handler 与精确闹钟谁先到都只 fire 一次
        if (roundStart == 0L || roundStart != scheduledRoundId) return
        scheduledRoundId = 0L
        val now = System.currentTimeMillis()
        // 迟到检测：到点回调比计划晚 15 秒以上 = 进程被 ROM 冻结过（真机诊断关键信号）
        val lateMs = now - (roundStart + effectiveIntervalSeconds() * 1000L)
        pendingLateTag =
            if (lateMs > 15_000L) " · 迟到${lateMs / 1000L}秒（后台被冻结过）" else ""
        val elapsed = now - roundStart
        // 到点统计：新鲜查询 ∨ 轮询缓存按包取最大（对抗事件入账延迟）
        val fresh = UsageStatsHelper.totalsFor(this, roundStart, now)
        val merged = HashMap<String, Long>()
        for ((k, v) in fresh) merged[k] = maxOf(v, statsCache[k] ?: 0L)
        for ((k, v) in statsCache) merged.putIfAbsent(k, v)
        statsCache = emptyMap()
        handler.removeCallbacks(statsPoller)
        Prefs.clearRound(this)
        Prefs.setRoundIntervalOverride(this, 0L)
        Prefs.setLastFireAt(this, now)

        // 统计累计：轮次 +1，各应用本轮时长入账
        StatsStore.onRoundEnd(this)
        StatsStore.addAppUsage(this, merged)

        NotificationManagerCompat.from(this).cancel(NOTIF_COUNTDOWN)
        handler.removeCallbacks(countdownUpdater)
        presentReminder(getString(R.string.time_up), elapsed, merged)
    }

    /** 统一提醒呈现链：悬浮窗浮层 → 页面兜底 → FSI 通知。全局到点与分应用触发共用。 */
    private fun presentReminder(heading: String, elapsed: Long, totals: Map<String, Long>) {
        pendingElapsed = elapsed
        pendingUsage = UsageStatsHelper.formatTop(totals, this)
        pendingHeading = heading

        // ── 主路径：悬浮窗全屏浮层。挂载成功≠显示成功（ColorOS 会静默吞窗），
        //    OverlayReminder 500ms 后异步验证，成败经 onOverlayShown/onOverlayFailed 回调 ──
        val overlayGranted = Settings.canDrawOverlays(this)
        android.util.Log.d("URFire", "present heading=$heading elapsed=$elapsed overlayGranted=$overlayGranted")
        if (overlayGranted && overlayReminder.show(heading, elapsed, pendingUsage)) {
            markFire("浮层已挂载，验证中…" + pendingLateTag)
            return
        }
        markFire(if (overlayGranted) "浮层添加失败，走兜底" else "仅通知（未开悬浮窗）" + pendingLateTag)
        runFallback()
    }

    /** 兜底链：直接起页面（多数 ROM 允许，MIUI 需「后台弹出界面」）→ FSI 通知 */
    private fun runFallback() {
        val content = Intent(this, ReminderActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(ReminderActivity.EXTRA_HEADING, pendingHeading)
            .putExtra(ReminderActivity.EXTRA_ELAPSED, pendingElapsed)
            .putStringArrayListExtra(ReminderActivity.EXTRA_USAGE, ArrayList(pendingUsage))
        try {
            startActivity(content)
        } catch (e: Exception) {
            markFire(Prefs.lastFireResult(this) + " · 页面兜底被拦(" + e.javaClass.simpleName + ")")
        }

        // FSI 高优通知（亮屏解锁态是横幅，灭屏/锁屏才真全屏）
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

        if (!Settings.canDrawOverlays(this)) {
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

    /** 浮层异步验证通过：真·全局浮层 */
    fun onOverlayShown() {
        if (!Prefs.isRunning(this)) return
        android.util.Log.d("URFire", "onOverlayShown")
        markFire("全局浮层 ✓" + pendingLateTag)
    }

    /** 浮层异步验证失败（含不可聚焦重试后）：走兜底链 */
    fun onOverlayFailed(reason: String) {
        if (!Prefs.isRunning(this)) return
        android.util.Log.d("URFire", "onOverlayFailed reason=$reason")
        markFire("浮层失败($reason)，走兜底" + pendingLateTag)
        runFallback()
    }

    /** 把到点链路的实际走向记到主界面（自诊断：用户无需 adb 即可反馈卡在哪一步） */
    private fun markFire(result: String) {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        Prefs.setLastFire(this, "$result · $time")
    }

    /** 倒计时通知：RemoteViews 走字 + 周期刷新的剩余时间/绝对到点时刻（ROM 不渲染走字控件时仍可见） */
    private fun showCountdownNotification(intervalMs: Long) {
        fireAtWall = System.currentTimeMillis() + intervalMs
        fireAtElapsed = SystemClock.elapsedRealtime() + intervalMs
        handler.removeCallbacks(countdownUpdater)
        handler.postDelayed(countdownUpdater, 1_000L)
        updateCountdownNotification(intervalMs)
    }

    private fun updateCountdownNotification(remainingMs: Long) {
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MonitorService::class.java).setAction(ACTION_CANCEL_ROUND),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val views = RemoteViews(packageName, R.layout.notification_countdown)
        val untilText = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(fireAtWall))
        views.setTextViewText(
            R.id.notifTime,
            "剩余${UsageStatsHelper.formatDuration(remainingMs)} · $untilText 到点"
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
