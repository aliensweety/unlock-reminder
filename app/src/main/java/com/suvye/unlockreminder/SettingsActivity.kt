package com.suvye.unlockreminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.os.PowerManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.TextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** 设置页：间隔、测试、全部权限入口、保活向导、统计重置。 */
class SettingsActivity : AppCompatActivity() {

    private val presets = listOf(
        "10 秒" to 10L, "30 秒" to 30L, "1 分钟" to 60L, "3 分钟" to 180L,
        "5 分钟" to 300L, "10 分钟" to 600L, "15 分钟" to 900L, "30 分钟" to 1800L,
        "自定义…" to -1L
    )

    private lateinit var spinnerInterval: Spinner
    private lateinit var customRow: LinearLayout
    private lateinit var editCustom: EditText
    private lateinit var btnPermNotif: Button
    private lateinit var btnPermUsage: Button
    private lateinit var btnPermOverlay: Button
    private lateinit var btnPermBattery: Button
    private lateinit var btnPermFsi: Button
    private lateinit var btnPermA11y: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        spinnerInterval = findViewById(R.id.spinnerInterval)
        customRow = findViewById(R.id.customRow)
        editCustom = findViewById(R.id.editCustom)
        btnPermNotif = findViewById(R.id.btnPermNotif)
        btnPermUsage = findViewById(R.id.btnPermUsage)
        btnPermOverlay = findViewById(R.id.btnPermOverlay)
        btnPermBattery = findViewById(R.id.btnPermBattery)
        btnPermFsi = findViewById(R.id.btnPermFsi)
        btnPermA11y = findViewById(R.id.btnPermA11y)

        spinnerInterval.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, presets.map { it.first }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerInterval.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val seconds = presets[position].second
                if (seconds > 0) {
                    customRow.visibility = View.GONE
                    Prefs.setIntervalSeconds(this@SettingsActivity, seconds)
                } else {
                    customRow.visibility = View.VISIBLE
                    if (editCustom.text.isNullOrBlank()) {
                        editCustom.setText(Prefs.intervalSeconds(this@SettingsActivity).toString())
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        editCustom.setOnEditorActionListener { _, _, _ -> applyCustom(); true }
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
            try { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            catch (_: Exception) { openAppDetails() }
        }
        btnPermOverlay.setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            } catch (_: Exception) { openAppDetails() }
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
                } catch (_: Exception) { openAppDetails() }
            }
        }
        btnPermFsi.setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                )
            } catch (_: Exception) { openAppDetails() }
        }
        btnPermA11y.setOnClickListener {
            try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            catch (_: Exception) { openAppDetails() }
        }
        findViewById<Button>(R.id.btnWizard).setOnClickListener {
            startActivity(Intent(this, WizardActivity::class.java))
        }
        findViewById<TextView>(R.id.versionText).text =
            "解锁提醒 v" + BuildConfig.VERSION_NAME + " · 构建于本地与 GitHub Actions"
        findViewById<Button>(R.id.btnResetStats).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.reset_title))
                .setMessage(getString(R.string.reset_msg))
                .setPositiveButton(getString(R.string.reset_ok)) { _, _ ->
                    StatsStore.resetAll(this)
                    Toast.makeText(this, getString(R.string.reset_done), Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPerms()
        refreshInterval()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) refreshPerms()
    }

    override fun onPause() {
        applyCustom()
        super.onPause()
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

    private fun refreshPerms() {
        btnPermNotif.text = mark(notifGranted(), "① 通知权限")
        btnPermUsage.text = mark(UsageStatsHelper.hasUsageAccess(this), "② 使用情况访问")
        btnPermOverlay.text = mark(Settings.canDrawOverlays(this), "③ 悬浮窗权限")
        val pm = getSystemService(PowerManager::class.java)
        btnPermBattery.text = mark(pm?.isIgnoringBatteryOptimizations(packageName) == true, "④ 电池优化白名单（可选）")
        val fsiVisible = Build.VERSION.SDK_INT >= 34
        btnPermFsi.visibility = if (fsiVisible) View.VISIBLE else View.GONE
        if (fsiVisible) {
            val nm = getSystemService(NotificationManager::class.java)
            btnPermFsi.text = mark(nm?.canUseFullScreenIntent() == true, "⑤ 全屏弹出（全屏意图）")
        }
        btnPermA11y.text = mark(KeepAliveAccessibilityService.isEnabled(this), "⑥ 无障碍保活（防冻结）")
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
