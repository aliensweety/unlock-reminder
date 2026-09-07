package com.suvye.unlockreminder

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat

class ReminderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ELAPSED = "elapsed"
        const val EXTRA_USAGE = "usage"
    }

    private lateinit var elapsedText: TextView
    private lateinit var usageText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindowFlags()
        setContentView(R.layout.activity_reminder)

        elapsedText = findViewById(R.id.elapsedText)
        usageText = findViewById(R.id.usageText)

        updateViews(intent)
        NotificationManagerCompat.from(this).cancel(MonitorService.NOTIF_ALARM)

        findViewById<Button>(R.id.btnConfirm).setOnClickListener {
            startService(
                Intent(this, MonitorService::class.java)
                    .setAction(MonitorService.ACTION_START_ROUND)
            )
            finish()
        }
        findViewById<Button>(R.id.btnCancel).setOnClickListener { cancelRound() }

        // 若没有使用统计权限，点击说明区域可快捷前往授权
        usageText.setOnClickListener {
            if (!UsageStatsHelper.hasUsageAccess(this)) {
                try {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                } catch (_: Exception) {
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                cancelRound()
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        updateViews(intent)
        NotificationManagerCompat.from(this).cancel(MonitorService.NOTIF_ALARM)
    }

    private fun updateViews(intent: Intent) {
        val elapsed = intent.getLongExtra(EXTRA_ELAPSED, 0L)
        val usage = intent.getStringArrayListExtra(EXTRA_USAGE) ?: arrayListOf()

        elapsedText.text = "${getString(R.string.elapsed_prefix)} ${UsageStatsHelper.formatDuration(elapsed)}"

        usageText.text = when {
            usage.isNotEmpty() -> {
                usage.mapIndexed { index, line ->
                    "${index + 1}.  $line"
                }.joinToString("\n")
            }
            UsageStatsHelper.hasUsageAccess(this) -> getString(R.string.usage_empty)
            else -> getString(R.string.usage_empty_no_perm)
        }
    }

    private fun configureWindowFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun cancelRound() {
        startService(
            Intent(this, MonitorService::class.java)
                .setAction(MonitorService.ACTION_CANCEL_ROUND)
        )
        finish()
    }
}
