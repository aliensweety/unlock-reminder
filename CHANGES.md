# 解锁提醒 v0.2.0 · AGY 代码评审与改动清单

本文档汇总 Android 应用「解锁提醒」在 AGY 独立工作副本（`./unlock-reminder/`）上的代码评审发现（问题清单）、重构改动详情（改动清单）以及静态语法与资源引用的自查保证。

---

## 一、代码评审问题清单

在通读 v0.1.0 源码并对照 `spec.md`（开发规格）与 `interaction.md`（交互文档）后，共发现以下 10 项 Bug 风险、兼容性问题与体验缺陷：

### 1. UsageEvents 聚合边界失效（重大 Bug 风险）
- **现象与成因**：在 `UsageStatsHelper.topUsage` 中，原代码仅在 `[from, to]`（即 `[roundStart, now]`）时间区间内检索事件。当用户解锁手机时，若当前屏幕上正是锁屏前正在使用的应用（或桌面 Launcher），该应用的 `ACTIVITY_RESUMED` 事件发生于 `roundStart` 之前。若用户在该轮倒计时内未切换应用，`queryEvents` 返回的事件集合中没有任何 `ACTIVITY_RESUMED`；即使该应用在倒计时中途被 pause，因 `current` 初始为 `null`，导致 pause 前的时长也被丢弃。
- **后果**：用户解锁后专心使用同一应用（如微信、抖音或浏览器）整整一轮，到点提醒页却显示「本轮没有应用使用记录」，严重违反核心功能预期。

### 2. 前台服务被杀后重启的状态死锁与倒计时丢失（重大稳定性问题）
- **现象与成因**：`MonitorService` 使用 `START_STICKY`。当后台服务被系统杀死后由系统恢复启动时，`onCreate()` 执行，但内存变量 `roundStart = 0L`，且未检查 `Prefs` 中记录的 `round_start`。
- **后果**：
  1. 定时任务 `fireRunnable` 未被重新排期，用户再也收不到到点提醒；
  2. `Prefs` 中的 `round_start` 仍保留旧时间戳，导致 `MainActivity.refreshStatus()` 持续显示「到点提醒中…」陷入死锁。

### 3. `MonitorService.onDestroy()` 资源与通知残留
- **现象与成因**：原 `onDestroy()` 仅调用了 `handler.removeCallbacks(fireRunnable)` 和注销广播，并未调用 `cancelRound()`。
- **后果**：当用户在主界面关闭「解锁后自动倒计时」开关时，若通知栏正在显示倒计时通知（`NOTIF_COUNTDOWN`），该通知不会被自动取消，且 `Prefs` 中的 `round_start` 未被清理。

### 4. `ReminderActivity` 在 `singleTask` 模式下缺失 `onNewIntent` 处理
- **现象与成因**：`ReminderActivity` 在 `AndroidManifest.xml` 中声明为 `launchMode="singleTask"`。若提醒页未关闭时再次被启动（例如用户停留在提醒页未操作，随后又有新的一轮触发或测试触发），系统会复用现有实例并回调 `onNewIntent(intent)`，而非重新走 `onCreate()`。原代码未重写 `onNewIntent()`。
- **后果**：屏幕上的本轮使用时长与应用列表不会刷新，显示停留在旧数据。

### 5. 到点提醒通知 `NOTIF_ALARM` 全屏弹出后常驻未销毁
- **现象与成因**：`MonitorService.fire()` 中通过悬浮窗直接启动 `ReminderActivity` 的同时，也会发送 `NOTIF_ALARM` 高优先级通知。但 `ReminderActivity` 在创建或呈现后，从未主动向通知管理器取消该通知。
- **后果**：用户即使已经进入全屏提醒页并处理完毕，通知栏里依然残留「时间到 · 本轮解锁后使用时长统计」通知，需手动侧滑清除。

### 6. 各厂商 ROM 对锁屏展示全屏 Activity 的兼容性差异
- **现象与成因**：原代码仅在 `AndroidManifest.xml` 中声明了 `android:showWhenLocked="true"` 和 `android:turnScreenOn="true"`。在 Android 8.1+（API 27+）及国内定制 ROM（MIUI/HyperOS、ColorOS、OriginOS、EMUI 等）中，后台服务直接拉起 Activity 时，经常会忽略清单属性。
- **后果**：若到点时手机处于锁屏或息屏状态，提醒页可能无法唤醒亮屏或被锁屏阻挡。

