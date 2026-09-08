# 解锁提醒 (Unlock Reminder)

选应用、设时长；解锁后按应用累计使用，到点全屏弹出提醒。下次解锁重新计时。只提醒，不限制。

## 下载安装

打开手机浏览器访问 Release 页下载 `unlock-reminder.apk` 安装（允许未知来源）：

- 恒定最新版直链：https://github.com/aliensweety/unlock-reminder/releases/latest/download/unlock-reminder.apk
- Release 列表：https://github.com/aliensweety/unlock-reminder/releases

## 首次使用（按主界面 ①②③④ 依次开通）

1. 通知权限
2. 使用情况访问（用于统计本轮各应用时长）
3. 悬浮窗权限（到点时无视当前应用直接全屏弹出的依据）
4. 电池优化白名单（防止服务被杀）

然后在首页添加要提醒的应用并设定时长，打开「开启提醒」即可。解锁后各应用从 0 计时，到点弹窗；灭屏或下次解锁重新开始。国产 ROM（MIUI/ColorOS 等）如遇重启后不自启，请在系统设置中允许本应用「自启动」。

## 构建

GitHub Actions 自动构建：推送 `main` 触发构建，推送 `v*` 标签自动发 Release。签名密钥首次构建时自动生成并上传 artifact（`signing-keystore`），回收后 `base64` 注入仓库 secret `KEYSTORE_BASE64`，此后签名一致、可覆盖安装（store/key 密码 `unlockreminder`，别名 `unlockreminder`）。

本地构建需 JDK 17 + Android SDK + Gradle 8.8：`gradle assembleRelease`。

## 版本

- v0.1.0：核心闭环（解锁→倒计时→全屏提醒→确定/取消；通知栏倒计时；使用统计；间隔可设）
