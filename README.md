# MotionTrace 轨迹日记

MotionTrace 是一个原生 Android 轨迹记录 App，用于记录日常出行、运动路线和沿途打卡。当前仓库只保留 Android App、Cloudflare Worker 云同步服务和部署文档。

## 项目结构

```text
android-app/           Android 原生 App
cloudflare-worker/     Cloudflare Workers + D1 云同步服务
docs/cloud-sync.md     云同步方案说明
.github/workflows/     Cloudflare Worker 自动部署流程
```

## 已实现

- Android 前台服务持续定位，退到后台或锁屏后继续记录轨迹。
- 按速度动态调整采样频率，快速移动最高约 1 秒一次，慢速约 10 秒一次。
- 今日、历史、我的三个底部 Tab。
- 今日页展示高德地图、今日轨迹、行程数量、距离、时长和打卡列表。
- 历史页按天归档，点击日期卡片可展开轨迹地图、轨迹点和打卡点。
- 沿途打卡支持获取地名、备注、拍照和多选已有照片。
- 我的页汇总展示本机常用打卡地址。
- 本机 JSON 存储轨迹、行程、打卡和照片路径。
- 可选云同步：注册、登录、修改密码、上传本机数据、从云端恢复。
- Cloudflare Worker 提供账号 API、轨迹快照同步、结构化轨迹点入库、轨迹管理后台和 CSV 导出。

## Android 运行

1. 用 Android Studio 打开 `android-app` 目录。
2. 使用 Android Studio 自带 JDK 17。
3. 安装 Android SDK Platform 36。
4. 复制 `android-app/gradle.properties.example` 为 `android-app/gradle.properties`。
5. 填写 `AMAP_KEY`；`CLOUD_WORKER_URL` 默认使用 `https://motiontrace.631581.xyz/`。
6. 连接真机或启动模拟器，点击 Run。

更多 Android 构建和权限说明见 `android-app/README.md`。

## 云同步部署

云同步服务位于 `cloudflare-worker/`，使用 Cloudflare Workers + D1 SQLite。GitHub Actions 已配置自动部署流程，推送到 `main` 后会自动执行 Worker 部署。

首次部署需要在 GitHub 仓库配置这些 Repository secrets：

```text
CLOUDFLARE_API_TOKEN
CLOUDFLARE_ACCOUNT_ID
CLOUDFLARE_D1_DATABASE_ID
```

详细部署步骤见 `cloudflare-worker/README.md` 和 `docs/cloud-sync.md`。

## 数据说明

- 默认数据保存在 Android 本机私有目录。
- 云同步当前上传轨迹、行程、打卡坐标、地名和文字信息；服务端会把 GPS 点位同步写入 `track_points` 表，便于后台按用户、行程和时间范围查询。
- 打卡照片原图暂不上传，仍保存在手机本机。
- 后续如需多设备同步照片，建议接入 Cloudflare R2。
