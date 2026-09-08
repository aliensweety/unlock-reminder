package com.suvye.unlockreminder

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

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

    /** 轮内各应用使用毫秒原始 totals，供分应用规则引擎使用 */
    fun totalsFor(ctx: Context, from: Long, to: Long): Map<String, Long> = computeTotals(ctx, from, to)

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
     * 先 RESUMED、旧 Activity 后 PAUSED，简单开关记录会提前关会话漏记），
     * 窗口末尾仍未闭合的会话计到窗口末尾并截断 15 分钟（防强杀僵尸会话整轮误记）。
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
        for (pkg in resumeAt.keys.toList()) {
            val start = resumeAt[pkg] ?: continue
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
}
