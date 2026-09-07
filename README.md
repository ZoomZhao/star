# 星星探险家

儿童习惯养成应用：家长分配任务，小朋友按次提交，家长确认后发星；星星可用于奖励兑换、购买记账或行为扣星。Android 手机 / Pad 与手机 Web 共用业务界面。

## 已实现

- 独立的平板横屏布局：侧边导航、恐龙岛场景、两列任务；手机采用上下布局和底部导航。
- ImageGen 先生成手机与横屏平板设计，再用真实组件还原。任务插画、角色互动、音效开关、Android 触感与提交反馈。
- 每日任务 / 日期切换 / 规则详情 / 重复完成次数与每日上限 / 家长审批及代完成。
- 每日、每周、指定日期任务模板；模板版本与每日任务快照；当天无提交任务可单独修改。
- 星星余额、累计获得和消耗、近 7 日图表、按日流水、额外奖励、消费、扣星、反向流水撤销。
- 奖励商品管理、孩子申请兑换、家长审核后扣星；申请记录保存当时的商品名及价格。
- 超级管理员创建家庭并预分配家长 / 孩子账号，支持禁用、重置密码；无公开注册。
- 完整 JSON 备份下载和事务恢复，恢复前自动备份，恢复后登录失效。

## 本地启动

需要 Node.js **24.14+**。数据库采用 Node 内置 SQLite，不需要安装数据库服务。当前 Node 24 会显示 SQLite 实验性 API 提示；版本已在容器中固定。

```sh
npm ci
cp .env.development.example .env.development
npm run dev:server
# 第二个终端
npm run dev
```

打开 http://localhost:5173。演示账号只在 `APP_ENV=development` 且 `DEMO_MODE=true` 时启用：

| 身份   | 账号   | 密码             |
| ------ | ------ | ---------------- |
| 小朋友 | child  | child123         |
| 家长   | parent | parent123        |
| 管理员 | admin  | local-admin-2026 |

演示数据与生产数据库分开；生产禁止演示账号。正式账号由超级管理员在管理中心预分配。修改初始 `ADMIN_PASSWORD` 只影响首次创建管理员，后续在设置中修改密码。

## Android

工程位于 `android/`，最低 Android 7（API 24），编译 / 目标 API 36。Java 21、Android SDK 36、Build Tools 36、Gradle Wrapper 已配置。布局不锁定方向，支持平板横屏与竖屏。

设置 `android/local.properties`：

```properties
sdk.dir=/你的/Android/SDK/路径
```

USB 实机调试（先启动本地服务、连接设备并允许 USB 调试）：

```sh
./scripts/android-debug.sh
```

脚本构建 APK，建立 `adb reverse tcp:3001 tcp:3001`，安装并启动。产物在 `artifacts/star-explorer-usb-debug.apk`。此包访问本机开发服务器，拔掉 USB 或停止服务后无法提交；不用于正式分发。

正式环境构建：

```sh
VITE_API_URL=https://你的域名 npm run build
npx cap sync android
cd android
./gradlew :app:assembleRelease
```

正式包仅使用 HTTPS；需要在 Android Studio 配置自己的发布签名后签名。不要使用 USB 调试配置发布。也可不设置 `VITE_API_URL`，在 Android 登录页填写 HTTPS 服务器地址。

## ECS 部署（当前已上线）

正式入口：**https://star.zoomzhao.com**。

当前 ECS 是 Ubuntu 26.04、约 400MB 内存，采用 Node.js 24.14 + SQLite + Caddy 2.11.4 + systemd。构建在本机完成，服务器只运行应用和代理，合计约 100MB 内存。Docker 配置保留作为更大服务器上的可选方案，当前服务器没有安装 Docker。

- 应用路径：`/opt/star/current`，按 `/opt/star/releases/<时间戳>` 保留版本。
- 数据库：`/opt/star/data/star.sqlite`。
- 恢复前备份：`/opt/star/backups/`。
- 服务：`star.service`、`star-web.service`，已启用开机启动和失败重启。
- Caddy 管理域名 HTTPS 和证书续期；原 IP HTTPS 入口重定向至正式域名。
- 应用仅监听 `127.0.0.1:3001`，外部通过 80 / 443 访问。

本机 `.env`（Git 忽略、权限 600）保存 ECS 连接信息及账号密码：

| 账号   | 密码字段        | 用途                             |
| ------ | --------------- | -------------------------------- |
| admin  | ADMIN_PASSWORD  | 创建家庭、分配账号、备份恢复     |
| parent | PARENT_PASSWORD | 审核任务、设置规则和奖励         |
| child  | CHILD_PASSWORD  | 查看每日任务、提交完成、兑换奖励 |

