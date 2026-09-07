# 解锁提醒 v0.2.0 · Grok 变体

对照 `spec.md` / `interaction.md` 通读 v0.1.0 后的评审与实现说明。未改 applicationId / 包名 / minSdk 27 / targetSdk 34；未新增第三方依赖与 `<uses-permission>`。

## 问题清单（v0.1.0 评审）

### 缺陷 / 兼容性（已修）

1. **到点通知缺少 contentIntent**  
   仅设 `fullScreenIntent`。屏幕已亮、用户正在使用其它 App 时，多数 ROM 不会自动全屏，点按通知也可能进不去提醒页。已补 `setContentIntent`。

2. **有悬浮窗时仍发 FSI 到点通知**  
   spec：有悬浮窗 → `startActivity`；否则高优先级通知 + FSI 兜底。原实现两条路同时走，提醒页与「时间到」通知叠在一起，点通知还会再拉起 `singleTask` 页。现仅在未弹出全屏时发兜底通知。

3. **确定后再来一轮，到点通知不撤**  
   `autoCancel` 只在点通知时生效。点「确定」只发 `START_ROUND`，`NOTIF_ALARM` 残留。现 `startRound` / 提醒页 `onCreate` 都会 cancel。

4. **Android 14 `startForeground` 未带 FGS type**  
   target 34 时 specialUse 服务应调用三参数 `startForeground(..., FOREGROUND_SERVICE_TYPE_SPECIAL_USE)`，否则部分机型抛 `MissingForegroundServiceTypeException`。

5. **动态广播未指定 exported 标志**  
   Android 13+ `registerReceiver` 必须带 flag。`USER_PRESENT` / `SCREEN_OFF` 为受保护系统广播，现用 `ContextCompat.RECEIVER_EXPORTED`。

6. **服务被杀后倒计时丢失**  
   Handler 回调随进程消失；`START_STICKY` 重启后不恢复本轮。主界面 `isRunning` 为 true 也不拉起服务（`onDestroy` 还曾把开关写成 false）。现：持久化 `fire_at`、重启后补 `postDelayed`；主界面 `onResume` 若开关为开则 `startForegroundService`；用户关开关才清本轮。

7. **UsageEvents 漏计「本轮开始时已在前台」的应用**  
   只从 `roundStart` 起看 RESUMED/PAUSED，配对不到开始前的 RESUMED。现向前回看 6 小时，时长仍夹紧到 `[from, to]`；孤立 PAUSED 按 `[from, paused]` 补一段。

8. **提醒页使用列表不可滚动**  
   8 条 + 长文案会顶到按钮下方。现内容区 `ScrollView`，按钮钉在底部。

9. **权限入口未显示 ✓/✗**  
   交互稿要求四个入口实时 ✓/✗，v0.1.0 只有「已允许/未允许」。

10. **关监控后倒计时通知可能残留**  
    倒计时通知不是 FGS 通知，`stopService` 不会自动撤。`onDestroy` 现会 cancel 倒计时/到点通知。

11. **提醒页「确定/取消」在监控已关时仍 `startService`**  
    用户从最近任务关掉开关后，点确定会把服务重新拉起来。现仅当 `Prefs.isRunning` 为 true 才发命令。

### 风险（缓解，无法根除）

12. **chronometer 倒计时在国产 ROM 上不稳定**  
    部分 MIUI/ColorOS/Harmony 会冻结低重要度通知里的 chronometer。新频道 `countdown2` 用 `IMPORTANCE_DEFAULT` + 无声，并补 `setShowWhen(true)` / 公开可见性 / 「停止本轮」Action。系统仍可能不走秒，这是 ROM 限制。

13. **Android 14 全屏通知可被用户关掉**  
    `USE_FULL_SCREEN_INTENT` 默认有，用户可在系统通知设置里关。spec 只有四个权限入口，不新增第五个；使用说明第 7 条提示。无悬浮窗且 FSI 被关时，只能点高优先级通知进入。

14. **后台启动 Activity（Android 10+ BAL）**  
    有 `SYSTEM_ALERT_WINDOW` 时系统允许后台 `startActivity`，这是主路径。无悬浮窗则走 FSI 兜底，符合 spec，不能保证「无视当前应用直接置顶」。

15. **前台服务仍可能被国产 ROM 杀掉**  
    已做 sticky 恢复、回前台拉起、开机/覆盖安装恢复。自启动白名单仍要用户在系统里开，hint 已写。

16. **`getApplicationInfo(pkg, 0)` 在 API 33+ 过时**  
    已改为 `ApplicationInfoFlags.of(0)`。

### 未改（偏离 spec 或收益不够）

- 不做历史库/白名单/番茄钟。  
- 不新增权限。  
- 不把倒计时改成每秒 `notify` 刷新（spec 明确用 chronometer 免更新）。  
- Android 14 `canUseFullScreenIntent()` 不单独做权限行。

---

## 改动清单（文件 → 改了什么 / 为什么）

### 业务逻辑（小步）

