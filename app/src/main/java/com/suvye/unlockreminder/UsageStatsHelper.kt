package com.suvye.unlockreminder

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process

object UsageStatsHelper {

    private const val LOOKBACK_MS = 6L * 60 * 60 * 1000

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
        if (!hasUsageAccess(ctx)) return emptyList()
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyList()

        val totals = HashMap<String, Long>()
        var current: String? = null
        var currentStart = 0L

        fun flush(endTime: Long) {
            val pkg = current ?: return
            val start = maxOf(currentStart, from)
            if (endTime > start) {
                totals[pkg] = (totals[pkg] ?: 0L) + (endTime - start)
            }
            current = null
        }

        val lookback = (from - LOOKBACK_MS).coerceAtLeast(0L)
        val events = usm.queryEvents(lookback, to)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    flush(event.timeStamp)
                    current = event.packageName
                    currentStart = event.timeStamp
                }
                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    if (current == event.packageName) {
                        flush(event.timeStamp)
                    } else if (current == null && event.timeStamp >= from) {
                        current = event.packageName
                        currentStart = from
                        flush(event.timeStamp)
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
                    val info = if (Build.VERSION.SDK_INT >= 33) {
                        pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getApplicationInfo(pkg, 0)
                    }
                    pm.getApplicationLabel(info).toString()
                } catch (_: Exception) {
                    pkg
                }
                "$label · ${formatDuration(ms)}"
            }
    }

    fun formatDuration(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0L)) / 1000
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
