package com.suvye.unlockreminder

import android.content.Context
import org.json.JSONObject

/**
 * 本地统计仓：解锁次数 / 提醒轮次 / 提醒触发次数 / 各应用累计使用毫秒。
 * 全部存 SharedPreferences，不联网。today_* 键按日期翻转归零。
 */
object StatsStore {
    private const val FILE = "stats"
    private const val KEY_UNLOCKS = "unlocks"
    private const val KEY_ROUNDS = "rounds"
    private const val KEY_REMINDERS = "reminders"
    private const val KEY_TODAY = "today_date"
    private const val KEY_TODAY_UNLOCKS = "today_unlocks"
    private const val KEY_TODAY_ROUNDS = "today_rounds"
    private const val KEY_TODAY_REMINDERS = "today_reminders"
    private const val KEY_APPSEC_PREFIX = "appms_"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun today(): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        return fmt.format(java.util.Date())
    }

    private fun bumpToday(ctx: Context, key: String): Long {
        val sp = sp(ctx).edit()
        val t = today()
        if (sp(ctx).getString(KEY_TODAY, "") != t) {
            sp.putString(KEY_TODAY, t)
            sp.putLong(KEY_TODAY_UNLOCKS, 0L)
            sp.putLong(KEY_TODAY_ROUNDS, 0L)
            sp.putLong(KEY_TODAY_REMINDERS, 0L)
        }
        val v = (ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getLong(key, 0L)) + 1
        sp.putLong(key, v)
        sp.apply()
        return v
    }

    fun onUnlock(ctx: Context) {
        sp(ctx).edit().putLong(KEY_UNLOCKS, sp(ctx).getLong(KEY_UNLOCKS, 0L) + 1).apply()
        bumpToday(ctx, KEY_TODAY_UNLOCKS)
    }

    fun onRoundEnd(ctx: Context) {
        sp(ctx).edit().putLong(KEY_ROUNDS, sp(ctx).getLong(KEY_ROUNDS, 0L) + 1).apply()
        bumpToday(ctx, KEY_TODAY_ROUNDS)
    }

    fun onReminder(ctx: Context) {
        sp(ctx).edit().putLong(KEY_REMINDERS, sp(ctx).getLong(KEY_REMINDERS, 0L) + 1).apply()
        bumpToday(ctx, KEY_TODAY_REMINDERS)
    }

    /** 轮次结束时累加各应用本轮使用毫秒 */
    fun addAppUsage(ctx: Context, appMs: Map<String, Long>) {
        val e = sp(ctx).edit()
        for ((pkg, ms) in appMs) {
            if (ms <= 0) continue
            val k = KEY_APPSEC_PREFIX + pkg
            e.putLong(k, sp(ctx).getLong(k, 0L) + ms)
        }
        e.apply()
    }

    fun unlocks(ctx: Context): Long = sp(ctx).getLong(KEY_UNLOCKS, 0L)
    fun rounds(ctx: Context): Long = sp(ctx).getLong(KEY_ROUNDS, 0L)
    fun reminders(ctx: Context): Long = sp(ctx).getLong(KEY_REMINDERS, 0L)
    fun todayUnlocks(ctx: Context): Long = sp(ctx).getLong(KEY_TODAY_UNLOCKS, 0L)
    fun todayRounds(ctx: Context): Long = sp(ctx).getLong(KEY_TODAY_ROUNDS, 0L)
    fun todayReminders(ctx: Context): Long = sp(ctx).getLong(KEY_TODAY_REMINDERS, 0L)

    /** 应用累计使用毫秒，降序前 N */
    fun topApps(ctx: Context, limit: Int = 8): List<Pair<String, Long>> =
        sp(ctx).all.entries
            .filter { it.key.startsWith(KEY_APPSEC_PREFIX) && (it.value as Long) > 0 }
            .map { it.key.removePrefix(KEY_APPSEC_PREFIX) to (it.value as Long) }
            .sortedByDescending { it.second }
            .take(limit)

    /** 清空累计（设置里提供） */
    fun resetAll(ctx: Context) {
        val e = sp(ctx).edit()
        e.clear()
        e.apply()
    }
}
