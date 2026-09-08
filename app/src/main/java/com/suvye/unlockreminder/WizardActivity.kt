package com.suvye.unlockreminder

import android.app.AppOpsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** 宏软件式保活向导：分品牌直达 + 每步实时验证状态 */
class WizardActivity : AppCompatActivity() {

    private lateinit var tipsText: TextView
    private lateinit var statusNotif: TextView
    private lateinit var statusUsage: TextView
    private lateinit var statusOverlay: TextView
    private lateinit var statusBattery: TextView
    private lateinit var statusAlarm: TextView
    private lateinit var statusOem: TextView
    private lateinit var goOem: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wizard)

        tipsText = findViewById(R.id.wizardTips)
        statusNotif = findViewById(R.id.statusNotif)
        statusUsage = findViewById(R.id.statusUsage)
        statusOverlay = findViewById(R.id.statusOverlay)
        statusBattery = findViewById(R.id.statusBattery)
        statusAlarm = findViewById(R.id.statusAlarm)
        statusOem = findViewById(R.id.statusOem)
        goOem = findViewById(R.id.goOem)

        tipsText.text = OemHelper.brandTips()
        goOem.setOnClickListener {
            if (!OemHelper.openAutoStart(this) && !OemHelper.openPermissionManager(this)) {
                OemHelper.openAppDetails(this)
                Toast.makeText(this, "未找到直达入口，请在应用详情→权限管理里检查", Toast.LENGTH_LONG).show()
            }
        }

        findViewById<Button>(R.id.goNotif).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 200)
            } else {
                try {
                    startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    )
                } catch (_: Exception) {
                    OemHelper.openAppDetails(this)
                }
            }
        }
        findViewById<Button>(R.id.goUsage).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (_: Exception) {
                OemHelper.openAppDetails(this)
            }
        }
        findViewById<Button>(R.id.goOverlay).setOnClickListener { OemHelper.openOverlaySettings(this) }
        findViewById<Button>(R.id.goBattery).setOnClickListener {
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
                    OemHelper.openAppDetails(this)
                }
            }
        }
        findViewById<Button>(R.id.goAlarm).setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))
                )
            } catch (_: Exception) {
                OemHelper.openAppDetails(this)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatuses()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 200) refreshStatuses()
    }

    private fun refreshStatuses() {
        val notifOk = Build.VERSION.SDK_INT < 33 ||
            (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED)
        statusNotif.text = mark(notifOk)

        val appOps = getSystemService(AppOpsManager::class.java)
        val usageOk = if (appOps == null) false else {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            ) == AppOpsManager.MODE_ALLOWED
        }
        statusUsage.text = mark(usageOk)

        statusOverlay.text = mark(Settings.canDrawOverlays(this))

        val pm = getSystemService(PowerManager::class.java)
        statusBattery.text = mark(pm?.isIgnoringBatteryOptimizations(packageName) == true)

        val alarmVisible = Build.VERSION.SDK_INT >= 31
        findViewById<Button>(R.id.goAlarm).visibility =
            if (alarmVisible) Button.VISIBLE else Button.GONE
        if (alarmVisible) {
            val am = getSystemService(android.app.AlarmManager::class.java)
            statusAlarm.text = mark(am?.canScheduleExactAlarms() == true)
        } else {
            statusAlarm.text = "（此系统版本无需）"
        }

        // 厂商自启动/后台弹出无公开检测 API：只能提示用户去确认
        statusOem.text = "□ 请人工确认（系统无检测接口）"
    }

    private fun mark(ok: Boolean): String = if (ok) "✓ 已就绪" else "✗ 未就绪，点右侧开启"
}
