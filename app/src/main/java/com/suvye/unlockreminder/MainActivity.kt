package com.suvye.unlockreminder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch

/** 首页 = 产品本身：开关 + 默认倒计时 + 要提醒的应用列表（图标 / 剩余 / 时长）。 */
class MainActivity : AppCompatActivity() {

    companion object {
        /** 默认倒计时行的伪 tag，与 MonitorService.DEFAULT_PKG 一致 */
        private const val DEFAULT_ROW_TAG = "__default__"
    }

    private lateinit var statusText: TextView
    private lateinit var bannerText: TextView
    private lateinit var switchMonitor: MaterialSwitch
    private lateinit var rulesContainer: LinearLayout
    private lateinit var rulesEmpty: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var suppressSwitch = false
    private var lastRuleKey = ""

    private val ticker = object : Runnable {
        override fun run() {
            refreshAll()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        bannerText = findViewById(R.id.bannerText)
        switchMonitor = findViewById(R.id.switchMonitor)
        rulesContainer = findViewById(R.id.rulesContainer)
        rulesEmpty = findViewById(R.id.rulesEmpty)

        bannerText.setOnClickListener { startActivity(Intent(this, WizardActivity::class.java)) }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.btnAddApp).setOnClickListener {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }

        switchMonitor.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            if (checked) {
                if (!RulesStore.rulesActive(this)) {
                    suppressSwitch = true
                    switchMonitor.isChecked = false
                    suppressSwitch = false
                    Toast.makeText(this, getString(R.string.toast_need_apps), Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this, AppPickerActivity::class.java))
                    return@setOnCheckedChangeListener
                }
                ContextCompat.startForegroundService(this, Intent(this, MonitorService::class.java))
                Toast.makeText(this, getString(R.string.toast_monitor_on), Toast.LENGTH_SHORT).show()
            } else {
                startService(
                    Intent(this, MonitorService::class.java)
                        .setAction(MonitorService.ACTION_STOP_MONITOR)
                )
                Toast.makeText(this, getString(R.string.toast_monitor_off), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.isRunning(this)) {
            ContextCompat.startForegroundService(this, Intent(this, MonitorService::class.java))
        }
        renderRules(force = true)
        refreshAll()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun refreshAll() {
        val running = Prefs.isRunning(this)
        val roundStart = Prefs.roundStart(this)
        val ruleCount = RulesStore.overrides(this).size

        statusText.text = when {
            !running -> getString(R.string.status_stopped)
            ruleCount == 0 -> getString(R.string.status_need_apps)
            roundStart > 0 -> {
                val elapsed = (System.currentTimeMillis() - roundStart).coerceAtLeast(0L)
                getString(R.string.status_round, UsageStatsHelper.formatDurationShort(elapsed))
            }
            else -> getString(R.string.status_waiting)
        }

        bannerText.visibility = if (needsSetup()) View.VISIBLE else View.GONE

        suppressSwitch = true
        switchMonitor.isChecked = running
        suppressSwitch = false

        renderRules(force = false)
        updateLiveRemaining()
    }

    private fun needsSetup(): Boolean {
        val overlay = Settings.canDrawOverlays(this)
        val usage = UsageStatsHelper.hasUsageAccess(this)
        val notif = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val a11y = KeepAliveAccessibilityService.isEnabled(this)
        return !overlay || !usage || !notif || !a11y
    }

    private fun renderRules(force: Boolean) {
        val rules = RulesStore.overrides(this)
        val defSec = Prefs.defaultSec(this)
        val key = "def:$defSec|" + rules.joinToString("|") { it.pkg + ":" + it.thresholdSec }
        if (!force && key == lastRuleKey) return
        lastRuleKey = key

        rulesContainer.removeAllViews()
        rulesEmpty.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(this)
        val density = resources.displayMetrics.density

        // 默认倒计时行：不在已选应用里的时间按它计
        val defRow = inflater.inflate(R.layout.item_rule, rulesContainer, false)
        bindDefaultRow(defRow, defSec)
        rulesContainer.addView(defRow)

        for (rule in rules) {
            addRowDivider(density)
            val row = inflater.inflate(R.layout.item_rule, rulesContainer, false)
            bindRow(row, rule)
            rulesContainer.addView(row)
        }
    }

    private fun addRowDivider(density: Float) {
        if (rulesContainer.childCount == 0) return
        val div = View(this)
        div.setBackgroundColor(ContextCompat.getColor(this, R.color.row_divider))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (0.5f * density).toInt().coerceAtLeast(1)
        )
        lp.marginStart = (60 * density).toInt()
        rulesContainer.addView(div, lp)
    }

    private fun bindDefaultRow(row: View, defSec: Long) {
        row.tag = DEFAULT_ROW_TAG
        val icon = row.findViewById<ImageView>(R.id.rowIcon)
        val name = row.findViewById<TextView>(R.id.rowAppName)
        val duration = row.findViewById<TextView>(R.id.rowDuration)
        row.findViewById<View>(R.id.rowRemove).visibility = View.GONE
        name.text = getString(R.string.rules_default)
        duration.text = if (defSec > 0L) RulesStore.formatThreshold(defSec) else getString(R.string.rules_duration_off)
        icon.setImageDrawable(
            try {
                packageManager.getApplicationIcon(packageName)
            } catch (_: Exception) {
                getDrawable(android.R.drawable.sym_def_app_icon)
            }
        )
        duration.setOnClickListener { showDefaultDurationPicker() }
    }

