package com.suvye.unlockreminder

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

class ReminderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ELAPSED = "elapsed"
        const val EXTRA_USAGE = "usage"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reminder)

        val elapsed = intent.getLongExtra(EXTRA_ELAPSED, 0L)
        val usage = intent.getStringArrayListExtra(EXTRA_USAGE) ?: arrayListOf()

        findViewById<TextView>(R.id.elapsedText).text =
            getString(R.string.elapsed_prefix) + " " + UsageStatsHelper.formatDuration(elapsed)

        val usageView = findViewById<TextView>(R.id.usageText)
        usageView.text = when {
            usage.isNotEmpty() -> usage.joinToString("\n")
            UsageStatsHelper.hasUsageAccess(this) -> getString(R.string.usage_empty)
            else -> getString(R.string.usage_empty_no_perm)
        }

        findViewById<Button>(R.id.btnConfirm).setOnClickListener {
            startService(
                Intent(this, MonitorService::class.java)
                    .setAction(MonitorService.ACTION_START_ROUND)
            )
            finish()
        }
        findViewById<Button>(R.id.btnCancel).setOnClickListener { cancelRound() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                cancelRound()
            }
        })
    }

    private fun cancelRound() {
        startService(
            Intent(this, MonitorService::class.java)
                .setAction(MonitorService.ACTION_CANCEL_ROUND)
        )
        finish()
    }
}
