package com.suvye.unlockreminder

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * 空壳守护无障碍服务：不监听任何事件、不读取任何屏幕内容（canRetrieveWindowContent=false）。
 *
 * 唯一作用：与系统 AccessibilityManagerService 保持常驻 Binder 绑定。
 * 带活跃无障碍绑定的进程在 ColorOS/MIUI 的速冻（cgroup freezer）白名单里，
 * 到点回调因此不会被冻结延迟——这正是宏软件（MacroDroid/Tasker）弹窗稳定的机制。
 */
class KeepAliveAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    companion object {
        fun isEnabled(ctx: Context): Boolean {
            val enabled = Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any { it.contains(ctx.packageName) }
        }
    }
}