| 文件 | 改了什么 | 为什么 |
|---|---|---|
| `app/src/main/java/.../MonitorService.kt` | 有悬浮窗且 `startActivity` 成功则不再发到点通知；失败/无权限才 FSI + `contentIntent` | 对齐 spec F3，避免双通道叠弹 |
| 同上 | API 34 `startForeground` 带 `SPECIAL_USE` | 避免 Android 14 缺 type 崩溃 |
| 同上 | `ContextCompat.registerReceiver(..., RECEIVER_EXPORTED)` | Android 13+ 动态广播合规，系统解锁/灭屏能送到 |
| 同上 | 持久化 `fireAt`；sticky/空 Intent 时 `restoreRoundIfNeeded`；错过超过 2 分钟则丢弃陈旧轮 | 服务被杀后倒计时还能续，过期轮不突然弹「时间到」 |
| 同上 | `onDestroy` 只撤通知、不改 `isRunning`、不清本轮 | 区分「用户关开关」与「系统杀掉」；后者才能恢复 |
| 同上 | 倒计时改频道 `countdown2`（DEFAULT、无声）+ `setShowWhen` + Action「停止本轮」 | 提高 chronometer 可见性，点按/按钮都能停本轮 |
| 同上 | `startRound` 取消 `NOTIF_ALARM` | 确定再来一轮后不留到点通知 |
| `app/src/main/java/.../Prefs.kt` | 增加 `fire_at`；`setRound` / `fireAt`；`clearRound` 同时清两项 | 状态行与恢复用真实到点时间，改间隔不影响已在走的 Handler |
| `app/src/main/java/.../UsageStatsHelper.kt` | 回看 6h 再夹紧到 `[from,to]`；API 33+ 新 `getApplicationInfo` | 补本轮开始时已在前台的应用；去掉过时 API |
| `app/src/main/java/.../MainActivity.kt` | 开关关：先 `setRunning(false)+clearRound` 再 `stopService`；`onResume` 开关为开则拉起 FGS | 关开关真正停；被杀后回前台能救活 |
| 同上 | 状态文案改为「等待解锁 / 倒计时剩余 n 秒 / 已停止」；权限按钮「名称 + ✓/✗」 | 对齐交互稿场景 4 |
| 同上 | Spinner 换成 ExposedDropdownMenu；自定义间隔用 `TextInputLayout` | Material 3，行为仍是预设 + 5–86400 自定义 |
| `app/src/main/java/.../ReminderActivity.kt` | 打开即 cancel 到点通知；确定/取消仅在监控仍开时发服务命令；`startForegroundService` | 不残留通知；监控已关时不误拉服务 |
| 同上 | 使用列表改成分行 item | 可读性；空态仍分「无记录 / 无权限」 |
| `app/src/main/java/.../BootReceiver.kt` | `startForegroundService` 包 try/catch | 部分 ROM 开机限制 FGS 时避免 receiver 崩溃 |
| `app/src/main/AndroidManifest.xml` | 主界面 `adjustResize`；提醒页 `Theme.App.Reminder` | 自定义间隔键盘；提醒页始终深色、不闪白天主题 |
| `app/build.gradle` | `versionCode 2` / `versionName 0.2.0` | 本轮版本号 |

### UI（Material 3）

| 文件 | 改了什么 | 为什么 |
|---|---|---|
| `res/values/colors.xml` `values-night/colors.xml` | 青绿 + 琥珀角色色，含 reminder 专用色 | 替换默认紫，日夜间与提醒页对比度稳定 |
| `res/values/themes.xml` `values-night/themes.xml` | Light/Dark NoActionBar + 独立 `Theme.App.Reminder` | 主界面跟随系统；全屏提醒始终深色 |
| `res/values/styles.xml` | Card / 权限按钮 / 提醒页确定·取消 | 圆角、无阴影填充卡、权限行对齐 |
| `res/layout/activity_main.xml` | 标题区、状态卡、监控卡、权限卡、说明卡 | v0.1.0 直排控件过素；结构仍是唯一设置页 |
| `res/layout/activity_reminder.xml` | 琥珀「时间到」、使用时长卡、底栏双按钮、可滚动 | 全屏打断更清晰，长列表不挡按钮 |
| `res/layout/item_usage_row.xml` | 应用名 + 时长一行 | 降序前 8 条的展示 |
| `res/drawable/ic_hero_timer.xml` 及 `ic_perm_*.xml` `bg_status_dot.xml` | 矢量图标 | 不新增依赖，按钮/状态点用 |
| `res/values/strings.xml` | 状态/权限/Toast/预设全部入 string | 界面中文；去掉 Kotlin 里散落文案 |
| `ic_launcher` 背景 | `#0F6C6A` | 与主题主色一致 |
| `README.md` | 补充 v0.2.0 一行 | 版本说明 |

### 资源自查

Kotlin 引用的 `@id`（statusCard/statusDot/statusText/switchMonitor/intervalDropdown/customRow/editCustom/btnTest/btnPerm*/elapsedText/usageEmpty/usageList/btnConfirm/btnCancel/usageAppName/usageAppDuration）均在对应 layout 中声明。  
`@string/@drawable/@color/@style/@mipmap` 均有定义；日夜间 `md_*` 与 `reminder_*` 键名对齐。  
未引用不存在的 ConstraintLayout 库。

## 主控补丁（编译修复）
- nonTransitiveRClass 下 App R 不含库属性：MainActivity 的 R.attr.* 引用全部改为 R.color.md_*（日/夜均已定义），colorAttr 直接 getColor。
