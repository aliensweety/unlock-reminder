package com.suvye.unlockreminder

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private val presets = listOf(
        "10 秒" to 10L,
        "30 秒" to 30L,
        "1 分钟" to 60L,
        "3 分钟" to 180L,
        "5 分钟" to 300L,
        "10 分钟" to 600L,
        "15 分钟" to 900L,
        "30 分钟" to 1800L,
        "自定义…" to -1L
    )

    private lateinit var statusText: TextView
    private lateinit var bannerText: TextView
    private lateinit var fireResultText: TextView
    private lateinit var switchMonitor: MaterialSwitch
    private lateinit var spinnerInterval: Spinner
    private lateinit var customRow: LinearLayout
    private lateinit var editCustom: EditText
    private lateinit var btnPermNotif: Button
    private lateinit var btnPermUsage: Button
    private lateinit var btnPermOverlay: Button
    private lateinit var btnPermBattery: Button
    private lateinit var btnPermFsi: Button
    private lateinit var btnPermAlarm: Button

    private val handler = Handler(Looper.getMainLooper())
    private var suppressSwitch = false

    private val ticker = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        bannerText = findViewById(R.id.bannerText)
        fireResultText = findViewById(R.id.fireResultText)
        switchMonitor = findViewById(R.id.switchMonitor)
        spinnerInterval = findViewById(R.id.spinnerInterval)
        customRow = findViewById(R.id.customRow)
        editCustom = findViewById(R.id.editCustom)
        btnPermNotif = findViewById(R.id.btnPermNotif)
        btnPermUsage = findViewById(R.id.btnPermUsage)
        btnPermOverlay = findViewById(R.id.btnPermOverlay)
        btnPermBattery = findViewById(R.id.btnPermBattery)
        btnPermFsi = findViewById(R.id.btnPermFsi)
        btnPermAlarm = findViewById(R.id.btnPermAlarm)

        spinnerInterval.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            presets.map { it.first }
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerInterval.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val seconds = presets[position].second
                if (seconds > 0) {
                    customRow.visibility = View.GONE
                    Prefs.setIntervalSeconds(this@MainActivity, seconds)
                } else {
                    customRow.visibility = View.VISIBLE
                    if (editCustom.text.isNullOrBlank()) {
                        editCustom.setText(Prefs.intervalSeconds(this@MainActivity).toString())
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
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

        findViewById<Button>(R.id.btnTest).setOnClickListener {
            if (!Prefs.isRunning(this)) {
                Toast.makeText(this, "请先打开上方开关", Toast.LENGTH_SHORT).show()
            } else {
                applyCustom()
                startService(
                    Intent(this, MonitorService::class.java)
                        .setAction(MonitorService.ACTION_START_ROUND)
                )
            }
        }

        // 自诊断测试：5 秒后到点，用户立刻切走，回来一眼看出全局弹窗是否通
        findViewById<Button>(R.id.btnTestDelay).setOnClickListener {
            if (!Prefs.isRunning(this)) {
                Toast.makeText(this, "请先打开上方开关", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "5 秒后到点！请立刻切到微信或桌面等待弹窗", Toast.LENGTH_LONG).show()
                startService(
                    Intent(this, MonitorService::class.java)
                        .setAction(MonitorService.ACTION_START_ROUND)
                        .putExtra(MonitorService.EXTRA_INTERVAL_OVERRIDE, 5L)
                )
            }
        }

        bannerText.setOnClickListener { startActivity(Intent(this, WizardActivity::class.java)) }

        findViewById<Button>(R.id.btnWizard).setOnClickListener {
            startActivity(Intent(this, WizardActivity::class.java))
        }

        editCustom.setOnEditorActionListener { _, _, _ ->
            applyCustom()
            true
        }
        editCustom.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) applyCustom() }

        btnPermNotif.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            } else {
                openAppDetails()
            }
        }
        btnPermUsage.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (_: Exception) {
                openAppDetails()
            }
        }
        btnPermOverlay.setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            } catch (_: Exception) {
                openAppDetails()
            }
        }
        btnPermBattery.setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) {
                    openAppDetails()
                }
            }
        }
        // Android 14 起全屏意图默认不授予，入口放进应用通知设置页
        btnPermFsi.setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                )
            } catch (_: Exception) {
                openAppDetails()
            }
        }
        // 精确闹钟：到点准时 + 能把被 ROM 冻结的后台进程叫醒（宏软件同款关键权限）
        btnPermAlarm.setOnClickListener {
            try {
                startActivity(
                    Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:$packageName"))
                )
            } catch (_: Exception) {
                openAppDetails()
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
        applyCustom()
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) refreshAll()
    }

    private fun refreshAll() {
        refreshPerms()
        refreshInterval()
        suppressSwitch = true
        switchMonitor.isChecked = Prefs.isRunning(this)
        suppressSwitch = false
        refreshStatus()
    }

    private fun refreshInterval() {
        val current = Prefs.intervalSeconds(this)
        val position = presets.indexOfFirst { it.second == current }
        if (position >= 0) {
            if (spinnerInterval.selectedItemPosition != position) {
                spinnerInterval.setSelection(position, false)
            }
            customRow.visibility = View.GONE
        } else {
            val customPosition = presets.size - 1
            if (spinnerInterval.selectedItemPosition != customPosition) {
                spinnerInterval.setSelection(customPosition, false)
            }
            customRow.visibility = View.VISIBLE
            if (editCustom.text.isNullOrBlank()) editCustom.setText(current.toString())
        }
    }

    private fun refreshStatus() {
        val running = Prefs.isRunning(this)
        val roundStart = Prefs.roundStart(this)
        statusText.text = when {
            running && roundStart > 0 -> {
                val ov = Prefs.roundIntervalOverride(this)
                val intervalMs = (if (ov > 0) ov else Prefs.intervalSeconds(this)) * 1000
                val remaining = intervalMs - (System.currentTimeMillis() - roundStart)
                if (remaining > 0) "倒计时中 · 剩余 ${remaining / 1000 + 1} 秒" else "到点提醒中…"
            }
            running -> "监控中 · 等待下次解锁"
            else -> "已停止 · 打开上方开关开始使用"
        }
        // 悬浮窗是全局弹窗的唯一合法前提：没开就常驻警告横幅
        bannerText.visibility =
            if (running && !Settings.canDrawOverlays(this)) View.VISIBLE else View.GONE
        val last = Prefs.lastFireResult(this)
        fireResultText.visibility = if (last.isEmpty()) View.GONE else View.VISIBLE
        fireResultText.text = "上次到点：$last"
    }

    private fun refreshPerms() {
        btnPermNotif.text = mark(notifGranted(), "① 通知权限")
        btnPermUsage.text = mark(UsageStatsHelper.hasUsageAccess(this), "② 使用情况访问")
        btnPermOverlay.text = mark(Settings.canDrawOverlays(this), "③ 悬浮窗权限")
        val pm = getSystemService(PowerManager::class.java)
        btnPermBattery.text =
            mark(pm?.isIgnoringBatteryOptimizations(packageName) == true, "④ 电池优化白名单")
        val fsiVisible = Build.VERSION.SDK_INT >= 34
        btnPermFsi.visibility = if (fsiVisible) View.VISIBLE else View.GONE
        if (fsiVisible) {
            val nm = getSystemService(NotificationManager::class.java)
            btnPermFsi.text = mark(nm?.canUseFullScreenIntent() == true, "⑤ 全屏弹出（全屏意图）")
        }
        val alarmVisible = Build.VERSION.SDK_INT >= 31
        btnPermAlarm.visibility = if (alarmVisible) View.VISIBLE else View.GONE
        if (alarmVisible) {
            val am = getSystemService(android.app.AlarmManager::class.java)
            btnPermAlarm.text = mark(am?.canScheduleExactAlarms() == true, "⑥ 精确闹钟（穿透后台冻结）")
        }
    }

    private fun mark(ok: Boolean, label: String): String =
        if (ok) "$label：✓ 已允许" else "$label：✗ 未允许（点按开启）"

    private fun applyCustom() {
        val text = editCustom.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        val value = text.toLongOrNull() ?: return
        if (value < Prefs.MIN_INTERVAL_SECONDS || value > Prefs.MAX_INTERVAL_SECONDS) {
            Toast.makeText(this, "间隔范围 ${Prefs.MIN_INTERVAL_SECONDS}–${Prefs.MAX_INTERVAL_SECONDS} 秒", Toast.LENGTH_SHORT).show()
            return
        }
        if (value != Prefs.intervalSeconds(this)) {
            Prefs.setIntervalSeconds(this, value)
            Toast.makeText(this, "间隔已设为 $value 秒", Toast.LENGTH_SHORT).show()
        }
    }

    private fun notifGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED)

    private fun openAppDetails() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        )
    }
}
