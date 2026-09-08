package com.suvye.unlockreminder

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * 守护无障碍服务：识别前台应用包名，不读取任何屏幕内容。
 *
 * 三个作用：
 * 1. 与系统 AccessibilityManagerService 保持常驻 Binder 绑定——带活跃无障碍绑定的
 *    进程在 ColorOS/MIUI 的速冻（cgroup freezer）白名单里，到点回调不会被冻结延迟，
 *    这正是宏软件（MacroDroid/Tasker）弹窗稳定的机制；
 * 2. 监听窗口切换事件，喂给 ForegroundLedger 做实时前台计时，摆脱对 usage 数据访问
 *    的依赖（ColorOS 会静默挂起后台应用的 usage 数据 →「时间不减」）；
 * 3. 每 3 秒主动探测焦点窗口：「解锁时已在前台」的应用不会再发窗口切换事件，
 *    被动等事件会漏掉它——主动探测保证前台是谁 3 秒内必然入账。
 */
class KeepAliveAccessibilityService : AccessibilityService() {

    private val probeHandler = Handler(Looper.getMainLooper())

    private val probeRunnable = object : Runnable {
        override fun run() {
            probeFocusedWindow()
            probeHandler.postDelayed(this, 3_000L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ForegroundLedger.init(applicationContext)
        ForegroundLedger.connected = true
        probeHandler.post(probeRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg.isEmpty()) return
        ForegroundLedger.onWindowChanged(pkg, SystemClock.elapsedRealtime())
    }

    /** 只取焦点窗口的包名喂账本，不遍历、不读取窗口内容 */
    private fun probeFocusedWindow() {
        try {
            val win = windows?.firstOrNull { it.isFocused } ?: return
            val pkg = win.root?.packageName?.toString()
            win.recycle()
            if (pkg.isNullOrEmpty()) return
            ForegroundLedger.onWindowChanged(pkg, SystemClock.elapsedRealtime())
        } catch (_: Exception) {
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        ForegroundLedger.connected = false
        probeHandler.removeCallbacks(probeRunnable)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        ForegroundLedger.connected = false
        probeHandler.removeCallbacks(probeRunnable)
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    companion object {
        fun isEnabled(ctx: Context): Boolean {
            val enabled = Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any { it.contains(ctx.packageName) }
        }
    }
}
