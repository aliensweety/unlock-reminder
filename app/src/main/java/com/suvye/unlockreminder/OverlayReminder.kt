package com.suvye.unlockreminder

import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView

/**
 * 到点提醒的浮层主路径。ColorOS/MIUI 对后台可聚焦全屏浮层可能「添加成功但不显示」，
 * 所以挂载后 500ms 异步验证 isAttachedToWindow（窗口挂载是下一帧完成的，同步查必误报）；
 * 失败自动降级为不可聚焦变体重试；再失败交给服务走 Activity/通知兜底。
 */
class OverlayReminder(private val host: MonitorService) {

    private var view: View? = null
    private var heading = ""
    private var elapsed = 0L
    private var usage: List<String> = emptyList()
    private var attempt = 0
    private val handler = Handler(Looper.getMainLooper())
    private val verifyRunnable = Runnable { verify() }

    fun isShowing(): Boolean = view != null

    fun show(heading: String, elapsedMs: Long, usageList: List<String>): Boolean {
        if (view != null) return true
        this.heading = heading
        elapsed = elapsedMs
        usage = usageList
        attempt = 0
        return tryAdd()
    }

    private fun tryAdd(): Boolean {
        val wm = host.getSystemService(WindowManager::class.java) ?: return false
        // 服务上下文包一层 M3 主题，保证 Material 组件可靠 inflate
        val themed = ContextThemeWrapper(host, R.style.Theme_App)
        val v = LayoutInflater.from(themed).inflate(R.layout.view_reminder, null)

        v.findViewById<TextView>(R.id.reminderTitle).text = heading
        v.findViewById<TextView>(R.id.elapsedText).text =
            host.getString(R.string.elapsed_prefix) + " " + UsageStatsHelper.formatDuration(elapsed)
        v.findViewById<TextView>(R.id.usageText).text = when {
            usage.isNotEmpty() -> usage.joinToString("\n")
            UsageStatsHelper.hasUsageAccess(host) -> host.getString(R.string.usage_empty)
            else -> host.getString(R.string.usage_empty_no_perm)
        }
        v.findViewById<Button>(R.id.btnConfirm).setOnClickListener { host.onOverlayConfirm() }
        v.findViewById<Button>(R.id.btnCancel).setOnClickListener { host.onOverlayCancel() }
        // 返回键只有落在持有焦点的 View 上才会进 OnKeyListener（仅可聚焦变体有效）
        if (attempt == 1) {
            v.isFocusable = true
            v.isFocusableInTouchMode = true
            v.requestFocus()
            v.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_BACK) {
                    host.onOverlayConfirm()
                    true
                } else {
                    false
                }
            }
        }

        // 三连击阶梯（Grok 建议：ColorOS 拦的是抢焦点的全屏窗，小窗往往放行）：
        // 0=全屏不可聚焦 → 1=全屏可聚焦 → 2=居中小窗不可聚焦
        val baseFlags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            if (attempt == 2) WindowManager.LayoutParams.WRAP_CONTENT
            else WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            when (attempt) {
                0 -> baseFlags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                1 -> baseFlags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                else -> baseFlags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            },
            PixelFormat.TRANSLUCENT
        )
        if (attempt == 2) params.gravity = android.view.Gravity.CENTER
        return try {
            wm.addView(v, params)
            view = v
            android.util.Log.d("URFire", "tryAdd ok attempt=$attempt attached=${v.isAttachedToWindow}")
            handler.postDelayed(verifyRunnable, 500L)
            true
        } catch (e: Exception) {
            android.util.Log.d("URFire", "tryAdd FAIL attempt=$attempt ${e.javaClass.simpleName}")
            false
        }
    }

    private fun verify() {
        val v = view ?: return
        val attached = v.isAttachedToWindow && v.windowToken != null
        android.util.Log.d("URFire", "verify attempt=$attempt attached=$attached token=${v.windowToken != null} vis=${v.visibility} alpha=${v.alpha} w=${v.width} h=${v.height}")
        if (attached) {
            host.onOverlayShown()
        } else if (attempt < 2) {
            attempt++
            removeQuietly()
            if (!tryAdd()) host.onOverlayFailed("RetryAddFailed")
        } else {
            removeQuietly()
            host.onOverlayFailed("NotAttached")
        }
    }

    private fun removeQuietly() {
        handler.removeCallbacks(verifyRunnable)
        val v = view ?: return
        view = null
        try {
            host.getSystemService(WindowManager::class.java)?.removeView(v)
        } catch (_: Exception) {
        }
    }

    fun close() {
        removeQuietly()
    }
}
