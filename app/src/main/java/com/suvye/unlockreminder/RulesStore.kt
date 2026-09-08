package com.suvye.unlockreminder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 分应用提醒规则：只按「选中的应用 + 各自时长」生效。
 * 没有全局默认；未列入的应用不提醒。
 * 语义：解锁后该应用累计使用 ≥ 时长 → 弹一次；下次解锁重新计。
 */
object RulesStore {
    data class AppRule(val pkg: String, val label: String, val thresholdSec: Long)

    val PRESETS = listOf(
        30L to "30 秒",
        60L to "1 分钟",
        180L to "3 分钟",
        300L to "5 分钟",
        600L to "10 分钟",
        900L to "15 分钟",
        1800L to "30 分钟",
        3600L to "1 小时"
    )
    const val DEFAULT_THRESHOLD_SEC = 600L

    private const val FILE = "rules"
    private const val KEY_OVERRIDES = "overrides_json"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun overrides(ctx: Context): List<AppRule> {
        val json = sp(ctx).getString(KEY_OVERRIDES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                AppRule(o.getString("pkg"), o.optString("label", o.getString("pkg")), o.getLong("sec"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveOverrides(ctx: Context, rules: List<AppRule>) {
        val arr = JSONArray()
        for (r in rules) {
            arr.put(JSONObject().put("pkg", r.pkg).put("label", r.label).put("sec", r.thresholdSec))
        }
        sp(ctx).edit().putString(KEY_OVERRIDES, arr.toString()).apply()
    }

    fun upsert(ctx: Context, pkg: String, label: String, sec: Long) {
        val list = overrides(ctx).toMutableList()
        list.removeAll { it.pkg == pkg }
        list.add(AppRule(pkg, label, sec))
        saveOverrides(ctx, list)
    }

    fun remove(ctx: Context, pkg: String) {
        saveOverrides(ctx, overrides(ctx).filter { it.pkg != pkg })
    }

    /** 某应用的生效阈值（秒），0 = 不在列表里，不提醒 */
    fun effectiveSec(ctx: Context, pkg: String): Long =
        overrides(ctx).firstOrNull { it.pkg == pkg }?.thresholdSec ?: 0L

    fun rulesActive(ctx: Context): Boolean = overrides(ctx).isNotEmpty()

    fun formatThreshold(sec: Long): String {
        PRESETS.firstOrNull { it.first == sec }?.let { return it.second }
        return if (sec % 60L == 0L) "${sec / 60L} 分钟" else "${sec} 秒"
    }
}