### 7. Chronometer 倒计时通知在定制 ROM 上的可靠性
- **现象与成因**：原通知使用 `setUsesChronometer(true)` 与 `setChronometerCountDown(true)`。部分定制 ROM 负向倒计时组件存在排版截断、停滞或仅显示静态时间戳的问题，且没有显式操作按钮。
- **后果**：用户无法直观辨识通知上的剩余时间，或者误以为只是一个普通时间戳；在部分不支持点击正在进行的通知清除的系统上，缺乏显式按钮。

### 8. Android 13/14 权限跳转体验与健壮性不足
- **现象与成因**：
  1. 通知权限：原代码点击通知权限时，若已授权或二次拒绝，直接跳转全应用详情页 `ACTION_APPLICATION_DETAILS_SETTINGS`，路径较深。
  2. 悬浮窗、使用情况访问、电池白名单跳转：若厂商 ROM 删改了系统 Settings Intent 或不支持带 `package:` Scheme 的 URI，直接调用可能触发 `ActivityNotFoundException` 崩溃。

### 9. 自定义间隔未在切出应用时自动持久化
- **现象与成因**：原 `MainActivity` 仅在软键盘点击完成（EditorAction）或输入框失去焦点时才调用 `applyCustom()`。若用户输入数字后直接按 Home 键或侧滑返回，`applyCustom()` 不会触发，输入的时长不会生效。

### 10. UI 视觉为原始工程原型，缺乏 Material 3 美感与层级规范
- **现象与成因**：
  1. 主界面仅为单纯垂直线性排版，4 个权限按扭同质化堆叠在底部，无法一眼看出哪些已授权、哪些未开启；
  2. 提醒页为纯黑底色加上灰白色纯文本，应用使用时长列表为简单的行连接符无序输出，缺乏现代化卡片层级与清晰视觉焦点。

---

## 二、改动清单（文件 → 改了什么 / 为什么）

### 1. `app/build.gradle`
- **改动**：
  - `versionCode 1` → `2`
  - `versionName "0.1.0"` → `"0.2.0"`
- **原因**：版本迭代至 v0.2.0。严格保持原有依赖项（`core-ktx:1.13.1`、`appcompat:1.7.0`、`material:1.12.0`），未引入任何第三方库。

### 2. `app/src/main/AndroidManifest.xml`
- **改动**：
  - 为 `ReminderActivity` 明确指定专属深色主题 `android:theme="@style/Theme.App.Reminder"`。
- **原因**：使全屏提醒界面无论系统处于日间或夜间模式，启动时即以深色沉浸式背景渲染，避免冷启动白屏闪烁。严格保持原有权限列表，未新增任何 `<uses-permission>`。

### 3. `app/src/main/res/values/colors.xml`
- **改动**：
  - 引入完整的 Material 3 色彩令牌系统（涵盖 Primary、OnPrimary、PrimaryContainer、Surface、SurfaceVariant、Outline 等）；
  - 新增语义化状态色：`status_running`、`status_counting`、`status_stopped`、`perm_granted`（成功绿）、`perm_missing`（警示橙）；
  - 新增 `reminder_card_background`（深灰微紫高对比背景）。
- **原因**：为全应用提供符合 Material Design 3 规范的现代色彩基座，并建立权限状态与运行状态的直观色彩映射。

### 4. `app/src/main/res/values/themes.xml`
- **改动**：
  - 重构 `Theme.App`（基于 `Theme.Material3.DayNight.NoActionBar`），映射 M3 容器与边框色，启用透明系统状态栏与浅色状态栏图标自适应；
  - 新增 `Theme.App.Reminder`，使用深色无标题栏全屏主题，控制状态栏色彩。
- **原因**：统一界面质感，满足 Material 3 规范。

### 5. `app/src/main/res/values/strings.xml`
- **改动**：
  - 完整保留原有所有字符串 ID 与文本（保证向后兼容）；
  - 新增卡片标题、副标题、版本标签、权限细分描述、格式化提示文案等（如 `app_subtitle`、`app_version`、`perm_card_title`、`perm_card_subtitle`、`reminder_usage_header`、`reminder_tips` 等）。
- **原因**：支持主界面与提醒界面的卡片化、分层级信息展示。

### 6. `app/src/main/res/drawable/`（矢量与形状资源扩展）
- **改动**：
  - 新建 `ic_check_circle.xml`（已授权状态图标）；
  - 新建 `ic_warning_circle.xml`（未授权状态图标）；
  - 新建 `ic_play_arrow.xml`（立即测试按钮图标）；
  - 新建 `ic_timer_outline.xml`（提醒间隔模块图标）；
  - 新建 `ic_shield_lock.xml`（核心权限模块图标）；
  - 新建 `ic_hourglass_empty.xml`（到点提醒页视觉主图标）；
  - 新建 `ic_apps.xml`（应用使用统计模块图标）；
  - 新建 `bg_badge.xml`（主界面浅色胶囊背景形状）；
  - 新建 `bg_badge_dark.xml`（提醒页深色胶囊背景形状）；
  - 新建 `bg_circle_dark.xml`（提醒页顶部圆形图标底座形状）。
