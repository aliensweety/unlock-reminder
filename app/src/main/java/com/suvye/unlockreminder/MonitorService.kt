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
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.WindowManager
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
        const val ACTION_REFRESH_DOT = "com.suvye.unlockreminder.action.REFRESH_DOT"

        private const val CH_MONITOR = "monitor"
        private const val CH_ALARM = "alarm"

        private const val NOTIF_MONITOR = 1
        private const val NOTIF_COUNTDOWN = 2
        const val NOTIF_ALARM = 3

        /** 连续这么多次轮询两源都无变化（×2 秒），判定统计被系统限制，通知栏告警 */
        private const val STALE_LIMIT = 10

        /** 默认倒计时在统计/提醒记录里的伪包名 */
        const val DEFAULT_PKG = "__default__"
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
    private var pendingHeading = ""
    private var statsCache: Map<String, Long> = emptyMap()
    private var statsStale = 0

    /** 桌面包名集合（60 秒缓存），前台是桌面 = 「没开应用」，通知栏回默认文案 */
    private var homePkgs: Set<String> = emptySet()
    private var homeReadAt = 0L

    /** 默认倒计时入账：亮屏 + 前台不在已选应用（2 秒粒度） */
    private var screenOn = true
    private var lastTickElapsed = 0L
    private var defaultUsedMs = 0L

    /** 循环提醒重置基准：pkg → 该应用提醒时刻的 raw 累计毫秒。effective = raw − credited。 */
    private val credited = HashMap<String, Long>()

    /** 当前浮层对应的应用（「知道了」时把浮层显示期间的增长一并 credited） */
    private var hitPkg: String? = null

    /** 当前浮层是不是默认倒计时触发的（「知道了」时把浮层期间的增长一并清零） */
    private var defaultHitPending = false

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            Prefs.setLastHeartbeat(this@MonitorService, System.currentTimeMillis())
            handler.postDelayed(this, 60_000L)
        }
    }

    /**
     * 每 2 秒全量重算（双源：无障碍账本 ∨ queryEvents，见 UsageStatsHelper.totalsFor），
     * 再减掉各应用已提醒掉的时长（循环提醒：提醒一次从 0 重新计）。
     * statsCache 与通知、规则引擎、闹钟排程统一只看 effective 值。
     */
    private val statsPoller = object : Runnable {
        override fun run() {
            if (roundStart == 0L || scheduledRoundId == 0L) return
            refreshStats()
            accrueDefault()
            checkPerAppRules()
            checkDefaultRule()
            updateMonitorNotification(statsCache)
            handler.postDelayed(this, 2_000L)
        }
    }

    /** 默认倒计时入账：亮屏且前台不是已选应用的时间都往默认账上记（桌面/未选应用/自己界面） */
    private fun accrueDefault() {
        val now = SystemClock.elapsedRealtime()
        val last = lastTickElapsed
        lastTickElapsed = now
        if (last == 0L || !screenOn) return
        val fg = ForegroundLedger.currentForeground()
        if (fg != null && RulesStore.effectiveSec(this, fg) > 0L) return
        // 单笔封顶 60 秒：进程被冻很久后醒来，避免一笔记入超长未知时段
        val dt = (now - last).coerceIn(0L, 60_000L)
        defaultUsedMs += dt
        Prefs.setDefaultUsed(this, roundStart, defaultUsedMs)
    }

    /** 默认倒计时：不在已选应用的累计 ≥ 默认时长 → 弹一次，从 0 重新计（循环） */
    private fun checkDefaultRule() {
        if (roundStart == 0L || overlayReminder.isShowing()) return
        val sec = Prefs.defaultSec(this)
        if (sec <= 0L) return
        if (defaultUsedMs < sec * 1000L) return
        if (System.currentTimeMillis() - roundStart < 3_000L) return
        val used = defaultUsedMs
        defaultUsedMs = 0L
        Prefs.setDefaultUsed(this, roundStart, 0L)
        StatsStore.onReminder(this, DEFAULT_PKG)
        android.util.Log.d("URFire", "defaultRule hit used=$used sec=$sec")
        defaultHitPending = true
        presentReminder(getString(R.string.rules_default), used, statsCache)
    }

    private fun refreshStats() {
        val raw = UsageStatsHelper.totalsFor(this@MonitorService, roundStart, roundStart, System.currentTimeMillis())
        statsStale = if (raw == lastRaw) statsStale + 1 else 0
        lastRaw = raw
        statsCache = raw
        if (credited.isNotEmpty()) {
            val eff = HashMap<String, Long>()
            for ((k, v) in raw) eff[k] = (v - (credited[k] ?: 0L)).coerceAtLeast(0L)
            statsCache = eff
        }
    }

    private var lastRaw: Map<String, Long> = emptyMap()

    /** 分应用循环提醒：effective 累计 ≥ 阈值 → 弹一次并把该应用从 0 重新计。解锁清空重计。 */
    private fun checkPerAppRules() {
        if (roundStart == 0L) return
        if (overlayReminder.isShowing()) return
        if (System.currentTimeMillis() - roundStart < 3_000L) return
        for ((pkg, ms) in statsCache) {
            val sec = RulesStore.effectiveSec(this, pkg)
            if (sec <= 0 || ms < sec * 1000L) continue
            credited[pkg] = ms + (credited[pkg] ?: 0L)
            Prefs.setCredited(this, roundStart, credited)
            StatsStore.onReminder(this, pkg)
            statsCache = statsCache.toMutableMap().apply { put(pkg, 0L) }
            val label = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) {
                pkg
            }
            android.util.Log.d("URFire", "perAppRule hit $label ms=$ms sec=$sec credited=${credited[pkg]}")
            hitPkg = pkg
            defaultHitPending = false
            presentReminder(label, ms, statsCache)
            break
        }
    }

    /** 计时语义：开启开关即开始计时；解锁只是把各应用时间重置（灭屏不清轮）。 */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_USER_PRESENT -> {
                    StatsStore.onUnlock(context)
                    startRound()
                }
                Intent.ACTION_SCREEN_OFF -> screenOn = false
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    lastTickElapsed = SystemClock.elapsedRealtime()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ForegroundLedger.init(this)
        createChannels()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
        // 覆盖安装后清掉旧版第二条倒计时通知
        NotificationManagerCompat.from(this).cancel(NOTIF_COUNTDOWN)
        pushForeground(buildMonitorNotification(emptyMap()))
        addKeepAliveDot()
        Prefs.setRunning(this, true)
        Prefs.setLastHeartbeat(this, System.currentTimeMillis())
        handler.postDelayed(heartbeatRunnable, 60_000L)
        scheduleWatchdog()
        recoverRoundIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_ROUND -> startRound()
            ACTION_CANCEL_ROUND -> cancelRound()
            ACTION_FIRE_NOW -> pokeRules()
            ACTION_REFRESH_DOT -> {
                removeKeepAliveDot()
                addKeepAliveDot()
            }
            ACTION_STOP_MONITOR -> {
                userStop = true
                cancelRound()
                Prefs.setRunning(this, false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> if (roundStart == 0L && Prefs.isRunning(this)) {
                // 开启提醒（开关/开机自启/覆盖安装）即开始计时，不等解锁
                startRound()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(heartbeatRunnable)
        handler.removeCallbacks(statsPoller)
        overlayReminder.close()
        removeKeepAliveDot()
        ForegroundLedger.endRound()
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

    /**
     * 服务（重）建：开关开着就一直有轮——有轮恢复轮，没轮立即开新轮。
     * 解锁负责重置，灭屏不再清轮。
     */
    private fun recoverRoundIfNeeded() {
        val start = Prefs.roundStart(this)
        if (start <= 0L) {
            if (Prefs.isRunning(this)) {
                startRound()
            } else {
                updateMonitorNotification()
            }
            return
        }
        roundStart = start
        scheduledRoundId = start
        credited.clear()
        credited.putAll(Prefs.credited(this, start))
        defaultUsedMs = Prefs.defaultUsed(this, start)
        lastTickElapsed = SystemClock.elapsedRealtime()
        ForegroundLedger.startRound(start, SystemClock.elapsedRealtime())
        handler.removeCallbacks(statsPoller)
        handler.post(statsPoller)
        pokeRules()
    }

    /** 「知道了」：关掉浮层。浮层盖着时底层应用其实还在 resumed 继续累计，
     *  把这段增长一并 credited，该应用才真正从 0 重新计。 */
    fun onOverlayConfirm() {
        overlayReminder.close()
        hitPkg?.let { pkg ->
            val raw = UsageStatsHelper.totalsFor(this, roundStart, roundStart, System.currentTimeMillis())
            val rawMs = raw[pkg] ?: 0L
            if (rawMs > (credited[pkg] ?: 0L)) credited[pkg] = rawMs
            Prefs.setCredited(this, roundStart, credited)
            statsCache = statsCache.toMutableMap().apply { put(pkg, 0L) }
        }
        hitPkg = null
        if (defaultHitPending) {
            defaultHitPending = false
            defaultUsedMs = 0L
            if (roundStart > 0L) Prefs.setDefaultUsed(this, roundStart, 0L)
        }
        updateMonitorNotification(statsCache)
    }

    /** 「结束本轮」：清掉本轮，等下次解锁。 */
    fun onOverlayCancel() {
        hitPkg = null
        overlayReminder.close()
        cancelRound()
    }

    /** 解锁 = 每个应用重新计时（循环提醒的 credited 也一并清空）。只提醒，不限制。 */
    private fun startRound() {
        roundStart = System.currentTimeMillis()
        scheduledRoundId = roundStart
        credited.clear()
        Prefs.setCredited(this, roundStart, emptyMap())
        statsStale = 0
        lastRaw = emptyMap()
        defaultUsedMs = 0L
        screenOn = true
        lastTickElapsed = SystemClock.elapsedRealtime()
        Prefs.setDefaultUsed(this, roundStart, 0L)
        Prefs.setRoundStart(this, roundStart)
        Prefs.setRoundIntervalOverride(this, 0L)
        NotificationManagerCompat.from(this).cancel(NOTIF_ALARM)
        statsCache = emptyMap()
        ForegroundLedger.startRound(roundStart, SystemClock.elapsedRealtime())
        handler.removeCallbacks(statsPoller)
        handler.post(statsPoller)
        updateMonitorNotification(emptyMap())
        scheduleNextRuleAlarm(emptyMap())
    }

    /** 精确闹钟叫醒后核对规则（进程被冻时靠它破冻）。 */
    private fun pokeRules() {
        if (roundStart == 0L) {
            updateMonitorNotification()
            return
        }
        refreshStats()
        accrueDefault()
        checkPerAppRules()
        checkDefaultRule()
        updateMonitorNotification(statsCache)
        scheduleNextRuleAlarm(statsCache)
    }

    /**
     * 本轮内按下一次闹钟：已在用的应用按剩余时间，否则最多 60 秒巡检一次。
     * 进程活着时 2 秒轮询已经够准，闹钟只防冻结。
     */
    private fun scheduleNextRuleAlarm(totals: Map<String, Long>) {
        if (roundStart == 0L) {
            getSystemService(AlarmManager::class.java)?.cancel(fireAlarmPending())
            return
        }
        var delay = 60_000L
        for (r in RulesStore.overrides(this)) {
            val used = totals[r.pkg] ?: 0L
            if (used <= 0L) continue
            val remaining = r.thresholdSec * 1000L - used
            if (remaining > 0L) delay = minOf(delay, remaining)
        }
        val defSec = Prefs.defaultSec(this)
        if (defSec > 0L && roundStart > 0L) {
            val remaining = defSec * 1000L - Prefs.defaultUsed(this, roundStart)
            if (remaining in 1 until delay) delay = remaining
        }
        delay = delay.coerceIn(2_000L, 60_000L)
        val am = getSystemService(AlarmManager::class.java) ?: return
        val fireAt = System.currentTimeMillis() + delay
        val pending = fireAlarmPending()
        try {
            val showIntent = PendingIntent.getActivity(
                this, 4, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(fireAt, showIntent), pending)
        } catch (_: SecurityException) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pending)
            } catch (_: Exception) {
            }
        }
    }

    private fun cancelRound() {
        val snapshot = statsCache
        val hadRound = roundStart > 0L
        handler.removeCallbacks(statsPoller)
        statsCache = emptyMap()
        statsStale = 0
        lastRaw = emptyMap()
        defaultUsedMs = 0L
        overlayReminder.close()
        roundStart = 0L
        scheduledRoundId = 0L
        credited.clear()
        ForegroundLedger.endRound()
        Prefs.clearRound(this)
        Prefs.setRoundIntervalOverride(this, 0L)
        if (snapshot.isNotEmpty()) StatsStore.addAppUsage(this, snapshot)
        if (hadRound) StatsStore.onRoundEnd(this)
        getSystemService(AlarmManager::class.java)?.cancel(fireAlarmPending())
        val nm = NotificationManagerCompat.from(this)
        nm.cancel(NOTIF_COUNTDOWN)
        nm.cancel(NOTIF_ALARM)
        if (Prefs.isRunning(this) && !userStop) updateMonitorNotification()
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
     * 防冻浮标：ColorOS 把「有可见窗口」的进程排除在速冻之外。
     * v0.8.2 改成 1px 全透明后统计空转复发，v0.9.0 默认回 4dp 低透明微点；
     * 嫌碍眼可在设置开「隐蔽模式」（回到 1px 全透明，保活效果自担）。
     */
    private fun addKeepAliveDot() {
        if (keepAliveDot != null) return
        if (!Settings.canDrawOverlays(this)) return
        val wm = getSystemService(WindowManager::class.java) ?: return
        val density = resources.displayMetrics.density
        val stealth = Prefs.keepaliveStealth(this)
        val size = if (stealth) 1 else (4 * density).toInt().coerceAtLeast(1)
        val v = View(this)
        if (stealth) {
            v.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            v.alpha = 0f
        } else {
            v.setBackgroundResource(R.drawable.keepalive_dot)
            v.alpha = 0.15f
        }
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 0
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

    private fun presentReminder(heading: String, elapsed: Long, totals: Map<String, Long>) {
        pendingElapsed = elapsed
        pendingUsage = UsageStatsHelper.formatTop(totals, this)
        pendingHeading = heading

        val overlayGranted = Settings.canDrawOverlays(this)
        android.util.Log.d("URFire", "present heading=$heading elapsed=$elapsed overlayGranted=$overlayGranted")
        if (overlayGranted && overlayReminder.show(heading, elapsed, pendingUsage)) {
            markFire("浮层已挂载，验证中…" + pendingLateTag)
            return
        }
        markFire(if (overlayGranted) "浮层添加失败，走兜底" else "仅通知（未开悬浮窗）" + pendingLateTag)
        runFallback()
    }

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

        val fullScreenPending = PendingIntent.getActivity(
            this, 0, content,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CH_ALARM)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(pendingHeading.ifEmpty { getString(R.string.time_up) })
            .setContentText(getString(R.string.tap_to_view))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(fullScreenPending)
            .setFullScreenIntent(fullScreenPending, true)

        if (!Settings.canDrawOverlays(this)) {
            val fixPending = PendingIntent.getActivity(
                this, 2,
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, getString(R.string.fix_overlay), fixPending)
        }
        safeNotify(NOTIF_ALARM, builder.build())
    }

    fun onOverlayShown() {
        if (!Prefs.isRunning(this)) return
        android.util.Log.d("URFire", "onOverlayShown")
        markFire("全局浮层 ✓" + pendingLateTag)
    }

    fun onOverlayFailed(reason: String) {
        if (!Prefs.isRunning(this)) return
        android.util.Log.d("URFire", "onOverlayFailed reason=$reason")
        markFire("浮层失败($reason)，走兜底" + pendingLateTag)
        runFallback()
    }

    private fun markFire(result: String) {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        Prefs.setLastFire(this, "$result · $time")
    }

    private fun updateMonitorNotification(totals: Map<String, Long> = emptyMap()) {
        pushForeground(buildMonitorNotification(totals))
    }

    private fun isHome(pkg: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - homeReadAt > 60_000L) {
            homeReadAt = now
            homePkgs = try {
                packageManager.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
                ).mapNotNull { it.activityInfo?.packageName }.toSet()
            } catch (_: Exception) {
                emptySet()
            }
        }
        return pkg in homePkgs
    }

    /** 前台服务只有这一条通知：等待解锁，或列出每个应用剩余时间。 */
    private fun buildMonitorNotification(totals: Map<String, Long>): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CH_MONITOR)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openIntent)

        val rules = RulesStore.overrides(this)
        if (roundStart == 0L) {
            val text = if (rules.isEmpty()) {
                getString(R.string.monitor_need_apps)
            } else {
                getString(R.string.monitor_waiting)
            }
            return builder
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .build()
        }

        var lines = ArrayList<String>()
        if (Prefs.hideNotifDetails(this)) {
            // 设置里开了「隐藏通知详情」：只显示最简状态
            lines.add(getString(R.string.notif_monitoring))
        } else {
            // 跟随当前前台应用：在用已选应用就显示它的剩余；否则显示默认倒计时（永远有数在走）
            val fg = ForegroundLedger.currentForeground()
            val fgSec = if (fg != null && !isHome(fg)) RulesStore.effectiveSec(this, fg) else 0L
            val defSec = Prefs.defaultSec(this)
            if (fg != null && fgSec > 0L) {
                val remaining = (fgSec * 1000L - (totals[fg] ?: 0L)).coerceAtLeast(0L)
                val label = try {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(fg, 0)).toString()
                } catch (_: Exception) {
                    fg
                }
                lines.add(getString(R.string.notif_next_left, label, UsageStatsHelper.formatDurationShort(remaining)))
            } else if (defSec > 0L) {
                val used = if (roundStart > 0L) Prefs.defaultUsed(this, roundStart) else defaultUsedMs
                val remaining = (defSec * 1000L - used).coerceAtLeast(0L)
                lines.add(
                    getString(
                        R.string.notif_next_left,
                        getString(R.string.rules_default),
                        UsageStatsHelper.formatDurationShort(remaining)
                    )
                )
            } else {
                lines.add(getString(R.string.notif_monitoring))
            }
        }
        val a11yEnabledSomewhere = KeepAliveAccessibilityService.isEnabled(this)
        val a11yAlive = ForegroundLedger.connected
        var fixUsage = false
        var fixA11y = false
        if (statsStale >= STALE_LIMIT || !UsageStatsHelper.hasUsageAccess(this) || !a11yEnabledSomewhere || !a11yAlive) {
            // 统计链路受损：usage 被挂起 / 无障碍被关闭或断绑 / 两源全停。
            // 无障碍被整体关掉（覆盖安装后 ColorOS 常干）时前台跟随和实时计时都会失效，必须可见。
            lines = ArrayList(lines).apply { add(0, getString(R.string.notif_stats_warn)) }
            fixUsage = !UsageStatsHelper.hasUsageAccess(this)
            fixA11y = !a11yEnabledSomewhere || !a11yAlive
        }
        val text = when {
            lines.isEmpty() -> getString(R.string.monitor_need_apps)
            else -> lines.first()
        }
        builder.setContentTitle(getString(R.string.app_name)).setContentText(text)
        if (lines.size > 1) {
            val inbox = NotificationCompat.InboxStyle()
                .setBigContentTitle(getString(R.string.notif_remaining))
            for (line in lines.take(7)) inbox.addLine(line)
            if (lines.size > 7) inbox.addLine(getString(R.string.notif_more, lines.size - 7))
            builder.setStyle(inbox)
        } else if (lines.size == 1) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(lines[0]))
        }
        if (fixUsage) {
            val fixPending = PendingIntent.getActivity(
                this, 5,
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, getString(R.string.notif_fix_stats), fixPending)
        }
        if (fixA11y) {
            val fixPending = PendingIntent.getActivity(
                this, 6,
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, getString(R.string.notif_fix_a11y), fixPending)
        }
        return builder.build()
    }

    private fun pushForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIF_MONITOR,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_MONITOR, notification)
        }
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
        val alarmChannel = NotificationChannel(CH_ALARM, getString(R.string.ch_alarm), NotificationManager.IMPORTANCE_HIGH)
        alarmChannel.enableVibration(true)
        alarmChannel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(alarmChannel)
    }
}
