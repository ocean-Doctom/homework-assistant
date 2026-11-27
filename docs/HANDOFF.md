# 交接文档

> 写于 2026-11-27 11:15，供新对话接续。先读这份，再读 `WORKFLOW.md`、`UI-SPEC.md`、`migrations/`。

---

## 一、现在是什么状态

**安卓 App 可用，网页已停用**。一个班（能动2401）8 名成员在用，作业流转、评论、班级空间、私聊、科目管理、系统消息、知识库、账号与安全、应用内更新都已上线。

- 版本：1.0.2 / versionCode 38
- 代码仓库：`<repo>` → `github.com/ocean-Doctom/homework-assistant`
- 安装包：构建产物 `app-release.apk`
- 线上网页：`https://homework-assistant-14264.app.workbuddy.host/`（**已停用，只保留登录**）
- 发布目录：`<发布目录>`
- 云端应用 ID：`wbapp_ofKMBbEQ72KsQszouCeHGw`
- 测试机：一台 Android 16 测试机

## 二、怎么跑

```bash
# 编译（必须指 JAVA_HOME，否则找不到 jbr）
cd mobile
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" PATH="$JAVA_HOME/bin:$PATH" \
  ./gradlew assembleRelease --no-daemon
cp app/build/outputs/apk/release/app-release.apk "<repo>.apk"

# 安装

"$ADB" install -r "<repo>.apk"

# 网页（如需改前端）
cd desktop && npm run build
# 构建产物手动复制到发布目录 public（先删旧 js/css，再 cp，最后才 deploy）
```

**发布**用 `workbuddy_sites_deploy`，参数见对话历史；**顺序不能错**：先复制文件，再发布，否则线上还是旧包。

## 三、架构

| 层 | 技术 | 备注 |
|---|---|---|
| 安卓 | Kotlin + 原生 View（**无 androidx、无 Compose**） | 全部手写 `LinearLayout` + Canvas 图标 |
| 云端 | PostgreSQL + PostgREST + WorkBuddy 云认证 | 通过 `/.cloud/database/rest/` 访问 |
| 网页 | React + Vite | **已停用**，只留登录页给 WebView 用 |

**关键约束**：安卓的登录界面是 WebView 加载网页，所以网页不能完全下线，否则登录废掉。要彻底删网页，得先把登录做成原生的。

## 四、数据库现状

13 张表：`class_info` `profiles` `class_roster` `assignments` `assignment_images` `submissions` `events` `subjects` `comments` `comment_images` `chat_threads` `chat_messages` `chat_message_images` `messages`

**所有业务表都有 `class_id`**，默认值 `public.current_class_id()`，RLS 全部按班隔离。

关键函数：

| 函数 | 作用 |
|---|---|
| `current_class_id()` | 当前用户班级（RLS 的基础，未加入返回 NULL） |
| `current_class_role()` | 当前用户在本班的角色 |
| `class_member_count(cid)` | 数人数，SECURITY DEFINER 绕过 RLS |
| `join_class(班名, 姓名, 学号)` | 凭名单加入，认领席位后建/更新 profile，补进度；席位是学委就恢复学委 |
| `create_class(班名, 课程, 姓名, 学号)` | 建新班，创建者当学委，班级 + 档案 + 学委席位一次写完（`2026-11-27`） |
| `dissolve_class()` | 学委解散空班，整体事务删除 |
| `leave_class()` | 退出班级：解开席位、进度交还席位、账号保留，学委也能退（`2026-11-27`） |
| `delete_account()` | 注销账号：只删个人档案，席位和进度留下（`2026-11-27`） |
| `auto_profile_role()` | BEFORE INSERT，本班第一个是学委（有学委**席位**时不自动产生学委） |
| `backfill_submissions()` | AFTER INSERT，新成员补作业进度 |
| `sync_member_seat()` | AFTER INSERT/UPDATE 档案，席位跟着改姓名/学号/角色，缺了自动补（`2026-11-27`） |
| `transfer_monitor(席位 id)` | 按席位转让学委，可以转给还没绑手机的空席位（`2026-11-27`） |
| `fill_content_seat()` | BEFORE INSERT 给评论/作业补 `seat_id`，老包也能自动带上（`2026-11-27`） |

接口：`PATCH /seats/{席位id}` 编辑席位（已认领的会同步改账号档案），`POST /seats/{席位id}/transfer` 转让学委。密码相关接口已删除。
`/chat/members` 返回 `members`（已认领，老包兼容）+ `seats`（全部席位，班级空间用）。

