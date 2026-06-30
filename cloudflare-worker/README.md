# MotionTrace Cloudflare Worker

这是轨迹日记的可选云同步服务端，部署在 Cloudflare Workers，数据存储在 D1 SQLite。

## 可行性边界

- 适合存储用户账号、登录会话、轨迹 JSON 快照、行程、打卡地名和文字。
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
登录成功后进入管理页，可按邮箱过滤并查询提交记录。
后台当前展示每次轨迹上传的提交时间、用户邮箱、上传体积、轨迹天数、轨迹点数、行程数和打卡数。

## 账号逻辑

- 注册：`POST /auth/register`，邮箱唯一，密码至少 8 位。
- 登录：`POST /auth/login`，成功后返回 30 天有效的 bearer token。
- 修改密码：`POST /auth/change-password`，需要登录 token、当前密码和新密码；修改成功后会保留当前会话并清理其他旧会话。
- 密码哈希：新注册和改密码使用 PBKDF2-SHA256；旧 SHA-256 哈希用户登录成功后会自动升级。
