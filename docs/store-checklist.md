# 上架合规清单（store readiness）

技术底子（已具备）：应用零网络权限 = 隐私故事最强形态；悬浮窗路线本身可上架（One Sec 等 Play 在售先例）；无障碍路线刻意不用。

## Google Play

| 项 | 要求 | 状态 |
|---|---|---|
| PACKAGE_USAGE_STATS | 受限权限：核心功能声明 + 使用场景视频（演示"到点显示各应用时长"这一核心闭环） | 待做：Play Console 申报 |
| QUERY_ALL_PACKAGES | 受限权限 | ✅ 已规避：Manifest 用 `<queries>`（带 LAUNCHER 入口的应用），无需申报 |
| SYSTEM_ALERT_WINDOW | 须为核心功能且在商店页披露；应用内开启前说明用途 | 待做：商店页文案 + 应用内说明（向导已含用途说明） |
| USE_FULL_SCREEN_INTENT | 闹钟/提醒类可用；targetSdk 34 起用户可撤权，App 已做降级（横幅+按钮） | ✅ 已达标（含 canUseFullScreenIntent 检测） |
| SCHEDULE_EXACT_ALARM | 普通权限但 Android 14 默认拒；已做 canScheduleExactAlarms() 判断+降级 | ✅ 已达标；可选：跳转"闹钟和提醒"授权页 |
| 目标 API | 每年抬线（当前需 targetSdk 35+ 时再适配，注意 15 的 FGS 限制） | 持续项 |
| 数据安全表单 | 不收集/不上传（与隐私政策一致） | 待做：Console 填表 |
| 隐私政策 | 需公网 URL | ✅ docs/privacy-policy.md 待挂到 GitHub Pages/Release 页 |

## 国内商店（OPPO 软件商店 / 华为 / 小米等）

| 项 | 说明 |
|---|---|
| 软件著作权证书 | 个人可办，1-4 周 |
| App 备案（工信部，2023 起强制） | 需主体（个人备案可行）+ 包名 + 签名公钥 |
| 隐私政策 + 个人信息收集清单 | 中文、首次启动弹窗同意（需加首启隐私弹窗，v0.5 候选） |
| 权限最小化审查 | 使用情况访问=敏感权限，商店审核会问用途：答"屏幕使用时长统计与提醒（数字健康类）" |
| 无障碍 | 不用（刻意设计），避免此类全部红线 |
| 个人开发者资质 | OPPO/ vivo 个人可注册；华为需软著；小米个人可发 |

## 现实建议

1. 先 Play（个人开发者 $25，政策透明，悬浮窗路线有先例）
2. 国内分发先走 GitHub/官网直装（无门槛），上架再补软著+备案
3. 上架包与侧载包同一签名（已具备：CI 固定 keystore）
