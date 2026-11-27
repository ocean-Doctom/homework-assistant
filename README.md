# 作业物流 / 作业助手

一个班一套部署的作业收发工具。学委发布作业，全班在时间线里看进度；进度走「揽收」流程：未写完 → 已写完 → 学委已揽收 → 学委已取回 → 学委已分发。

日常使用的是 **Android 原生客户端**。本仓库同时保留桌面端、服务端与数据库 schema 的源码。

## 目录

| 路径 | 说明 |
| --- | --- |
| `mobile/` | Android 客户端，Kotlin + Gradle Kotlin DSL（`minSdk 26`，`targetSdk 37`） |
| `desktop/` | Electron + React + Vite 桌面端（Windows） |
| `server/` | Fastify 服务端，默认监听 `3780` |
| `schema.sql` | 云数据库基线建表语句 |
| `migrations/` | 按日期记录的表结构变更与排障笔记 |
| `docs/HANDOFF.md` | 部署与交接说明 |

> 应用代码**不会**自动建表。`schema.sql` 和 `migrations/` 里的语句需要由管理侧逐条执行；
> 只执行追加语句，不要重跑整份基线，不要 `DROP TABLE` / `TRUNCATE`。

## 本地构建

**Android**

```bash
cd mobile
# 在 mobile/local.properties 里写 sdk.dir=<你的 Android SDK 路径>（该文件不入库）
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

发布包签名不在仓库里。需要时在 `mobile/app/` 下建 `keystore.properties`（已被 `.gitignore` 覆盖）：

```properties
storeFile=release.keystore
storePassword=你的密码
keyAlias=你的别名
keyPassword=你的密码
```

没有该文件时 `assembleRelease` 仍可编译，只是产物不带 release 签名。

**服务端**

```bash
cd server
npm install
npm start
# 可通过 PORT / HOST 环境变量覆盖默认端口与监听地址
```

**桌面端**

```bash
cd desktop
npm install
npm run dev      # 开发模式
npm run dist     # 打包 Windows 安装包
```

## 配置与凭据

- 各端需要指向**你自己的**云服务实例与数据库；请在运行时配置或环境变量中提供，不要在源码里硬编码地址、密钥或令牌。
- 以下文件/目录一律不入库，`.gitignore` 已覆盖：`*.apk`、`*.aab`、`*.keystore`、`*.jks`、`local.properties`、`backups/`、`server/node_modules/`、`server/data/secret`、`*.zip`。
- 若你曾把任何密钥提交进历史，请**先轮换密钥**，再清理历史——删除文件不会让已泄露的密钥失效。

## 隐私

仓库中的示例姓名与学号均为占位符（如 `同学A` / `U2024xxxxxx`）。真实班级名单、云端数据快照、成员信息属于个人信息，**不纳入版本库**，也不要写进 issue、PR 或提交信息。

## 许可证

[MIT](LICENSE) © 2026 ocean-Doctom
