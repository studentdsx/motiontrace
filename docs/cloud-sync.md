# 云同步方案

## 可行性结论

可行。Cloudflare Workers + D1 SQLite 足够承载当前轨迹日记的轻量云同步：

- 用户注册、登录
- 用户修改密码
- 保存登录会话 token
- 上传/下载轨迹 JSON 快照
- 存储行程、轨迹点、打卡地名和文字等结构化数据

当前实现会同步整份本机轨迹 JSON 快照，同时在服务端解析 GPS 点位写入 `track_points` 表。照片原图暂不上传，仍保存在手机本机。原因是 D1 适合结构化数据，不适合存储大量图片；后续如果需要多设备照片同步，建议再接 Cloudflare R2。

服务端同时提供 Web 管理后台，用于查询轨迹点、排查上传提交记录和管理用户。后台有独立登录页，默认账号为 `admin`，默认密码为 `Admin@1357`。

## 服务端

目录：

```text
cloudflare-worker
```

包含：

```text
cloudflare-worker/src/worker.js
cloudflare-worker/schema.sql
cloudflare-worker/wrangler.toml.example
cloudflare-worker/README.md
```

部署流程：

```bash
cd cloudflare-worker
wrangler d1 create motiontrace
cp wrangler.toml.example wrangler.toml
wrangler d1 execute motiontrace --remote --file=./schema.sql
wrangler deploy
```

把部署后的 Worker URL 写入 Android App 构建配置 `CLOUD_WORKER_URL`，由开发者配置，不让终端用户手动填写。
管理后台访问 `https://motiontrace.631581.xyz/admin`，未登录时会跳转登录页；输入默认账号 `admin` 和默认密码 `Admin@1357` 后可按用户、日期和行程 ID 查询 GPS 点位，并支持分页列表、高德地图视图和 CSV 导出。用户管理页支持查询用户、修改邮箱、重置密码和删除用户。
`motiontrace.631581.xyz` 作为正式服务地址使用，建议在 Cloudflare 控制台里绑定到 Worker；GitHub Actions 默认只更新 Worker 脚本和 D1 表结构。

## Android 端

底部第三个 Tab 已改为“我的”，包含：

- 邮箱
- 密码
- 新密码
- 注册
- 登录
- 修改密码
- 上传本机数据
- 从云端恢复
- 退出登录

本机保存：

- 登录邮箱
- token

同步内容：

- 轨迹点
- 行程数量
- 距离、时间
- 打卡坐标
- 打卡地名
- 打卡备注
- 打卡照片路径字符串

服务端轨迹点表：

- 用户 ID
- 轨迹日期
- 行程 ID
- 点位时间
- 经度
- 纬度
- 定位精度
- 速度

跨天行程按开始记录日期归档。比如 6 月 1 日开始记录、6 月 2 日结束，轨迹表里的 `date` 仍是 6 月 1 日，但点位时间会显示 6 月 2 日的真实时间。

服务端提交记录：

- 提交时间
- 用户邮箱
- 上传体积
- 轨迹天数
- 轨迹点数
- 行程数
- 打卡数

暂不同步：

- 打卡照片原图

## 风险和后续优化

- 当前是手动全量快照同步，简单可靠，但多设备同时编辑时会以最后上传/恢复为准。
- 后续可以升级为按天增量同步，减少覆盖风险。
- 新注册和改密码会使用 PBKDF2-SHA256 加盐哈希；旧 SHA-256 哈希用户登录成功后会自动升级。
- 如果用户规模增加，应增加限流、防爆破、邮箱验证和数据删除入口。
