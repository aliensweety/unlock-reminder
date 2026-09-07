package com.suvye.unlockreminder

import android.graphics.PixelFormat
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView

/**
 * 到点提醒的浮层主路径：前台服务直接挂 TYPE_APPLICATION_OVERLAY 全屏 View，
 * 不经过 Activity 启动栈，因此不受各 ROM「后台弹出界面」拦截（只需悬浮窗权限）。
 */
class OverlayReminder(private val host: MonitorService) {

    private var view: View? = null

    /** @return Pair(是否成功, 失败时的异常类别名) */
    fun show(elapsed: Long, usage: List<String>): Pair<Boolean, String> {
        if (view != null) return Pair(true, "")
        val wm = host.getSystemService(WindowManager::class.java)
            ?: return Pair(false, "NoWindowManager")
        val themed = ContextThemeWrapper(host, R.style.Theme_App)
        val v = LayoutInflater.from(themed).inflate(R.layout.view_reminder, null)

        v.findViewById<TextView>(R.id.elapsedText).text =
            host.getString(R.string.elapsed_prefix) + " " + UsageStatsHelper.formatDuration(elapsed)
        v.findViewById<TextView>(R.id.usageText).text = when {
            usage.isNotEmpty() -> usage.joinToString("\n")
            UsageStatsHelper.hasUsageAccess(host) -> host.getString(R.string.usage_empty)
            else -> host.getString(R.string.usage_empty_no_perm)
        }
        v.findViewById<Button>(R.id.btnConfirm).setOnClickListener { host.onOverlayConfirm() }
        v.findViewById<Button>(R.id.btnCancel).setOnClickListener { host.onOverlayCancel() }
        // 返回键只有落在持有焦点的 View 上才会进 OnKeyListener
        v.isFocusable = true
        v.isFocusableInTouchMode = true
        v.requestFocus()
        v.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_BACK) {
                host.onOverlayCancel()
                true
            } else {
                false
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        return try {
            wm.addView(v, params)
            if (!v.isAttachedToWindow) {
                // addView 不抛异常但窗口没真正挂上：当作失败记因
                try {
                    wm.removeView(v)
                } catch (_: Exception) {
                }
                Pair(false, "NotAttached")
            } else {
                view = v
                Pair(true, "")
            }
        } catch (e: Exception) {
            Pair(false, e.javaClass.simpleName)
        }
    }

    fun close() {
        val v = view ?: return
        view = null
        try {
            host.getSystemService(WindowManager::class.java)?.removeView(v)
        } catch (_: Exception) {
        }
    }
}
