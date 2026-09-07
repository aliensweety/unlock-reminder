package com.suvye.unlockreminder

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

class ReminderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ELAPSED = "elapsed"
        const val EXTRA_USAGE = "usage"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        setContentView(R.layout.activity_reminder)
        NotificationManagerCompat.from(this).cancel(MonitorService.NOTIF_ALARM)

        val elapsed = intent.getLongExtra(EXTRA_ELAPSED, 0L)
        val usage = intent.getStringArrayListExtra(EXTRA_USAGE) ?: arrayListOf()

        findViewById<TextView>(R.id.elapsedText).text =
            getString(R.string.elapsed_prefix) + " " + UsageStatsHelper.formatDuration(elapsed)

        bindUsage(usage)

        findViewById<MaterialButton>(R.id.btnConfirm).setOnClickListener {
            if (Prefs.isRunning(this)) {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, MonitorService::class.java)
                        .setAction(MonitorService.ACTION_START_ROUND)
                )
            }
            finish()
        }
        findViewById<MaterialButton>(R.id.btnCancel).setOnClickListener { cancelRound() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                cancelRound()
            }
        })
    }

    private fun bindUsage(usage: List<String>) {
        val list = findViewById<LinearLayout>(R.id.usageList)
        val empty = findViewById<TextView>(R.id.usageEmpty)
        list.removeAllViews()
        if (usage.isEmpty()) {
            empty.visibility = View.VISIBLE
            empty.text = if (UsageStatsHelper.hasUsageAccess(this)) {
                getString(R.string.usage_empty)
            } else {
                getString(R.string.usage_empty_no_perm)
            }
            return
        }
        empty.visibility = View.GONE
        val inflater = LayoutInflater.from(this)
        usage.forEach { line ->
            val row = inflater.inflate(R.layout.item_usage_row, list, false)
            val sep = line.lastIndexOf(" · ")
            val name = if (sep >= 0) line.substring(0, sep) else line
            val duration = if (sep >= 0) line.substring(sep + 3) else ""
            row.findViewById<TextView>(R.id.usageAppName).text = name
            row.findViewById<TextView>(R.id.usageAppDuration).text = duration
            list.addView(row)
        }
    }

    private fun cancelRound() {
        if (Prefs.isRunning(this)) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, MonitorService::class.java)
                    .setAction(MonitorService.ACTION_CANCEL_ROUND)
            )
        }
        finish()
    }
}
