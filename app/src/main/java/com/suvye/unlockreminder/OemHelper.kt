package com.suvye.unlockreminder

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * 厂商（宏软件式）权限引导：分品牌尝试直达「自启动 / 权限管理」页，
 * 组件名随 ROM 版本变化，全部 try-catch 逐个降级，最终兜底应用详情页。
 */
object OemHelper {

    enum class Brand { XIAOMI, OPPO, VIVO, HUAWEI, HONOR, SAMSUNG, GENERIC }

    fun brand(): Brand = when {
        Build.MANUFACTURER.equals("Xiaomi", true) || Build.MANUFACTURER.equals("Redmi", true) -> Brand.XIAOMI
        Build.MANUFACTURER.equals("OPPO", true) || Build.MANUFACTURER.equals("realme", true) ||
            Build.MANUFACTURER.equals("OnePlus", true) -> Brand.OPPO
        Build.MANUFACTURER.equals("vivo", true) || Build.MANUFACTURER.equals("iQOO", true) -> Brand.VIVO
        Build.MANUFACTURER.equals("HUAWEI", true) -> Brand.HUAWEI
        Build.MANUFACTURER.equals("HONOR", true) -> Brand.HONOR
        Build.MANUFACTURER.equals("samsung", true) -> Brand.SAMSUNG
        else -> Brand.GENERIC
    }

    fun brandName(): String = when (brand()) {
        Brand.XIAOMI -> "小米/Redmi（MIUI/澎湃OS）"
        Brand.OPPO -> "OPPO/realme/一加（ColorOS）"
        Brand.VIVO -> "vivo/iQOO（OriginOS）"
        Brand.HUAWEI -> "华为（EMUI/HarmonyOS）"
        Brand.HONOR -> "荣耀（MagicOS）"
        Brand.SAMSUNG -> "三星（One UI）"
        Brand.GENERIC -> "此设备"
    }

    /** 品牌专属保活提示（向导页顶部文案） */
    fun brandTips(): String = when (brand()) {
        Brand.OPPO -> "ColorOS 保活要点（缺一项就可能到点不弹）：①应用信息→关闭「应用速冻」；②电池→关闭「后台冻结/睡眠待机优化」，允许完全后台行为；③权限管理→允许自启动与「后台弹出界面」；④最近任务下拉锁定本应用。"
        Brand.XIAOMI -> "MIUI/澎湃OS 保活要点（缺一项就可能到点不弹）：①「自启动」开启；②权限管理里「后台弹出界面」「后台显示悬浮窗」都允许；③省电策略改为「无限制」；④最近任务里锁定本应用。"
        Brand.VIVO -> "OriginOS 保活要点（缺一项就可能到点不弹）：①「后台弹窗」允许；②权限管理里允许后台高耗电；③电池设置为允许后台运行；④最近任务锁定本应用。"
        else -> "建议：允许自启动、把电池策略设为不限制、允许显示悬浮窗与后台弹出，并在最近任务中锁定本应用。"
    }

    private fun tryStart(ctx: Context, candidates: List<ComponentName>): Boolean {
        for (cn in candidates) {
            try {
                ctx.startActivity(
                    Intent()
                        .setComponent(cn)
                        .putExtra("packageName", ctx.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return true
            } catch (_: Exception) {
            }
        }
        return false
    }

    /** 厂商自启动管理页；false 表示没有已知直达入口 */
    fun openAutoStart(ctx: Context): Boolean = when (brand()) {
        Brand.OPPO -> tryStart(
            ctx,
            listOf(
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupProfileActivity"),
                ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupProfileActivity"),
                ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupProfileActivity")
            )
        )
        Brand.XIAOMI -> tryStart(
            ctx,
            listOf(
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            )
        )
        Brand.VIVO -> tryStart(
            ctx,
            listOf(
                ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
            )
        )
        Brand.HUAWEI -> tryStart(
            ctx,
            listOf(
                ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
            )
        )
        Brand.HONOR -> tryStart(
            ctx,
            listOf(
                ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
            )
        )
        else -> false
    }

    /** 厂商权限管理页（ColorOS 的「后台弹出界面」在这里面） */
    fun openPermissionManager(ctx: Context): Boolean = when (brand()) {
        Brand.OPPO -> tryStart(
            ctx,
            listOf(
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.PermissionManagerActivity"),
                ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.PermissionManagerActivity"),
                ComponentName("com.oppo.safe", "com.oppo.safe.permission.PermissionManagerActivity")
            )
        )
        Brand.XIAOMI -> tryStart(
            ctx,
            listOf(
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity"),
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsActivity")
            )
        )
        else -> false
    }

    fun openOverlaySettings(ctx: Context): Boolean = try {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (_: Exception) {
        false
    }

    fun openAppDetails(ctx: Context): Boolean = try {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (_: Exception) {
        false
    }
}
