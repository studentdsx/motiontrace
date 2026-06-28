# 轨迹日记 Android

这是从当前微信小程序迁移出来的原生 Android 版本，重点解决小程序退到后台后无法稳定持续记录轨迹的问题。

## 已实现

- 前台服务持续定位，系统通知栏会常驻显示记录状态。
- 应用退到后台、锁屏后继续接收定位更新。
- 按移动速度动态调整采样频率：慢速约 10 秒，普通移动约 5 秒，活跃移动约 2 秒，快速移动最高 1 秒。
- 本地 JSON 存储轨迹点、距离、时长、打卡、备注和打卡照片。
- 首页使用高德地图显示今日轨迹、统计数据、打卡列表。
- 支持手动打卡、选择多张照片并保存到应用私有目录。
- 支持跳转系统设置授予“始终允许”后台定位。

## 运行方式

1. 安装新版 Android Studio，建议使用 Android Studio Koala 或更新版本。
2. 用 Android Studio 打开 `android-app` 目录。
3. 使用 Android Studio 自带 JDK 17 运行 Gradle Sync。
4. 安装 Android SDK Platform 36。
5. 复制 `gradle.properties.example` 为 `gradle.properties`，并填写 `AMAP_KEY`。
6. 连接 Android 真机，点击 Run。
7. 首次启动后允许精确定位和通知权限。
8. 如果需要更强的后台恢复能力，点击“后台权限”并在系统设置里选择“始终允许”。

## 重要说明

- 只要点击“开始记录”后通知栏仍然存在，轨迹记录服务就会继续运行。
- 如果用户手动停止通知对应的服务、强制停止应用、系统极端省电策略杀进程，记录会中断。
- 上架 Google Play 时，`ACCESS_BACKGROUND_LOCATION` 会触发位置权限审核，需要证明后台定位是核心功能。
- 当前已接入高德地图 SDK，Key 从本地 `gradle.properties` 的 `AMAP_KEY` 注入，避免提交到 Git 仓库。
- 高德 Key 必须在高德开放平台绑定当前包名 `com.motiontrace.diary` 和你的签名 SHA1；Debug 和 Release 签名需要分别配置，否则真机地图可能空白或鉴权失败。
- 当前项目使用 `compileSdk 36`、`targetSdk 35`、Build Tools 36.1.0 和 Android Gradle Plugin 8.10.1，需要 Gradle 8.11.1、JDK 17 或更新版本、Android SDK Platform 36。请用新版 Android Studio 打开后构建。

## 后台记录机制

Android 版使用 `TrackRecordingService` 作为前台服务，启动后会创建常驻通知，并通过高德定位 SDK 接收定位更新。轨迹坐标和高德地图使用同一坐标系，数据写入应用私有目录里的 `motion_tracks_v1.json`，打卡照片复制到应用私有目录，不依赖服务器。
