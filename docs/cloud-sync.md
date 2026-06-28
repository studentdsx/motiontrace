# 云同步方案

## 可行性结论

可行。Cloudflare Workers + D1 SQLite 足够承载当前轨迹日记的轻量云同步：

- 用户注册、登录
- 保存登录会话 token
- 上传/下载轨迹 JSON 快照
- 存储行程、轨迹点、打卡文字等结构化数据

当前实现先同步整份本机轨迹 JSON 快照。照片原图暂不上传，仍保存在手机本机。原因是 D1 适合结构化数据，不适合存储大量图片；后续如果需要多设备照片同步，建议再接 Cloudflare R2。

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
wrangler d1 execute motiontrace --file=./schema.sql
wrangler deploy
```

把部署后的 Worker URL 填到 Android App 的“我的 -> 云同步 -> Worker 地址”。

## Android 端

底部第三个 Tab 已改为“我的”，包含：

- Worker 地址
- 邮箱
- 密码
- 注册
- 登录
- 上传本机数据
- 从云端恢复
- 退出登录

本机保存：

- Worker 地址
- 登录邮箱
- token

同步内容：

- 轨迹点
- 行程数量
- 距离、时间
- 打卡坐标
- 打卡备注
- 打卡照片路径字符串

暂不同步：

- 打卡照片原图

## 风险和后续优化

- 当前是手动全量快照同步，简单可靠，但多设备同时编辑时会以最后上传/恢复为准。
- 后续可以升级为按天增量同步，减少覆盖风险。
- 密码当前使用 Worker 内 SHA-256 加盐哈希，适合 MVP；正式公开服务建议升级 PBKDF2/Argon2 类慢哈希方案。
- 如果用户规模增加，应增加限流、防爆破、邮箱验证和数据删除入口。
