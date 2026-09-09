package com.suvye.unlockreminder

import android.content.Context
import android.provider.Settings

/**
 * 无障碍实时前台账本：KeepAliveAccessibilityService 把窗口切换记进来，
 * MonitorService / UsageStatsHelper 读。同进程单例，内存共享。
 *
 * 记账模型：记住「当前前台包 + 进入时刻」，切换时把 (now - enteredAt) 结给上一个包。
 * 存在的意义：ColorOS 会对后台应用静默挂起 usage 数据访问，queryEvents 空转时
 * 「时间不减」（v0.8.x 真机实锤）——账本不受 usage 权限管控，只要无障碍绑定在就持续走字。
 *
 * 过滤：SystemUI（通知横幅/下拉栏都是它的窗口事件）、自身包、当前输入法。
 * 这些窗口覆盖不改变「用户在用哪个应用」；IME 若不滤，弹出后抖音的段会被闭合
 * 且收起时不重发抖音事件，计时就此停住。桌面不滤：给桌面记账无害（没有桌面规则）。
 * 时间基准统一 elapsedRealtime（单调，防 NTP/时区跳变），roundId 用墙钟 roundStart。
 */
object ForegroundLedger {
    private var appContext: Context? = null
    private val lock = Any()
    private var roundId = 0L
    private var currentPkg: String? = null
    private var enteredAt = 0L
    private val closed = HashMap<String, Long>()
    private var imePkg: String? = null
    private var imeReadAt = 0L

    /** 无障碍服务是否实际绑定（ColorOS 杀进程后设置里仍显示开启，但绑定不会自动恢复） */
    @Volatile
    var connected = false

    fun init(ctx: Context) {
        if (appContext == null) appContext = ctx.applicationContext
    }

    fun onWindowChanged(pkg: String, nowElapsed: Long) {
        if (pkg == "com.android.systemui") return
        if (pkg == currentIme()) return
        if (pkg == appContext?.packageName) {
            // 自己的窗口（首页/浮层/提醒页）不算前台应用：闭合当前段并清空，
            // 否则上一个包在我们界面里持续虚账增长（v0.13.0 记录的边角 bug）
            synchronized(lock) {
                val cur = currentPkg
                if (cur != null && roundId != 0L && nowElapsed > enteredAt) {
                    closed[cur] = (closed[cur] ?: 0L) + (nowElapsed - enteredAt)
                }
                currentPkg = null
            }
            return
        }
        synchronized(lock) {
            val cur = currentPkg
            if (cur == pkg) return
            if (cur != null && roundId != 0L && nowElapsed > enteredAt) {
                closed[cur] = (closed[cur] ?: 0L) + (nowElapsed - enteredAt)
            }
            currentPkg = pkg
            enteredAt = nowElapsed
        }
    }

    /** 开轮/解锁：清累计，当前前台包从 now 重新计（= 解锁后每个应用从 0 开始的语义） */
    fun startRound(rid: Long, nowElapsed: Long) {
        synchronized(lock) {
            roundId = rid
            closed.clear()
            enteredAt = nowElapsed
        }
    }

    fun endRound() {
        synchronized(lock) {
            roundId = 0L
            closed.clear()
            currentPkg = null
        }
    }

    /** 账本轮次是否与当前轮匹配（不匹配 = 账本不可信，统计回退纯 queryEvents） */
    fun isLive(rid: Long): Boolean = synchronized(lock) { rid != 0L && roundId == rid }

    /** 当前前台应用包名（null = 桌面/锁屏/自己的界面），通知栏跟随显示用 */
    fun currentForeground(): String? = synchronized(lock) { currentPkg }

    /** 本轮各包累计（闭合段 + 当前段实时增长，无封顶） */
    fun totals(nowElapsed: Long): Map<String, Long> = synchronized(lock) {
        if (roundId == 0L) return emptyMap()
        val out = HashMap<String, Long>(closed)
        val cur = currentPkg
        if (cur != null && nowElapsed > enteredAt) {
            out[cur] = (out[cur] ?: 0L) + (nowElapsed - enteredAt)
        }
        out
    }

    /** 当前默认输入法包名，5 秒缓存 */
    private fun currentIme(): String? {
        val ctx = appContext ?: return null
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - imeReadAt < 5_000L) return imePkg
        imeReadAt = now
        imePkg = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
            ?.takeIf { it.isNotEmpty() }
        return imePkg
    }
}
