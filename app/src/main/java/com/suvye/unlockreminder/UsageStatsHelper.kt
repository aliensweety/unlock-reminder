package com.suvye.unlockreminder

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
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

    /**
     * 聚合 [from, to] 内各应用前台时长，返回 "应用名 · 时长" 列表，按用时降序。
     * 回看 30 分钟播种：解锁时已在前台的 App 往往不会再发 RESUMED 事件，
     * 单指针配对会系统性漏记，因此按包名维护 open map，PAUSED/STOPPED 收尾，
     * 窗口结束仍未闭合的会话记到窗口末尾（应用被强杀收不到 PAUSED 时由这里兜底）。
     */
    fun topUsage(ctx: Context, from: Long, to: Long, limit: Int = 8): List<String> {
        if (!hasUsageAccess(ctx)) return emptyList()
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyList()

        val lookbackMs = 30 * 60 * 1000L
        val totals = HashMap<String, Long>()
        val open = HashMap<String, Long>()

        val events = try {
            usm.queryEvents(maxOf(0L, from - lookbackMs), to)
        } catch (_: Exception) {
            return emptyList()
        }
        val event = UsageEvents.Event()
        try {
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val pkg = event.packageName ?: continue
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        val prev = open[pkg]
                        if (prev == null || event.timeStamp < prev) open[pkg] = event.timeStamp
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                        settle(totals, open, pkg, event.timeStamp, from, to)
                    }
                }
            }
        } catch (_: Exception) {
            // 个别 OEM 事件流异常：用已收集到的部分结算
        }
        for (pkg in open.keys.toList()) {
            settle(totals, open, pkg, to, from, to)
        }

        val homePkg = try {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            ctx.packageManager.resolveActivity(homeIntent, 0)?.activityInfo?.packageName
        } catch (_: Exception) {
            null
        }
        val pm = ctx.packageManager
        return totals.entries
            .filter { it.key != ctx.packageName && it.key != homePkg }
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

    /** 结算一个包的未闭合会话：只统计与提醒窗口 [from, to] 的交集 */
    private fun settle(
        totals: HashMap<String, Long>,
        open: HashMap<String, Long>,
        pkg: String,
        endTime: Long,
        from: Long,
        to: Long
    ) {
        val start = open.remove(pkg) ?: return
        val s = maxOf(start, from)
        val e = minOf(endTime, to)
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
