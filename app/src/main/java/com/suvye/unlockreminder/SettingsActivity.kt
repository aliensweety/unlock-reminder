package com.suvye.unlockreminder

import android.Manifest
import android.app.NotificationManager
import android.os.PowerManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** 设置页：权限、保活向导、统计重置。不再有全局时长。 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var btnPermNotif: Button
    private lateinit var btnPermUsage: Button
    private lateinit var btnPermOverlay: Button
    private lateinit var btnPermBattery: Button
    private lateinit var btnPermFsi: Button
    private lateinit var btnPermA11y: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        btnPermNotif = findViewById(R.id.btnPermNotif)
        btnPermUsage = findViewById(R.id.btnPermUsage)
        btnPermOverlay = findViewById(R.id.btnPermOverlay)
        btnPermBattery = findViewById(R.id.btnPermBattery)
        btnPermFsi = findViewById(R.id.btnPermFsi)
        btnPermA11y = findViewById(R.id.btnPermA11y)

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
        val btnStealthDot = findViewById<Button>(R.id.btnStealthDot)
        btnStealthDot.setOnClickListener {
            val next = !Prefs.keepaliveStealth(this)
            Prefs.setKeepaliveStealth(this, next)
            markStealth(btnStealthDot, next)
            startService(Intent(this, MonitorService::class.java).setAction(MonitorService.ACTION_REFRESH_DOT))
        }
        markStealth(btnStealthDot, Prefs.keepaliveStealth(this))
        findViewById<TextView>(R.id.versionText).text = "v" + BuildConfig.VERSION_NAME
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
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) refreshPerms()
    }

    private fun refreshPerms() {
        btnPermNotif.text = mark(notifGranted(), "通知")
        btnPermUsage.text = mark(UsageStatsHelper.hasUsageAccess(this), "使用情况")
        btnPermOverlay.text = mark(Settings.canDrawOverlays(this), "悬浮窗")
        val pm = getSystemService(PowerManager::class.java)
        btnPermBattery.text = mark(pm?.isIgnoringBatteryOptimizations(packageName) == true, "电池优化")
        val fsiVisible = Build.VERSION.SDK_INT >= 34
        btnPermFsi.visibility = if (fsiVisible) View.VISIBLE else View.GONE
        if (fsiVisible) {
            val nm = getSystemService(NotificationManager::class.java)
            btnPermFsi.text = mark(nm?.canUseFullScreenIntent() == true, "全屏弹出")
        }
        btnPermA11y.text = mark(KeepAliveAccessibilityService.isEnabled(this), "无障碍")
    }

    private fun mark(ok: Boolean, label: String): String =
        if (ok) "$label  ✓" else label

    private fun markStealth(btn: Button, on: Boolean) {
        btn.text = if (on) getString(R.string.btn_stealth_dot) + "  ✓" else getString(R.string.btn_stealth_dot)
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
