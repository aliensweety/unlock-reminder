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

    /** 聚合 [from, to] 内各应用前台时长，返回 "应用名 · 时长" 列表，按用时降序。 */
    fun topUsage(ctx: Context, from: Long, to: Long, limit: Int = 8): List<String> {
        if (from <= 0L || to <= from) return emptyList()
        if (!hasUsageAccess(ctx)) return emptyList()
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyList()

        val totals = HashMap<String, Long>()
        var current: String? = null
        var currentStart = 0L

        fun flush(endTime: Long) {
            val pkg = current ?: return
            val effectiveStart = maxOf(currentStart, from)
            val effectiveEnd = minOf(endTime, to)
            if (effectiveEnd > effectiveStart) {
                totals[pkg] = (totals[pkg] ?: 0L) + (effectiveEnd - effectiveStart)
            }
            current = null
        }

        // 向前回溯最多 15 分钟，找准 roundStart 边界时刻正处于前台的应用
        val lookbackStart = maxOf(0L, from - 15 * 60 * 1000L)
        val events = try {
            usm.queryEvents(lookbackStart, to)
        } catch (_: Exception) {
            null
        } ?: return emptyList()

        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.timeStamp < from) {
                // 在 roundStart 之前的事件：仅维护当前前台包状态，不累计时长
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        current = event.packageName
                        currentStart = event.timeStamp
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED -> {
                        if (current == event.packageName) {
                            current = null
                        }
                    }
                }
            } else {
                // 进入 [from, to] 范围内的事件
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        flush(event.timeStamp)
                        current = event.packageName
                        currentStart = event.timeStamp
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED -> {
                        if (current == event.packageName) {
                            flush(event.timeStamp)
                        }
                    }
                }
            }
        }
        flush(minOf(to, System.currentTimeMillis()))

        val pm = ctx.packageManager
        return totals.entries
            .filter { it.key != ctx.packageName }
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
