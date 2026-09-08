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

        // 回看 6 小时：防「RESUMED 早于窗口 + 一直没 PAUSED」的长会话漏记，超出本轮的段会被夹紧
        val lookbackMs = 6 * 60 * 60 * 1000L
        val totals = HashMap<String, Long>()
        // 按包名做 open/close 计数配平：应用内切页是新 Activity 先 RESUMED、旧 Activity 后 PAUSED，
        // 简单的开/关记录会把会话提前关掉漏记，计数法才能扛住多 Activity 与分屏
        val openCount = HashMap<String, Int>()
        val resumeAt = HashMap<String, Long>()

        val events = try {
            usm.queryEvents(maxOf(0L, from - lookbackMs), to)
        } catch (_: Exception) {
            return emptyList()
        }
        val event = UsageEvents.Event()
        var rawCount = 0
        try {
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val pkg = event.packageName ?: continue
                rawCount++
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
        // 到窗口末尾仍未 PAUSED 的会话：正常计到窗口末尾，但防强杀/崩溃的僵尸会话整轮误记，截断 15 分钟
        for (pkg in resumeAt.keys.toList()) {
            val start = resumeAt[pkg] ?: continue
            credit(totals, pkg, start, minOf(to, start + 15 * 60 * 1000L), from, to)
        }
        openCount.clear()
        resumeAt.clear()

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
