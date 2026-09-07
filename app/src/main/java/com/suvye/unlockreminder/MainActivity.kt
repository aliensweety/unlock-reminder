package com.suvye.unlockreminder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
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
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private val presets = listOf(
        "10 秒（测试推荐）" to 10L,
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
    private lateinit var switchMonitor: MaterialSwitch
    private lateinit var spinnerInterval: Spinner
    private lateinit var customRow: LinearLayout
    private lateinit var editCustom: EditText
    private lateinit var btnTest: Button
    private lateinit var btnPermNotif: MaterialButton
    private lateinit var btnPermUsage: MaterialButton
    private lateinit var btnPermOverlay: MaterialButton
    private lateinit var btnPermBattery: MaterialButton

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
        switchMonitor = findViewById(R.id.switchMonitor)
        spinnerInterval = findViewById(R.id.spinnerInterval)
        customRow = findViewById(R.id.customRow)
        editCustom = findViewById(R.id.editCustom)
        btnTest = findViewById(R.id.btnTest)
        btnPermNotif = findViewById(R.id.btnPermNotif)
        btnPermUsage = findViewById(R.id.btnPermUsage)
        btnPermOverlay = findViewById(R.id.btnPermOverlay)
        btnPermBattery = findViewById(R.id.btnPermBattery)

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
                stopService(Intent(this, MonitorService::class.java))
                Toast.makeText(this, "监控已关闭", Toast.LENGTH_SHORT).show()
            }
            refreshStatus()
        }

        btnTest.setOnClickListener {
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
                try {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    openAppDetails()
                }
            }
        }

        btnPermUsage.setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName"))
                )
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                } catch (_: Exception) {
                    openAppDetails()
                }
            }
        }

        btnPermOverlay.setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                } catch (_: Exception) {
                    openAppDetails()
                }
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
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.isRunning(this)) {
            // 保障若系统偶发清理服务，进入主界面时静默拉起保持常驻
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
                val intervalMs = Prefs.intervalSeconds(this) * 1000
                val remaining = intervalMs - (System.currentTimeMillis() - roundStart)
                if (remaining > 0) "倒计时中 · 剩余 ${remaining / 1000 + 1} 秒" else "到点提醒中…"
            }
            running -> "监控中 · 等待下次解锁"
            else -> "已停止 · 打开下方开关开始使用"
        }
    }

    private fun refreshPerms() {
        stylePermButton(
            btnPermNotif,
            granted = notifGranted(),
            grantedText = "① 通知权限：已开启",
            missingText = "① 通知权限：未开启（点按授权）"
        )
        stylePermButton(
            btnPermUsage,
            granted = UsageStatsHelper.hasUsageAccess(this),
            grantedText = "② 使用情况访问：已开启",
            missingText = "② 使用情况访问：未开启（点按授权）"
        )
        stylePermButton(
            btnPermOverlay,
            granted = Settings.canDrawOverlays(this),
            grantedText = "③ 悬浮窗权限：已开启",
            missingText = "③ 悬浮窗权限：未开启（点按授权）"
        )
        val pm = getSystemService(PowerManager::class.java)
        val batteryGranted = pm?.isIgnoringBatteryOptimizations(packageName) == true
        stylePermButton(
            btnPermBattery,
            granted = batteryGranted,
            grantedText = "④ 电池优化白名单：已加入",
            missingText = "④ 电池优化白名单：未加入（点按设置）"
        )
    }

    private fun stylePermButton(btn: MaterialButton, granted: Boolean, grantedText: String, missingText: String) {
        btn.text = if (granted) grantedText else missingText
        val iconRes = if (granted) R.drawable.ic_check_circle else R.drawable.ic_warning_circle
        val colorRes = if (granted) R.color.perm_granted else R.color.perm_missing
        val color = ContextCompat.getColor(this, colorRes)

        btn.icon = ContextCompat.getDrawable(this, iconRes)
        btn.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        btn.iconPadding = 20
        btn.strokeColor = ColorStateList.valueOf(color)
        btn.setTextColor(color)
    }

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
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
        }
    }
}
