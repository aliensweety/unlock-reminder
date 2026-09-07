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
import android.util.TypedValue
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class MainActivity : AppCompatActivity() {

    private val presets by lazy {
        listOf(
            getString(R.string.preset_10s) to 10L,
            getString(R.string.preset_30s) to 30L,
            getString(R.string.preset_1m) to 60L,
            getString(R.string.preset_3m) to 180L,
            getString(R.string.preset_5m) to 300L,
            getString(R.string.preset_10m) to 600L,
            getString(R.string.preset_15m) to 900L,
            getString(R.string.preset_30m) to 1800L,
            getString(R.string.preset_custom) to -1L
        )
    }

    private lateinit var statusCard: MaterialCardView
    private lateinit var statusDot: View
    private lateinit var statusText: android.widget.TextView
    private lateinit var switchMonitor: MaterialSwitch
    private lateinit var intervalDropdown: MaterialAutoCompleteTextView
    private lateinit var customRow: TextInputLayout
    private lateinit var editCustom: TextInputEditText
    private lateinit var btnPermNotif: MaterialButton
    private lateinit var btnPermUsage: MaterialButton
    private lateinit var btnPermOverlay: MaterialButton
    private lateinit var btnPermBattery: MaterialButton

    private val handler = Handler(Looper.getMainLooper())
    private var suppressSwitch = false
    private var suppressInterval = false

    private val ticker = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusCard = findViewById(R.id.statusCard)
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        switchMonitor = findViewById(R.id.switchMonitor)
        intervalDropdown = findViewById(R.id.intervalDropdown)
        customRow = findViewById(R.id.customRow)
        editCustom = findViewById(R.id.editCustom)
        btnPermNotif = findViewById(R.id.btnPermNotif)
        btnPermUsage = findViewById(R.id.btnPermUsage)
        btnPermOverlay = findViewById(R.id.btnPermOverlay)
        btnPermBattery = findViewById(R.id.btnPermBattery)

        val labels = presets.map { it.first }
        intervalDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels)
        )
        intervalDropdown.keyListener = null
        intervalDropdown.setOnClickListener { intervalDropdown.showDropDown() }
        intervalDropdown.setOnItemClickListener { _, _, position, _ ->
            if (suppressInterval) return@setOnItemClickListener
            val seconds = presets[position].second
            if (seconds > 0) {
                customRow.visibility = View.GONE
                customRow.error = null
                Prefs.setIntervalSeconds(this, seconds)
            } else {
                customRow.visibility = View.VISIBLE
                if (editCustom.text.isNullOrBlank()) {
                    editCustom.setText(Prefs.intervalSeconds(this).toString())
                }
            }
        }

        switchMonitor.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            if (checked) {
                applyCustom()
                Prefs.setRunning(this, true)
                ContextCompat.startForegroundService(this, Intent(this, MonitorService::class.java))
                Toast.makeText(this, R.string.toast_monitor_on, Toast.LENGTH_SHORT).show()
            } else {
                Prefs.setRunning(this, false)
                Prefs.clearRound(this)
                stopService(Intent(this, MonitorService::class.java))
                Toast.makeText(this, R.string.toast_monitor_off, Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<MaterialButton>(R.id.btnTest).setOnClickListener {
            if (!Prefs.isRunning(this)) {
                Toast.makeText(this, R.string.toast_need_switch, Toast.LENGTH_SHORT).show()
            } else {
                applyCustom()
                ContextCompat.startForegroundService(
                    this,
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
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.isRunning(this)) {
            try {
                ContextCompat.startForegroundService(this, Intent(this, MonitorService::class.java))
            } catch (_: Exception) {
            }
        }
        refreshAll()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        applyCustom()
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
        suppressInterval = true
        if (position >= 0) {
            intervalDropdown.setText(presets[position].first, false)
            customRow.visibility = View.GONE
            customRow.error = null
        } else {
            intervalDropdown.setText(presets.last().first, false)
            customRow.visibility = View.VISIBLE
            if (editCustom.text.isNullOrBlank()) editCustom.setText(current.toString())
        }
        suppressInterval = false
    }

    private fun refreshStatus() {
        val running = Prefs.isRunning(this)
        val roundStart = Prefs.roundStart(this)
        val fireAt = Prefs.fireAt(this)
        val countdownActive = running && roundStart > 0
        val remaining = if (countdownActive) {
            val end = if (fireAt > 0L) fireAt else roundStart + Prefs.intervalSeconds(this) * 1000
            end - System.currentTimeMillis()
        } else {
            0L
        }
        statusText.text = when {
            countdownActive && remaining > 0 -> getString(R.string.status_countdown, remaining / 1000 + 1)
            countdownActive -> getString(R.string.status_firing)
            running -> getString(R.string.status_waiting)
            else -> getString(R.string.status_stopped)
        }

        val containerAttr: Int
        val onContainerAttr: Int
        when {
            countdownActive && remaining > 0 -> {
                containerAttr = R.color.md_tertiary_container
                onContainerAttr = R.color.md_on_tertiary_container
            }
            running -> {
                containerAttr = R.color.md_primary_container
                onContainerAttr = R.color.md_on_primary_container
            }
            else -> {
                containerAttr = R.color.md_surface_container_high
                onContainerAttr = R.color.md_on_surface_variant
            }
        }
        val container = colorAttr(containerAttr)
        val onContainer = colorAttr(onContainerAttr)
        statusCard.setCardBackgroundColor(container)
        statusText.setTextColor(onContainer)
        statusDot.backgroundTintList = ColorStateList.valueOf(onContainer)
    }

    private fun refreshPerms() {
        bindPerm(btnPermNotif, getString(R.string.perm_notif), notifGranted())
        bindPerm(btnPermUsage, getString(R.string.perm_usage), UsageStatsHelper.hasUsageAccess(this))
        bindPerm(btnPermOverlay, getString(R.string.perm_overlay), Settings.canDrawOverlays(this))
        val pm = getSystemService(PowerManager::class.java)
        val battery = pm?.isIgnoringBatteryOptimizations(packageName) == true
        bindPerm(btnPermBattery, getString(R.string.perm_battery), battery)
    }

    private fun bindPerm(button: MaterialButton, name: String, granted: Boolean) {
        val mark = getString(if (granted) R.string.perm_granted else R.string.perm_denied)
        button.text = "$name  $mark"
        button.setBackgroundColor(
            colorAttr(if (granted) R.color.md_secondary_container else R.color.md_surface_container_high)
        )
        button.setTextColor(
            colorAttr(if (granted) R.color.md_on_secondary_container else R.color.md_on_surface)
        )
        button.iconTint = ColorStateList.valueOf(
            colorAttr(if (granted) R.color.md_on_secondary_container else R.color.md_on_surface_variant)
        )
    }

    private fun applyCustom() {
        if (customRow.visibility != View.VISIBLE) return
        val text = editCustom.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        val value = text.toLongOrNull()
        if (value == null) return
        if (value < Prefs.MIN_INTERVAL_SECONDS || value > Prefs.MAX_INTERVAL_SECONDS) {
            customRow.error = getString(
                R.string.toast_interval_range,
                Prefs.MIN_INTERVAL_SECONDS,
                Prefs.MAX_INTERVAL_SECONDS
            )
            Toast.makeText(
                this,
                getString(R.string.toast_interval_range, Prefs.MIN_INTERVAL_SECONDS, Prefs.MAX_INTERVAL_SECONDS),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        customRow.error = null
        if (value != Prefs.intervalSeconds(this)) {
            Prefs.setIntervalSeconds(this, value)
            Toast.makeText(this, getString(R.string.toast_interval_set, value), Toast.LENGTH_SHORT).show()
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

    private fun colorAttr(colorRes: Int): Int = getColor(colorRes)
}
