package com.suvye.unlockreminder

import android.content.Context
import org.json.JSONObject

object Prefs {
    private const val FILE = "settings"
    private const val KEY_INTERVAL = "interval_seconds"
    private const val KEY_ROUND_START = "round_start"
    private const val KEY_RUNNING = "service_running"
    private const val KEY_LAST_FIRE = "last_fire_result"
    private const val KEY_LAST_FIRE_AT = "last_fire_at"
    private const val KEY_INTERVAL_OVERRIDE = "interval_override"
    private const val KEY_HEARTBEAT = "service_heartbeat"
    private const val KEY_STEALTH_DOT = "keepalive_stealth_dot"
    private const val KEY_CREDITED = "credited_json"
    private const val KEY_CREDITED_ROUND = "credited_round"
    private const val KEY_HIDE_NOTIF_DETAILS = "hide_notif_details"

    const val DEFAULT_INTERVAL_SECONDS = 10L
    const val MIN_INTERVAL_SECONDS = 5L
    const val MAX_INTERVAL_SECONDS = 86400L

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun intervalSeconds(ctx: Context): Long =
        sp(ctx).getLong(KEY_INTERVAL, DEFAULT_INTERVAL_SECONDS)

    fun setIntervalSeconds(ctx: Context, seconds: Long) {
        sp(ctx).edit().putLong(KEY_INTERVAL, seconds.coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS)).apply()
    }

    fun roundStart(ctx: Context): Long = sp(ctx).getLong(KEY_ROUND_START, 0L)

    fun setRoundStart(ctx: Context, time: Long) {
        sp(ctx).edit().putLong(KEY_ROUND_START, time).apply()
    }

    fun clearRound(ctx: Context) {
        sp(ctx).edit().remove(KEY_ROUND_START).apply()
    }

    fun isRunning(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_RUNNING, false)

    fun setRunning(ctx: Context, running: Boolean) {
        sp(ctx).edit().putBoolean(KEY_RUNNING, running).apply()
    }

    /** 上次到点提醒走了哪条链路（自诊断：无需 adb 也能从用户界面看到卡在哪一步） */
    fun lastFireResult(ctx: Context): String = sp(ctx).getString(KEY_LAST_FIRE, "").orEmpty()

    fun setLastFire(ctx: Context, value: String) {
        sp(ctx).edit().putString(KEY_LAST_FIRE, value).apply()
    }

    fun lastFireAt(ctx: Context): Long = sp(ctx).getLong(KEY_LAST_FIRE_AT, 0L)

    fun setLastFireAt(ctx: Context, time: Long) {
        sp(ctx).edit().putLong(KEY_LAST_FIRE_AT, time).apply()
    }

    /** 诊断测试用临时间隔（秒），0 表示无覆盖；本轮结束即清 */
    fun roundIntervalOverride(ctx: Context): Long = sp(ctx).getLong(KEY_INTERVAL_OVERRIDE, 0L)

    fun setRoundIntervalOverride(ctx: Context, seconds: Long) {
        sp(ctx).edit().putLong(KEY_INTERVAL_OVERRIDE, seconds).apply()
    }

    /** 监控服务心跳（看门狗用它判断服务是否被冻/被杀） */
    fun lastHeartbeat(ctx: Context): Long = sp(ctx).getLong(KEY_HEARTBEAT, 0L)

    fun setLastHeartbeat(ctx: Context, time: Long) {
        sp(ctx).edit().putLong(KEY_HEARTBEAT, time).apply()
    }

    /** 防冻浮标隐蔽模式：1px 全透明。默认关（4dp 微点），开了保活效果自担 */
    fun keepaliveStealth(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_STEALTH_DOT, false)

    fun setKeepaliveStealth(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(KEY_STEALTH_DOT, value).apply()
    }

    /** 隐藏通知栏详情：开了之后通知只显示「监控中」，不显示任何剩余倒计时 */
    fun hideNotifDetails(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_HIDE_NOTIF_DETAILS, false)

    fun setHideNotifDetails(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(KEY_HIDE_NOTIF_DETAILS, value).apply()
    }

    /**
     * 循环提醒的已入账毫秒（pkg → 提醒时该应用的 raw 累计）。
     * effective = raw - credited[pkg]：提醒后该应用从 0 重新计。
     * 带 roundStart 校验：换轮自动作废。写穿持久化，服务被杀重建不丢。
     */
    fun credited(ctx: Context, roundStart: Long): Map<String, Long> {
        if (sp(ctx).getLong(KEY_CREDITED_ROUND, 0L) != roundStart) return emptyMap()
        val json = sp(ctx).getString(KEY_CREDITED, null) ?: return emptyMap()
        return try {
            val o = JSONObject(json)
            val out = HashMap<String, Long>()
            for (k in o.keys()) out[k] = o.getLong(k)
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun setCredited(ctx: Context, roundStart: Long, credited: Map<String, Long>) {
        val o = JSONObject()
        for ((k, v) in credited) o.put(k, v)
        sp(ctx).edit()
            .putLong(KEY_CREDITED_ROUND, roundStart)
            .putString(KEY_CREDITED, o.toString())
            .apply()
    }
}