已预分配“我的家庭”，初始四项任务：阅读（1 星 × 2 次）、刷牙（1 星 × 2 次）、整理玩具（2 星 × 1 次）、户外运动（2 星 × 1 次），每日最多 8 星。初始余额为 0。初始奖励为冰淇淋和玩具，规则和价格均可由家长修改。管理员可在预分配新账号时设置孩子称呼。

连接参数 `ECS_HOST`、`ECS_USER`、`ECS_SSH_PORT`、`ECS_PASSWORD`、`DOMAIN` 位于 `.env`。SSH 密码通过 ASKPASS 读取，不作为命令参数或上传内容；服务端只接收应用所需环境变量。

更新部署（已有 Node / Caddy 运行时的服务器）：

```sh
npm ci
npm run build
# 脚本会在本机准备仅含运行依赖的部署包
node scripts/deploy-systemd.mjs
```

部署脚本上传已构建前端、后端与纯 JS 运行依赖，切换 release，保留数据库与备份目录。当前运行时下载来自 Node.js / Caddy 官方发布，已验证 SHA-256 / SHA-512 校验和。

```sh
node scripts/ecs-command.mjs 'systemctl status star star-web --no-pager'
node scripts/ecs-command.mjs 'journalctl -u star -n 50 --no-pager'
```

域名正式版 Android 的服务地址为 `https://star.zoomzhao.com`。已安装的 `artifacts/star-explorer-online.apk` 为可联网的调试签名体验包；后续公开发布需使用自己的 release 签名。安装后无需 USB 网络转发。

## 数据与规则

- 北京时间自然日。孩子只能提交当天；家长可以代完成过去日期，不能预先完成未来任务。
- 待审核 + 已通过次数共同占用每日上限；退回释放名额。每条提交只能审批一次。
- 发星、扣星、审批均在 `BEGIN IMMEDIATE` 事务中结算。星星余额不为负。
- 模板修改从明天生效，只用于之后尚未生成的日期任务；已查看并生成的未来日期也保留其规则快照。
- 已有任意提交记录的任务冻结规则。已记账记录不修改，撤销追加反向流水，保留审批记录及已完成次数。
- 账本按任务归属日期记录任务收入；额外奖励、消费和撤销按操作当天记录。累计收支包含反向流水，因此它们是全部账目总额。
- 任务提交、记账、兑换使用幂等编号，网络失败重试复用编号。服务端对重复审批和重复兑换结算也有唯一约束。
- 手机 Web 会话保存在当前浏览器标签页；Android 会话保存在应用私有 Preferences。退出、更改密码、管理员重置密码和恢复备份会撤销相关登录。
- 第一版无需离线提交；失去网络时展示提示，恢复后重试。账本页面最多显示最近 1000 笔，完整历史始终包含在备份内。

## 备份与恢复

使用 **Web 管理员账号 → 管理中心 → 备份与恢复** 下载 JSON 完整备份。文件包含密码哈希，不含明文密码和登录会话。

恢复时选择备份、输入当前管理员密码、确认替换数据。系统先在隔离内存数据库校验版本、数据结构、外键和余额，再保存当前完整备份到 `BACKUP_DIR/before-restore-*.json`，最后在事务中替换数据。失败不修改现有数据；成功后使用备份内账号重新登录。上传上限 20MB。

Docker 可选方案下取回自动保存的恢复前备份：

```sh
docker compose cp app:/app/backups/ ./recovery-backups/
```

不要在运行时直接替换 SQLite 文件或只复制 WAL 模式的主文件；管理中心导出的备份是完整一致的数据快照。

## 验证

```sh
npm test             # 权限、次数上限、并发审批、规则快照、账本和恢复
npm run test:e2e     # 浏览器端完整流程，独立内存数据库，不影响开发数据
npm run build       # TypeScript + Web 生产构建
```

浏览器测试使用本机 Chrome，启动独立 3002 / 5174 端口。Android 已在连接的 `DMG-W00 / Android 12 / 2800 × 1840` 平板构建、安装，验证横竖屏、登录、任务详情、恐龙交互和星星口袋。线上版本另验证 HTTPS 与取消 USB 网络转发后的访问。

设计图、生成提示词位于 `docs/design/`；界面截图位于 `docs/screenshots/`。原始 PNG 保留在设计目录，应用使用压缩 WebP。

官方参考：[Capacitor Android](https://capacitorjs.com/docs/android)、[Node SQLite API](https://nodejs.org/docs/latest-v24.x/api/sqlite.html)。