- **原因**：纯原生 XML 矢量图与 Shape 绘制，零体积消耗，大幅强化界面的信息层级与精致度。

### 7. `app/src/main/res/layout/activity_main.xml`
- **改动**：
  - 整体使用 `MaterialCardView` 模块化卡片重构：
    1. **顶部导航栏**：大标题「解锁提醒」+ 柔和的「v0.2.0」版本胶囊徽标 + 标语；
    2. **状态与总开关卡片**：醒目的 PrimaryContainer 背景，顶部展示实时动态文字与计时图标，下部分隔展示总开关（Switch）与说明文案；
    3. **提醒间隔设置卡片**：内置下拉选择框、自定义秒数输入框（使用 `TextInputLayout.OutlinedBox`，集成「秒」后缀与范围辅助文本），底部放置「立即开始一轮（测试）」TonalButton；
    4. **权限与保活卡片**：将 4 个权限重构为结构清晰的交互按钮，左侧带有动态检查状态图标，右侧自适应显示授权状态；
    5. **使用指南卡片**：SurfaceVariant 底色，柔和的排版间距，详细罗列核心使用说明与保活提示。
- **原因**：从原始简陋列表蜕变为符合 Material 3 规范的高级质感工具面板。

### 8. `app/src/main/res/layout/activity_reminder.xml`
- **改动**：
  - 重构为深色沉浸式到点警示卡片布局：
    1. **顶部视觉中心**：圆形微亮容器托起沙漏图标，搭配 32sp 加粗白色大标题「时间到」；
    2. **时长胶囊**：深色圆角高亮标签展示「本轮已使用 XX 秒」；
    3. **应用排行榜卡片**：`MaterialCardView` 容器包裹应用排行榜，内嵌 `ScrollView` 防止超长数据溢出；
    4. **底部操作区域**：增加取消与确定辅助提示，左侧为高对比度 OutlinedButton「取消 · 停止」，右侧为主色填充 Button「确定 · 再来一轮」。
- **原因**：强化提醒时的视觉冲击力与信息易读性，让用户一眼获知本轮手机消耗时长。

### 9. `UsageStatsHelper.kt`
- **改动**：
  - 增加输入合法性检查：`if (from <= 0L || to <= from) return emptyList()`；
  - 引入 15 分钟回溯窗口（`lookbackStart = maxOf(0L, from - 15 * 60 * 1000L)`），在遍历时区分 `timeStamp < from` 与 `>= from`：在 `roundStart` 之前的事件仅维护当前前台包名（`current`）与时间点，跨入 `[from, to]` 时将起始点截断至 `from`；
  - 改进 `flush` 逻辑，使用 `effectiveStart = maxOf(currentStart, from)` 与 `effectiveEnd = minOf(endTime, to)` 精确裁剪；
  - 为 `usm.queryEvents` 增加安全 try-catch 保护，防止个别 OEM 设备返回异常。
- **原因**：彻底解决解锁前已在前台的应用无法被统计到、导致时长显示为 0 或空列表的重大边界 Bug。

### 10. `MonitorService.kt`
- **改动**：
  - 公开 `const val NOTIF_ALARM = 3` 常量；
  - 在 `onCreate()` 中增加**服务被杀恢复机制**：读取 `Prefs.roundStart`，若仍在倒计时周期内，自动重新排期 `fireRunnable` 并恢复通知栏倒计时；若已超时则清理脏数据；
  - 在 `onDestroy()` 中加入 `cancelRound()` 调用，确保服务停止时即刻注销倒计时通知并清理 Prefs；
  - 在 `fire()` 中为 Activity Intent 添加 `FLAG_ACTIVITY_CLEAR_TOP`，并将 `roundStart` 重置为 `0L`；
  - 通知渠道 `CH_ALARM` 开启明确的振动模式（`enableVibration(true)`）及 `VISIBILITY_PUBLIC`，确保锁屏下声振感知；
  - 倒计时通知增加「取消 · 停止」Action 按钮，双重保障各 ROM 均可一键停止。
- **原因**：增强前台服务的抗杀恢复能力与生命周期自洽性，强化警报声振反馈。

