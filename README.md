# 星星探险家

儿童习惯养成应用：家长分配任务，小朋友按次提交，家长确认后发星；星星可用于奖励兑换、购买记账或行为扣星。Android 手机 / Pad 使用原生 Android Views 界面，手机 Web 使用 React，两端共用 HTTPS API 和 SQLite 数据。

## 已实现

- 原生 Android 平板横屏：插画侧边导航、固定场景和日期、科目切换，两列任务在右侧区域独立滚动；窄屏使用底部导航。今日获得移到顶部，隐藏当日可得总数。
- 五个一级科目：语文、数学、英语、体育、其他；语文、数学、英语各三个预制模板，体育和其他各两个，新小朋友自动配置 13 个起步任务。
- 恐龙探险与公主花园两套皮肤，选择保存在设备上。五张 ImageGen 科目插画在两端共用。
- ImageGen 先生成手机与横屏平板设计，再用真实组件还原。任务插画、角色互动、音效开关、Android 触感与提交反馈。
- 每日任务 / 日期切换 / 规则详情 / 重复完成次数与每日上限 / 家长审批及代完成。
- 每日、每周、指定日期任务模板；模板版本与每日任务快照；当天无提交任务可单独修改。
- 星星余额、累计获得和消耗、近 7 日图表、按日流水、额外奖励、消费、扣星、反向流水撤销。
- 家长在奖励小铺新增、编辑名称/说明/图片图标/星星价格，按全部/已上架/已下架筛选，快捷下架或重新上架，并进入兑换审核。孩子只看到上架奖励；已有兑换申请保留当时的商品名及价格。
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

构建与安装原生体验包：

```sh
npm run android:build
npm run android:install   # 设备解锁并允许 USB 安装
```

产物为 `artifacts/star-explorer-native.apk`（0.2.8-native，调试签名），默认访问 `https://star.zoomzhao.com`，不依赖 USB 转发。Android 已移除 BridgeActivity 和 WebView，界面、表单、图片绘制、触感、星星动画、文件选择和网络请求均为原生实现。不要再运行 Capacitor sync。

发布前在 Android Studio 配置自己的 release 签名，再执行 `./android/gradlew -p android :app:assembleRelease`。API 地址在 `android/app/build.gradle` 的 `API_BASE_URL` 中配置，正式构建仅支持 HTTPS。调试构建可通过 `test_api` Intent 参数指定本机回环地址，供隔离测试使用；release 忽略该参数。

原生界面测试必须使用独立包名：先启动 `node scripts/native-fixture.mjs`，运行 `adb reverse tcp:3004 tcp:3004`，再以 `./android/gradlew -p android -PisolatedTestApp :app:assembleDebug :app:assembleDebugAndroidTest` 构建测试版（`com.starexplorer.app.testbed`）。安装两个测试 APK 后，用 `adb shell am instrument -w -e class com.starexplorer.app.CompletionAnimationTest com.starexplorer.app.testbed.test/androidx.test.runner.AndroidJUnitRunner` 验证动画。测试 API 使用独立内存数据库，不写生产任务或账本。不要在日常使用包名上运行 Gradle connected 测试，其结束清理可能卸载应用。测试构建后交付前必须重新运行 `npm run android:build`，保证交付产物使用正式包名。

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

现有账号及密码保持不变，升级时已为小朋友补齐五科共 10 个模板；没有修改星星账本。预制内容见 `shared/task-templates.json`，家长可修改或停用。初始奖励为冰淇淋和玩具，规则和价格均可由家长修改。管理员可在预分配新账号时设置孩子称呼。

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

域名正式版 Android 的服务地址为 `https://star.zoomzhao.com`。新原生体验包为 `artifacts/star-explorer-native.apk`；旧 `star-explorer-online.apk` 是先前的 Capacitor 版本。后续公开发布需使用自己的 release 签名。

## 数据与规则

- 家长审批和代完成可输入每次星星（1–101）与本次完成次数（1–20，不能超过每日剩余额度），实发星星为两者乘积。孩子仍按一次提交，待确认次数同样占用额度。旧版加星、少发请求继续兼容。
- 家长可在任务规则中按科目上移、下移任务，立即同步到孩子端。排序单独存储并随备份恢复，不改变规则版本、任务快照或账本。

- 语文、数学、英语各有「课内作业」，每天一次，基础 2 星。所有任务在家长审批或代完成时均可选择额外奖励 1 星，默认按基础星星发放；基础超过 1 星时还可选择少发 1 星（例如 2 星只发 1 星）。每次确认最低发放 1 星，额外奖励与少发不能同时选择，按实发数量结算、撤销。
- 存量孩子通过 `node --env-file=/opt/star/server.env server/seed-homework.mjs` 补充三科课内作业，重复运行不会重复添加。

- 北京时间自然日。孩子只能提交当天；家长可以代完成过去日期，不能预先完成未来任务。
- 待审核 + 已通过次数共同占用每日上限；退回释放名额。每条提交只能审批一次。
- 发星、扣星、审批均在 `BEGIN IMMEDIATE` 事务中结算。星星余额不为负。
- 模板修改从明天生效，只用于之后尚未生成的日期任务；已查看并生成的未来日期也保留其规则快照。
- 已有任意提交记录的任务冻结规则。已记账记录不修改，撤销追加反向流水，保留审批记录及已完成次数。
- 账本按任务归属日期记录任务收入；额外奖励、消费和撤销按操作当天记录。累计收支包含反向流水，因此它们是全部账目总额。
- 任务提交、记账、兑换使用幂等编号，网络失败重试复用编号。服务端对重复审批和重复兑换结算也有唯一约束。
- Web 使用 localStorage 保留登录，Android 使用 Keystore AES-GCM 加密会话令牌后存入私有 Preferences；不保存明文密码。会话有效期为 180 天未使用，每日使用会续期。主动退出、更改密码、管理员停用/重置账号或恢复备份后重新登录。
- 科目写入规则和每一天的任务快照，也包含在备份中。当前存量数据升级只补充科目和缺少的起步模板，账号、密码和账本保留。
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

浏览器测试使用本机 Chrome，启动独立 3002 / 5174 端口。本次 Review 后 13 项 API 测试、10 项浏览器流程通过。Android 0.2.4-native 构建、2 项布局单元测试、2 项网络恢复测试和 2 项 RefinementTest 流程通过；界面测试使用 2800×1840 / 400 dpi 专用模拟器和独立测试包。视觉预览见 `docs/design/native/IMPLEMENTATION.md`，本次修复细节见 `docs/REVIEW-2026-09-08.md`。本次未部署 ECS 或覆盖安装日常使用平板。

设计图、生成提示词位于 `docs/design/`；界面截图位于 `docs/screenshots/`。原始 PNG 保留在设计目录，应用使用压缩 WebP。

官方参考：[Android Views](https://developer.android.com/develop/ui/views/layout/declaring-layout)、[Node SQLite API](https://nodejs.org/docs/latest-v24.x/api/sqlite.html)。

本次整体 Review 的修复、验证范围与后续建议见 [Review 记录](docs/REVIEW-2026-09-08.md)。
