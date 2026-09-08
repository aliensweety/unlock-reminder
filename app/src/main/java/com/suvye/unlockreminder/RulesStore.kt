package com.suvye.unlockreminder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 分应用提醒规则（默认+覆盖模型）：
 * - defaultSec：全局默认生效时长（秒），0 = 未列入覆盖列表的应用不提醒
 * - overrides：按应用的独立时长（秒），与默认互斥生效——存在即覆盖默认
 * 监控语义：本轮内某应用累计使用 ≥ 生效时长 → 全屏提醒（每应用每轮一次）
 */
object RulesStore {
    data class AppRule(val pkg: String, val label: String, val thresholdSec: Long)

    private const val FILE = "rules"
    private const val KEY_DEFAULT = "default_sec"
    private const val KEY_OVERRIDES = "overrides_json"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 全局默认生效时长（秒），0 = 默认不提醒 */
    fun defaultSec(ctx: Context): Long = sp(ctx).getLong(KEY_DEFAULT, 0L)

    fun setDefaultSec(ctx: Context, sec: Long) {
        sp(ctx).edit().putLong(KEY_DEFAULT, sec).apply()
    }

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

    /** 某应用的生效阈值（秒），0 = 不提醒 */
    fun effectiveSec(ctx: Context, pkg: String): Long {
        overrides(ctx).firstOrNull { it.pkg == pkg }?.let { return it.thresholdSec }
        return defaultSec(ctx)
    }

    /** 规则模式开启中（默认>0 或存在覆盖行）：此时全局倒计时让位给分应用规则 */
    fun rulesActive(ctx: Context): Boolean =
        defaultSec(ctx) > 0 || overrides(ctx).isNotEmpty()
}