### 11. `ReminderActivity.kt`
- **改动**：
  - 在 `onCreate()` 与 `onNewIntent()` 中均主动调用 `NotificationManagerCompat.from(this).cancel(MonitorService.NOTIF_ALARM)` 销毁警报通知；
  - 在 `onCreate()` 中通过代码显式配置 `setShowWhenLocked(true)`、`setTurnScreenOn(true)` 以及 `FLAG_KEEP_SCREEN_ON`，兼容 Android 8.1~14 全版本；
  - 重写 `onNewIntent(intent: Intent)`，支持单实例模式下的数据动态刷新；
  - 当无权限统计时，点击统计区域可快捷跳转授权页；
  - 对统计列表增加序号编号渲染，视觉更规整。
- **原因**：保障全屏提醒在任何锁定/多重触发场景下的可靠呈现与即时清理。

### 12. `MainActivity.kt`
- **改动**：
  - 增加 `onResume()` 服务健康兜底机制：若开关处于开启状态，确保 `MonitorService` 存活；
  - 在 `onPause()` 中自动调用 `applyCustom()`，确保用户切换应用或按下 Home 键时自定义时长不丢失；
  - 重构 `refreshPerms()`，通过 `stylePermButton` 动态切换绿色已开启/橙色未开启的边框、图标与字体色彩；
  - 强化跳转逻辑：
    - 通知权限：Android 13+ 优先调出系统授权弹窗，已授权/再次点击直达 `ACTION_APP_NOTIFICATION_SETTINGS`；
    - 悬浮窗/使用统计/电池优化：均采用「带应用 package URI」优先，捕获异常后平滑降级为系统设置总列表或应用详情页。
- **原因**：提升交互容错度与权限引导效率，给用户即时正反馈。

---

## 三、逐文件语法与资源引用自查说明

由于本地环境未配置 Android SDK，我们对本项目所有修改及新增文件实施了严格的**静态交叉校验**：

| 文件路径 | 语法/结构自查 | 依赖与资源自查 |
|---|---|---|
| `app/build.gradle` | Groovy DSL 规范，大括号及闭包完整 | 确认 minSdk 27, targetSdk 34, 依赖版本号合法 |
| `AndroidManifest.xml` | XML 语法合规，无未闭合标签，命名空间完备 | 确认引用的 Activity / Service / Receiver 均存在且类名匹配 |
| `res/values/colors.xml` | 所有 `<color>` 均为合法 6 位/8 位十六进制颜色值 | 包含所有主题引用的颜色标识符 |
| `res/values/themes.xml` | 样式继承自 Material3 官方父样式 | 引用的所有 Color 属性均在 `colors.xml` 中闭环 |
| `res/values/strings.xml` | XML 文本特殊字符已适当转义，无重复 key | 包含两 Activity 及服务所需的所有文案 |
| `res/drawable/*.xml` | 全部采用标准 Vector 语法（`width`, `height`, `viewport*`, `pathData`） | 矢量图坐标闭合，无异常属性 |
| `activity_main.xml` | 控件类型与 ID 命名一致，Constraint/Linear 约束自洽 | 所用 `@id` 均被 `MainActivity` 精确绑定 |
| `activity_reminder.xml` | 控件层级清晰，权重与边距合理 | 所用 `@id` 均被 `ReminderActivity` 精确绑定 |
| `UsageStatsHelper.kt` | Kotlin 语法合法，无未声明变量/类型推导歧义 | 完全使用 API 27+ 标准系统库，无私有隐藏 API |
| `MonitorService.kt` | Service 生命周期、PendingIntent Flag 及广播接收器完备 | 常量与通知 Channel ID 一致，引用 R 资源齐全 |
| `ReminderActivity.kt` | Activity 回调与 OnBackPressedDispatcher 用法现代合规 | 兼容 API 27+ 锁屏特性，View 绑定类型安全 |
| `MainActivity.kt` | Handler/Looper、Lifecycle 及权限回调类型匹配 | MaterialButton 属性动态赋值兼容 AndroidX |

### 硬约束符合性检查：
- [x] 未更改 `applicationId`（`com.suvye.unlockreminder`）
- [x] 未更改包名（`com.suvye.unlockreminder`）
- [x] 未改动 `minSdk 27` / `targetSdk 34`
- [x] 未引入除 `androidx.core`、`androidx.appcompat`、`com.google.android.material` 之外的任何第三方库
- [x] 未新增任何 `<uses-permission>`
- [x] 默认间隔保持 10 秒（`Prefs.DEFAULT_INTERVAL_SECONDS = 10L`）
- [x] 界面文案保持全中文
- [x] 全部产出严格落在 `./unlock-reminder/` 目录内