**席位模型**（2026-11-27 起）：`class_roster` 是「班级席位」，自带 `role`（student/monitor），`claimed_by` 绑手机号账号，可以空着。
账号和席位解耦：退出班级＝解开绑定，席位和作业进度都留在班里；回来靠 `join_class` 认领，学委席位认领回来就是学委。
**边界的定论**：账号＝私有空间（手机号、头像、私聊，像一台电脑的不同用户），席位＝班级公开位置（成员、作业、评论、进度）。
**成员管理＝席位管理**：每个成员对应一条席位，`profiles_sync_seat` 触发器保证这一点（档案改了席位跟着改，没席位就补一条），
`/members` 也改成从 `class_roster` 取数（返回结构没变，老包照常）。删成员只解开席位绑定，不删席位。

**当前数据**：1 个班能动2401，8 人，10 条席位（8 条已绑定 id 4、9~15；2 条「测试服」空席）。
2026-11-27 在真机上完整跑过一轮：退班 → 建「能动2402」→ 解散 → 用同样姓名学号加回 2401 → 学委自动恢复。

## 五、踩过的坑（务必先看）

1. **`optString` 把 JSON null 读成字符串 `"null"`** —— 头像路径、班级 id 都中过招。取值一律加 `isNull` 判断，`face()` 里已经容错。
2. **RLS 策略里子查询自己保护的表会自锁** —— 新用户 `current_class_id()` 是 NULL，查谁都查不到。改用 `SECURITY DEFINER` 函数（`class_member_count`）。
3. **`INSERT ... RETURNING` 需要 SELECT 策略** —— PostgREST 的 `return=representation` 会校验新行能否被查出来，所以 `profiles_select` 加了 `OR owner_id = auth.uid()`。
4. **`toUser()` 会吞掉自定义字段** —— 我在 `present()` 里构造了带 `pending` 的对象，最后又过了 `toUser`，标记丢了。占位记录要直接返回原对象。
5. **WebView 缓存旧页面** —— 曾经让同学一直在看老版本。服务端已加 `Cache-Control: no-cache`，js/css 用 hash 长缓存。
6. **部署顺序错** —— 先 deploy 后复制，线上拿到旧包。必须复制完再发。
7. **“改完编译通过”不等于能用** —— 手机号登录死循环、注册 RLS 失败，都是编译通过但真机不可用。**新功能必须自己走一遍主路径**。

## 六、待办（按优先级）

### P0 · 安卓原生登录
现在登录依赖 WebView 加载网页。做成原生后，网页才能彻底删除。需要调云认证的 `sendOtp` / `verifyOtp` 两个接口，风险中等（要测通验证码流程）。

### P1 · 重写网页版
用户明确要求：**等安卓开发好以后再重写网页**。现在的 `desktop/src` 是残的（业务功能已停用），重写时不要在此基础上改，参考安卓的交互重新做。

### P1 · 崩溃上报
没有崩溃收集，闪退只能靠同学微信反馈。接一个（如 Bugly / Sentry），半小时能通。

### P2 · 消息推送
私聊目前只在对方开着 App 时能收到，关掉就收不到。

### P2 · 分页
会话列表 300 条、消息 200 条，都是写死的上限，数据涨了会不准。

### P3 · 清理技术债
- `DemoBackend.kt` 697 行死代码（`isDemo()` 恒 false）
- 零自动化测试
- 名单成员的「编辑」功能没做（只有移除）
- 退出班级/注销账号的按钮链路只在 SQL 和页面两处分别验过，真机点按还没人手动过一遍
- 学委退出后班里没人有学委权限（不能发布/揽收/管成员），要等学委用同样姓名学号加回来；
  如果想支持「无学委时成员认领」，得再加一个 RPC + 入口，还没做

## 七、开发方式约定

- 需求模糊**先确认再动手**，不要猜一版做出来
- 改数据库前先说明影响面（哪些表、哪些策略、老数据怎么办）
- 改完主动说**哪些没验证**，不要用“已完成”掩盖
- 每次改动 `git add -A && git commit -m "动词：做了什么"`，然后 `git push`
- UI 按 `UI-SPEC.md`（间距 4/8/12/16/24，字号 11/12/13/15/16/21）

## 八、新对话怎么开场

把下面这句发过去即可：

> 继续开发作业助手（安卓）。先读 `<repo>\docs\HANDOFF.md` 和 `docs/WORKFLOW.md`，然后我们做 XXX。
