package com.suvye.unlockreminder

import android.content.Context

object Prefs {
    private const val FILE = "settings"
    private const val KEY_INTERVAL = "interval_seconds"
    private const val KEY_ROUND_START = "round_start"
    private const val KEY_RUNNING = "service_running"
    private const val KEY_LAST_FIRE = "last_fire_result"
    private const val KEY_LAST_FIRE_AT = "last_fire_at"
    private const val KEY_INTERVAL_OVERRIDE = "interval_override"

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
}
