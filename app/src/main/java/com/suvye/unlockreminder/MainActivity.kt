package com.suvye.unlockreminder

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch

/** 首页 = 仪表盘：状态、开关、今日/累计数字卡、应用时长条形榜、两个入口。 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var bannerText: TextView
    private lateinit var fireResultText: TextView
    private lateinit var switchMonitor: MaterialSwitch
    private lateinit var kpiUnlocks: TextView
    private lateinit var kpiRounds: TextView
    private lateinit var kpiReminders: TextView
    private lateinit var kpiUnlocksToday: TextView
    private lateinit var kpiRoundsToday: TextView
    private lateinit var kpiRemindersToday: TextView
    private lateinit var topAppsContainer: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private var suppressSwitch = false

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
        fireResultText = findViewById(R.id.fireResultText)
        switchMonitor = findViewById(R.id.switchMonitor)
        kpiUnlocks = findViewById(R.id.kpiUnlocks)
        kpiRounds = findViewById(R.id.kpiRounds)
        kpiReminders = findViewById(R.id.kpiReminders)
        kpiUnlocksToday = findViewById(R.id.kpiUnlocksToday)
        kpiRoundsToday = findViewById(R.id.kpiRoundsToday)
        kpiRemindersToday = findViewById(R.id.kpiRemindersToday)
        topAppsContainer = findViewById(R.id.topAppsContainer)

        bannerText.setOnClickListener { startActivity(Intent(this, WizardActivity::class.java)) }

        findViewById<Button>(R.id.btnRules).setOnClickListener {
            startActivity(Intent(this, RulesActivity::class.java))
        }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        switchMonitor.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            if (checked) {
                ContextCompat.startForegroundService(this, Intent(this, MonitorService::class.java))
                Toast.makeText(this, "监控已开启，锁屏再解锁即开始倒计时", Toast.LENGTH_SHORT).show()
            } else {
                startService(
                    Intent(this, MonitorService::class.java)
                        .setAction(MonitorService.ACTION_STOP_MONITOR)
                )
                Toast.makeText(this, "监控已关闭", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 服务健康兜底：开关应开着但服务被杀（START_STICKY 尚未拉起）时立即重建
        if (Prefs.isRunning(this)) {
            ContextCompat.startForegroundService(this, Intent(this, MonitorService::class.java))
        }
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

        statusText.text = when {
            running && roundStart > 0 && RulesStore.rulesActive(this) -> "分应用提醒监控中 · 规则触发时弹出"
            running && roundStart > 0 -> {
                val ov = Prefs.roundIntervalOverride(this)
                val intervalMs = (if (ov > 0) ov else Prefs.intervalSeconds(this)) * 1000
                val remaining = intervalMs - (System.currentTimeMillis() - roundStart)
                if (remaining > 0) "倒计时中 · 剩余 ${remaining / 1000 + 1} 秒" else "到点提醒中…"
            }
            running -> "监控中 · 等待下次解锁"
            else -> "已停止 · 打开上方开关开始使用"
        }

        bannerText.visibility =
            if (running && !Settings.canDrawOverlays(this)) View.VISIBLE else View.GONE

        val last = Prefs.lastFireResult(this)
        fireResultText.visibility = if (last.isEmpty()) View.GONE else View.VISIBLE
        fireResultText.text = "上次到点：$last"

        suppressSwitch = true
        switchMonitor.isChecked = running
        suppressSwitch = false

        kpiUnlocks.text = StatsStore.unlocks(this).toString()
        kpiRounds.text = StatsStore.rounds(this).toString()
        kpiReminders.text = StatsStore.reminders(this).toString()
        kpiUnlocksToday.text = "今日 ${StatsStore.todayUnlocks(this)}"
        kpiRoundsToday.text = "今日 ${StatsStore.todayRounds(this)}"
        kpiRemindersToday.text = "今日 ${StatsStore.todayReminders(this)}"

        refreshTopApps()
    }

    private fun refreshTopApps() {
        val apps = StatsStore.topApps(this, limit = 6)
        topAppsContainer.removeAllViews()
        if (apps.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.topapps_empty)
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.reminder_text_secondary))
            }
            topAppsContainer.addView(empty)
            return
        }
        val maxMs = apps.first().second.coerceAtLeast(1L)
        val inflater = LayoutInflater.from(this)
        for ((pkg, ms) in apps) {
            val row = inflater.inflate(R.layout.item_topapp, topAppsContainer, false)
            val name = row.findViewById<TextView>(R.id.rowAppName)
            val time = row.findViewById<TextView>(R.id.rowAppTime)
            val bar = row.findViewById<ProgressBar>(R.id.rowBar)
            name.text = appLabel(pkg)
            time.text = UsageStatsHelper.formatDuration(ms)
            bar.max = 100
            bar.progress = ((ms * 100L) / maxMs).toInt().coerceIn(1, 100)
            topAppsContainer.addView(row)
        }
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }
}
