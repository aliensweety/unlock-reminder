package com.suvye.unlockreminder

import android.content.Intent
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * 应用提醒规则（默认+覆盖模型）：
 * - 全局默认时长套用于所有应用（默认=关，即只有覆盖列表内的应用提醒）
 * - 覆盖列表：每个应用独立时长（行内下拉），覆盖默认
 * - 添加应用：多选对话框（已装应用，排除本应用），批量以默认时长加入
 */
class RulesActivity : AppCompatActivity() {

    private val thresholdOptions = listOf(
        60L to "1 分钟", 180L to "3 分钟", 300L to "5 分钟", 600L to "10 分钟",
        900L to "15 分钟", 1800L to "30 分钟", 3600L to "1 小时"
    )
    private val defaultOptions = listOf(0L to "关闭") + thresholdOptions

    private lateinit var rulesContainer: LinearLayout
    private lateinit var spinnerDefault: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rules)

        rulesContainer = findViewById(R.id.rulesContainer)
        spinnerDefault = findViewById(R.id.spinnerDefault)

        val labels = defaultOptions.map { it.second }.toTypedArray()
        spinnerDefault.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val savedDefault = RulesStore.defaultSec(this)
        spinnerDefault.setSelection(defaultOptions.indexOfFirst { it.first == savedDefault }.coerceAtLeast(0))
        spinnerDefault.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                RulesStore.setDefaultSec(this@RulesActivity, defaultOptions[position].first)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.btnAddApp).setOnClickListener { showAddDialog() }

        renderRules()
    }

    override fun onResume() {
        super.onResume()
        renderRules()
    }

    private fun renderRules() {
        rulesContainer.removeAllViews()
        val rules = RulesStore.overrides(this)
        findViewById<TextView>(R.id.rulesEmpty).visibility =
            if (rules.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(this)
        for (rule in rules) {
            val row = inflater.inflate(R.layout.item_rule, rulesContainer, false)
            val name = row.findViewById<TextView>(R.id.rowAppName)
            val spinner = row.findViewById<Spinner>(R.id.rowSpinner)
            val remove = row.findViewById<TextView>(R.id.rowRemove)

            name.text = rule.label
            spinner.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_item,
                thresholdOptions.map { it.second }
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            val idx = thresholdOptions.indexOfFirst { it.first == rule.thresholdSec }
            spinner.setSelection(idx.coerceAtLeast(0))
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                private var first = true
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    // setSelection 触发的首次回调不写库
                    if (first) { first = false; return }
                    RulesStore.upsert(this@RulesActivity, rule.pkg, rule.label, thresholdOptions[position].first)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            remove.setOnClickListener {
                RulesStore.remove(this, rule.pkg)
                renderRules()
            }
            rulesContainer.addView(row)
        }
    }

    /** 多选添加：已装应用（排除本应用），可全选；确认后按当前默认时长（关则 5 分钟）加入 */
    private fun showAddDialog() {
        val pm = packageManager
        val apps: List<ResolveInfo> = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).filter {
            it.activityInfo.packageName != packageName &&
                it.activityInfo.packageName != "com.android.settings"
        }.distinctBy { it.activityInfo.packageName }.sortedBy {
            try { pm.getApplicationLabel(pm.getApplicationInfo(it.activityInfo.packageName, 0)).toString() }
            catch (_: Exception) { it.activityInfo.packageName }
        }
        if (apps.isEmpty()) return

        val existing = RulesStore.overrides(this).map { it.pkg }.toSet()
        val labels = apps.map {
            try { pm.getApplicationLabel(pm.getApplicationInfo(it.activityInfo.packageName, 0)).toString() }
            catch (_: Exception) { it.activityInfo.packageName }
        }
        val pkgs = apps.map { it.activityInfo.packageName }
        val checked = BooleanArray(apps.size) { pkgs[it] in existing }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.rules_pick_title))
            .setMultiChoiceItems(labels.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(getString(R.string.rules_pick_ok)) { _, _ ->
                val added = pkgs.filterIndexed { i, _ -> checked[i] }
                val base = RulesStore.defaultSec(this).takeIf { it > 0 } ?: 300L
                for (pkg in added) {
                    RulesStore.upsert(this, pkg, labelFor(pkg), base)
                }
                renderRules()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun labelFor(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }
}
