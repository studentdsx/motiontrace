# MotionTrace Cloudflare Worker

这是轨迹日记的可选云同步服务端，部署在 Cloudflare Workers，数据存储在 D1 SQLite。

## 可行性边界

- 适合存储用户账号、登录会话、轨迹 JSON 快照、结构化 GPS 点位、行程、打卡地名和文字。
- 当前版本不上传打卡照片原图，照片仍保存在手机本地。
- 如果后续需要同步照片，建议新增 Cloudflare R2，不建议把图片塞进 D1。

## 部署

1. 安装并登录 Wrangler。
2. 创建 D1 数据库：

```bash
wrangler d1 create motiontrace
```

3. 复制配置：

```bash
cp wrangler.toml.example wrangler.toml
```

把输出的 `database_id` 填入 `wrangler.toml`。

4. 初始化表：

```bash
wrangler d1 execute motiontrace --remote --file=./schema.sql
```

5. 部署 Worker：

```bash
wrangler deploy
```

部署后的 Worker URL 填到 Android App 构建配置 `CLOUD_WORKER_URL`，不要让终端用户手动填写。
管理后台访问 `https://motiontrace.631581.xyz/admin`，未登录时会先进入登录页；默认账号为 `admin`，默认密码为 `Admin@1357`。
登录后可以按用户名、轨迹日期和行程 ID 筛选云端 GPS 点位，支持分页列表、高德地图视图切换，并可导出 CSV。后台还提供用户列表、删除用户、重置密码和修改用户名/邮箱。
`motiontrace.631581.xyz` 建议在 Cloudflare 控制台里作为 Worker 自定义域名绑定一次；自动部署只更新 Worker 脚本和 D1 表结构，避免 GitHub Actions Token 额外依赖 Zone 路由权限。

## GitHub Actions 自动化部署

项目根目录已经提供 `.github/workflows/deploy-cloudflare-worker.yml`。当 `main` 分支里的 `cloudflare-worker/**` 发生变化时会自动执行，也可以在 GitHub Actions 页面手动运行。

### 首次准备

1. 在本机或 Cloudflare 控制台先创建 D1 数据库：

```bash
wrangler d1 create motiontrace
```

2. 记录输出里的 `database_id`。

3. 在 GitHub 仓库进入 `Settings -> Secrets and variables -> Actions`，新增这些 Repository secrets：

```text
CLOUDFLARE_API_TOKEN
CLOUDFLARE_ACCOUNT_ID
CLOUDFLARE_D1_DATABASE_ID
```

`CLOUDFLARE_D1_DATABASE_ID` 填第 2 步拿到的 `database_id`。如果你选择把真实的 `cloudflare-worker/wrangler.toml` 提交到仓库，这个 secret 可以不填。

### API Token 权限

建议在 Cloudflare 创建自定义 API Token，至少给当前账号这些权限：

```text
Account / Workers Scripts / Edit
Account / D1 / Edit
Account / Account Settings / Read
```

如果要让 GitHub Actions 同时创建或修改自定义域名/路由，再额外给对应 Zone 的 Workers Routes 权限；当前默认流程不需要这项权限。

### 自动化流程做了什么

1. 检查 `cloudflare-worker/wrangler.toml` 是否存在。
2. 如果不存在，就用 `wrangler.toml.example` 和 `CLOUDFLARE_D1_DATABASE_ID` 生成临时配置。
3. 执行远程 D1 初始化：

```bash
wrangler d1 execute motiontrace --remote --file=./schema.sql
```

4. 执行部署：

```bash
wrangler deploy
```

`schema.sql` 使用 `CREATE TABLE IF NOT EXISTS` 和 `CREATE INDEX IF NOT EXISTS`，所以每次部署前执行是安全的。

部署完成后，可以访问：

```text
https://motiontrace.631581.xyz/health
```

返回 `{"ok":true}` 就说明服务端已经可用。

管理后台访问：

```text
https://motiontrace.631581.xyz/admin
```

默认账号为 `admin`，默认密码为 `Admin@1357`。
登录成功后进入管理页，可查询云端结构化轨迹记录：

- 用户筛选支持下拉选择和模糊匹配。
- 轨迹日期默认选择昨天。
- 行程 ID 会根据当前用户和日期动态加载下拉选项。
- 列表视图展示时间、用户、日期、行程、点序号、经纬度、速度、精度和点位 ID。
- 列表视图按页加载，地图视图会用高德 JS API 按用户 + 日期 + 行程分组绘制历史轨迹。
- 点击 `导出 CSV` 可下载当前筛选条件下的轨迹点，最多导出 10000 条。
- 用户管理页可查询用户列表、修改用户名和邮箱、重置密码、删除用户；删除用户会同步删除该用户的会话、云端快照、提交记录和结构化轨迹点。

后台仍保留提交记录接口 `GET /admin/api/submissions`，用于排查每次上传的体积、天数、轨迹点数、行程数和打卡数。

## 数据结构

云同步上传时，App 仍提交完整轨迹 JSON 快照。Worker 会保留原始快照，同时解析出 GPS 点位写入 `track_points` 表，便于后台查询和导出。
用户在 App 里开启实时同步后，记录过程中新增的 GPS 点位会通过 `POST /sync/track-point` 直接写入同一张 `track_points` 表；重复上报会按稳定点位 ID 覆盖，不会产生重复行。

`track_points` 主要字段：

```text
id           点位 ID
user_id      用户 ID
date         轨迹日期，格式为 yyyy-MM-dd
trip_id      行程 ID
trip_index   当天第几个行程
point_index  原始快照中的点位序号
timestamp    点位时间戳，毫秒
longitude    经度
latitude     纬度
accuracy     定位精度，米
speed        速度，m/s
created_at   云端写入时间
```

轨迹点的 `date` 以行程开始日期为准；跨天行程中第二天产生的点位仍归到开始记录那天，点位时间戳保持真实时间，所以后台时间可能显示为第二天。
如果旧数据里没有明确行程列表，Worker 会按兼容逻辑生成 `legacy_日期`；无法匹配到行程时间段的点位会归入 `unassigned`。

## 账号逻辑

- 注册：`POST /auth/register`，用户名和邮箱唯一，密码至少 8 位；邮箱用于找回密码或联系。
- 登录：`POST /auth/login`，使用用户名和密码，成功后返回 30 天有效的 bearer token；旧账号仍可临时用邮箱作为登录名兼容登录。
- 修改密码：`POST /auth/change-password`，需要登录 token、当前密码和新密码；修改成功后会保留当前会话并清理其他旧会话。
- 密码哈希：新注册和改密码使用 PBKDF2-SHA256；旧 SHA-256 哈希用户登录成功后会自动升级。
