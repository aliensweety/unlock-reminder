package com.suvye.unlockreminder

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat

class ReminderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_HEADING = "heading"
        const val EXTRA_ELAPSED = "elapsed"
        const val EXTRA_USAGE = "usage"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.view_reminder)
        // 部分 ROM 会忽略清单里的 showWhenLocked/turnScreenOn，代码层面再设一遍
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        findViewById<Button>(R.id.btnConfirm).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnCancel).setOnClickListener { cancelRound() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })

        bind(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bind(intent)
    }

    private fun bind(intent: Intent) {
        // 走到提醒页就清掉兜底的高优通知，避免处理后残留；
        // 自诊断记录改为追加：保留前面链路（如「浮层失败(Xxx)」）的信息
        NotificationManagerCompat.from(this).cancel(MonitorService.NOTIF_ALARM)
        val cur = Prefs.lastFireResult(this)
        if (!cur.endsWith("提醒页 ✓")) {
            Prefs.setLastFire(this, if (cur.isEmpty()) "提醒页已打开 ✓" else "$cur → 提醒页 ✓")
        }

        findViewById<TextView>(R.id.reminderTitle).text =
            intent.getStringExtra(EXTRA_HEADING) ?: getString(R.string.time_up)

        val elapsed = intent.getLongExtra(EXTRA_ELAPSED, 0L)
        val usage = intent.getStringArrayListExtra(EXTRA_USAGE) ?: arrayListOf()

        findViewById<TextView>(R.id.elapsedText).text =
            getString(R.string.elapsed_prefix) + " " + UsageStatsHelper.formatDuration(elapsed)

        findViewById<TextView>(R.id.usageText).text = when {
            usage.isNotEmpty() -> usage.joinToString("\n")
            UsageStatsHelper.hasUsageAccess(this) -> getString(R.string.usage_empty)
            else -> getString(R.string.usage_empty_no_perm)
        }
    }

    private fun cancelRound() {
        // 监控已关时不要把服务误拉活，只关闭提醒页
        if (Prefs.isRunning(this)) {
            startService(
                Intent(this, MonitorService::class.java)
                    .setAction(MonitorService.ACTION_CANCEL_ROUND)
            )
        }
        finish()
    }
}