    /** 默认倒计时时长：关闭 / 预设 / 自定义分钟 */
    private fun showDefaultDurationPicker() {
        val presets = RulesStore.PRESETS
        val labels = mutableListOf(getString(R.string.rules_duration_off))
        presets.forEach { labels.add(it.second) }
        labels.add(getString(R.string.rules_duration_custom))
        val current = Prefs.defaultSec(this)
        val checked = when {
            current <= 0L -> 0
            else -> presets.indexOfFirst { it.first == current }.let { if (it >= 0) it + 1 else labels.size - 1 }
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.rules_default))
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                when {
                    which == 0 -> Prefs.setDefaultSec(this, 0L)
                    which < labels.size - 1 -> Prefs.setDefaultSec(this, presets[which - 1].first)
                    else -> showDefaultCustomDuration()
                }
                renderRules(force = true)
                updateLiveRemaining()
            }
            .show()
    }

    private fun showDefaultCustomDuration() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.rules_custom_hint)
            val minutes = (Prefs.defaultSec(this@MainActivity) / 60L).coerceAtLeast(1L)
            setText(minutes.toString())
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.rules_custom_title))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val minutes = input.text?.toString()?.trim()?.toLongOrNull() ?: return@setPositiveButton
                if (minutes < 1L) {
                    Toast.makeText(this, getString(R.string.rules_custom_hint), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                Prefs.setDefaultSec(this, minutes * 60L)
                renderRules(force = true)
                updateLiveRemaining()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun bindRow(row: View, rule: RulesStore.AppRule) {
        row.tag = rule.pkg
        val icon = row.findViewById<ImageView>(R.id.rowIcon)
        val name = row.findViewById<TextView>(R.id.rowAppName)
        val duration = row.findViewById<TextView>(R.id.rowDuration)
        val remove = row.findViewById<TextView>(R.id.rowRemove)
        name.text = rule.label
        duration.text = RulesStore.formatThreshold(rule.thresholdSec)
        icon.setImageDrawable(
            try {
                packageManager.getApplicationIcon(rule.pkg)
            } catch (_: Exception) {
                getDrawable(android.R.drawable.sym_def_app_icon)
            }
        )
        duration.setOnClickListener { showDurationPicker(rule) }
        remove.setOnClickListener {
            RulesStore.remove(this, rule.pkg)
            renderRules(force = true)
            updateLiveRemaining()
        }
    }

    private fun updateLiveRemaining() {
        val roundStart = Prefs.roundStart(this)
        val running = Prefs.isRunning(this)
        var totals = if (running && roundStart > 0L) {
            UsageStatsHelper.totalsFor(this, roundStart, roundStart, System.currentTimeMillis())
        } else {
            emptyMap()
        }
        if (totals.isNotEmpty()) {
            // 循环提醒：减掉各应用已提醒掉的时长，与通知栏口径一致
            val cr = Prefs.credited(this, roundStart)
            if (cr.isNotEmpty()) {
                totals = totals.mapValues { (it.value - (cr[it.key] ?: 0L)).coerceAtLeast(0L) }
            }
        }
        val rules = RulesStore.overrides(this).associateBy { it.pkg }
        val defSec = Prefs.defaultSec(this)
        for (i in 0 until rulesContainer.childCount) {
            val row = rulesContainer.getChildAt(i)
            val pkg = row.tag as? String ?: continue
            val remainingView = row.findViewById<TextView>(R.id.rowRemaining)
            if (pkg == DEFAULT_ROW_TAG) {
                when {
                    !running || roundStart <= 0L -> {
                        remainingView.visibility = View.VISIBLE
                        remainingView.text = getString(R.string.rules_default_desc)
                    }
                    defSec <= 0L -> remainingView.visibility = View.GONE
                    else -> {
                        remainingView.visibility = View.VISIBLE
                        val used = Prefs.defaultUsed(this, roundStart)
                        val left = (defSec * 1000L - used).coerceAtLeast(0L)
                        remainingView.text = getString(
                            R.string.rules_used_left,
                            UsageStatsHelper.formatDurationShort(left)
                        )
                    }
                }
                continue
            }
            val rule = rules[pkg] ?: continue
            val used = totals[pkg] ?: 0L
            val limit = rule.thresholdSec * 1000L
            when {
                !running || roundStart <= 0L -> {
                    remainingView.visibility = View.GONE
                }
                else -> {
                    remainingView.visibility = View.VISIBLE
                    val left = (limit - used).coerceAtLeast(0L)
                    remainingView.text = getString(
                        R.string.rules_used_left,
                        UsageStatsHelper.formatDurationShort(left)
                    )
                }
            }
        }
    }

    private fun showDurationPicker(rule: RulesStore.AppRule) {
        val labels = RulesStore.PRESETS.map { it.second } + getString(R.string.rules_duration_custom)
        val current = RulesStore.PRESETS.indexOfFirst { it.first == rule.thresholdSec }
        AlertDialog.Builder(this)
            .setTitle(rule.label)
            .setSingleChoiceItems(labels.toTypedArray(), current) { dialog, which ->
                if (which < RulesStore.PRESETS.size) {
                    RulesStore.upsert(this, rule.pkg, rule.label, RulesStore.PRESETS[which].first)
                    renderRules(force = true)
                    updateLiveRemaining()
                    dialog.dismiss()
                } else {
                    dialog.dismiss()
                    showCustomDuration(rule)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCustomDuration(rule: RulesStore.AppRule) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.rules_custom_hint)
            val minutes = (rule.thresholdSec / 60L).coerceAtLeast(1L)
            setText(minutes.toString())
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.rules_custom_title))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val minutes = input.text?.toString()?.trim()?.toLongOrNull() ?: return@setPositiveButton
                if (minutes < 1L) {
                    Toast.makeText(this, getString(R.string.rules_custom_hint), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                RulesStore.upsert(this, rule.pkg, rule.label, minutes * 60L)
                renderRules(force = true)
                updateLiveRemaining()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
