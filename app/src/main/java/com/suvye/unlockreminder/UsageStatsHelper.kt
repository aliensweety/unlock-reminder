package com.suvye.unlockreminder

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import android.os.SystemClock

object UsageStatsHelper {

    fun hasUsageAccess(ctx: Context): Boolean {
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            ctx.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * 双源合计（轮内各应用使用毫秒）。
     * queryEvents（q）：系统结算，但 ColorOS 会对后台应用静默挂起 usage 数据访问 → 空转；
     * 无障碍账本（a）：实时不受管控，但服务重启会丢账、窗口噪音略有出入。
     * 账本健康时：a 为下限，q 最多只比 a 多 [Q_LEDGE_MS]（补漏），更高的 q 视为
     * 僵尸虚账截断；账本不可用（无障碍关闭/轮次不匹配）则退回纯 q（v0.8 行为）。
     */
    fun totalsFor(ctx: Context, roundId: Long, from: Long, to: Long): Map<String, Long> {
        val q = computeTotals(ctx, from, to)
        if (!ForegroundLedger.isLive(roundId)) return q
        ForegroundLedger.init(ctx)
        val a = ForegroundLedger.totals(SystemClock.elapsedRealtime())
        val out = HashMap<String, Long>()
        for (k in a.keys + q.keys) {
            val av = a[k] ?: 0L
            val qv = q[k] ?: 0L
            out[k] = maxOf(av, minOf(qv, av + Q_LEDGE_MS))
        }
        android.util.Log.d("URStats", "a=$a q=$q -> $out")
        return out
    }

    private const val Q_LEDGE_MS = 60_000L

    /** totals → "应用名 · 时长" 列表（排除自身与 0 秒噪音） */
    fun formatTop(totals: Map<String, Long>, ctx: Context, limit: Int = 8): List<String> {
        val pm = ctx.packageManager
        return totals.entries
            .filter { it.key != ctx.packageName }
            .filter { it.value >= 1_000L } // 0 秒的系统组件（photopicker/IntentResolver 等）是噪音
            .sortedByDescending { it.value }
            .take(limit)
            .map { (pkg, ms) ->
                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (_: Exception) {
                    pkg
                }
                "$label · ${formatDuration(ms)}"
            }
    }

    fun topUsage(ctx: Context, from: Long, to: Long, limit: Int = 8): List<String> =
        formatTop(computeTotals(ctx, from, to), ctx, limit)

    /**
     * 聚合 [from, to] 内各应用前台时长。回看 6 小时播种：解锁时已在前台的 App 往往
     * 不会再发 RESUMED 事件；按包名做 open/close 计数配平（应用内切页是新 Activity
     * 先 RESUMED、旧 Activity 后 PAUSED，简单开关记录会提前关会话漏记）。
     */
    private fun computeTotals(ctx: Context, from: Long, to: Long): Map<String, Long> {
        if (!hasUsageAccess(ctx)) return emptyMap()
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyMap()

        val lookbackMs = 6 * 60 * 60 * 1000L
        val totals = HashMap<String, Long>()
        val openCount = HashMap<String, Int>()
        val resumeAt = HashMap<String, Long>()

        val events = try {
            usm.queryEvents(maxOf(0L, from - lookbackMs), to)
        } catch (_: Exception) {
            return emptyMap()
        }
        val event = UsageEvents.Event()
        try {
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val pkg = event.packageName ?: continue
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        val c = openCount[pkg] ?: 0
                        openCount[pkg] = c + 1
                        if (c == 0) resumeAt[pkg] = event.timeStamp
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                        val c = openCount[pkg]
                        if (c != null) {
                            if (c <= 1) {
                                openCount.remove(pkg)
                                val start = resumeAt.remove(pkg)
                                if (start != null) credit(totals, pkg, start, event.timeStamp, from, to)
                            } else {
                                openCount[pkg] = c - 1
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // 个别 OEM 事件流异常：用已收集到的部分结算
        }
        // 未闭合会话只结算最晚的一段：它是真实的「当前前台」；更早的未闭合都是
        // 强杀/跨快照留下的残缺数据，多段各给 15 分钟会叠出几十分钟虚账（模拟器实测）。
        resumeAt.entries.maxByOrNull { it.value }?.let { (pkg, start) ->
            credit(totals, pkg, start, minOf(to, start + 15 * 60 * 1000L), from, to)
        }
        openCount.clear()
        resumeAt.clear()
        return totals
    }

    /** 记入一段时长：只统计与提醒窗口 [from, to] 的交集 */
    private fun credit(
        totals: HashMap<String, Long>,
        pkg: String,
        start: Long,
        end: Long,
        from: Long,
        to: Long
    ) {
        val s = maxOf(start, from)
        val e = minOf(end, to)
        if (e > s) totals[pkg] = (totals[pkg] ?: 0L) + (e - s)
    }

    fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> "${h}小时${m}分${s}秒"
            m > 0 -> "${m}分${s}秒"
            else -> "${s}秒"
        }
    }

    /** 通知栏 / 列表用的短格式 */
    fun formatDurationShort(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> "${h}小时${m}分"
            m > 0 && s > 0 -> "${m}分${s}秒"
            m > 0 -> "${m}分钟"
            else -> "${s}秒"
        }
    }
}
