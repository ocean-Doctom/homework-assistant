# 云端数据库变更记录

git 管不到数据库。这里按时间记录每一次手工执行的 SQL，用于追溯和重建。
**以后每次改表结构、函数、触发器或 RLS 策略，都在这里追加一节。**

---

## 2026-11-27 · 账号与安全（退出班级 / 注销账号）

### 0. 核心模型：手机号和班级席位是解耦的

- **账号**＝手机号登录出来的 `profiles` 一行（姓名、学号、头像）。
- **席位**＝`class_roster` 一行，属于班级，`claimed_by` 绑着某个账号；可以空着（`claimed_by IS NULL`）。
- 席位自带 `role`，所以「学委」也是一种席位。人走了席位留下，学委身份跟着席位走，不跟着账号走。

退出班级＝把 `claimed_by` 解开、`profiles.class_id` 置空；作业进度和席位都留在班里。
回来＝`join_class` 认领席位，身份按席位的 `role` 恢复（学委席位 → 学委）。

### 1. 表结构

```sql
ALTER TABLE public.class_roster ADD COLUMN IF NOT EXISTS role TEXT NOT NULL DEFAULT 'student';
ALTER TABLE public.class_roster ADD CONSTRAINT class_roster_role_check CHECK (role IN ('student', 'monitor'));
```

**为什么要加这列**：原来的 8 个成员是直接建 `profiles` 入班的（历史路径），**一条 `class_roster` 都没有**。
不补席位的话，退出后 `join_class` 找不到「姓名 + 学号 + 未认领」的记录，人就走得了、回不来。

### 2. 回填（只跑一次）

```sql
-- 已绑定的席位，如果绑的是学委，就标成学委席位
UPDATE public.class_roster r SET role = 'monitor'
WHERE EXISTS (SELECT 1 FROM public.profiles p
              WHERE p.owner_id = r.claimed_by AND p.class_id = r.class_id AND p.role = 'monitor');

-- 给还没有席位的学委补一条（能动2401 的同学A → class_roster id=4）
INSERT INTO public.class_roster (class_id, name, student_no, role, claimed_by)
SELECT p.class_id, p.name, COALESCE(p.student_no, ''), 'monitor', p.owner_id
FROM public.profiles p
WHERE p.role = 'monitor' AND p.class_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM public.class_roster r
                  WHERE r.class_id = p.class_id AND r.claimed_by = p.owner_id);
```

普通同学的席位不提前补，退出时由函数当场建一条（只补真用到的人，名单里不出现一堆空席位）。

### 3. `auto_profile_role()`：有学委席位时不再自动产生学委

```sql
CREATE OR REPLACE FUNCTION public.auto_profile_role() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
BEGIN
  IF (SELECT COUNT(*) FROM public.profiles WHERE class_id = NEW.class_id) = 0
     AND NOT EXISTS (SELECT 1 FROM public.class_roster WHERE class_id = NEW.class_id AND role = 'monitor') THEN
    NEW.role := 'monitor';
  ELSE
    NEW.role := 'student';
  END IF;
  RETURN NEW;
END $fn$;
```

学委退出后班里 0 个学委但学委席位还在，这时新来的人不能因为「本班第一个」就自动当上学委。

### 4. `join_class()`：身份按席位恢复

在原来基础上加两条：

- 认领的席位 `role = 'monitor'` **且**班里当前没有学委 → `profiles.role = 'monitor'`（学委回来认领）。
  班里已有学委时降级为 `student`，不会出现两个学委。
- `INSERT` 分支先按 `'student'` 插（让 `auto_profile_role` 正常跑完），再 `UPDATE` 成席位角色。

```sql
CREATE OR REPLACE FUNCTION public.join_class(klass_name text, member_name text, member_no text)
RETURNS json LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $function$
DECLARE pid TEXT := auth.uid(); cid BIGINT; rid BIGINT; r TEXT := 'student';
BEGIN
  IF pid IS NULL THEN RAISE EXCEPTION '请先登录'; END IF;
  IF EXISTS (SELECT 1 FROM public.profiles WHERE owner_id = pid AND class_id IS NOT NULL) THEN
    RAISE EXCEPTION '你已经加入班级了';
  END IF;
  SELECT id INTO cid FROM public.class_info WHERE lower(trim(name)) = lower(trim(klass_name)) LIMIT 1;
  IF cid IS NULL THEN RAISE EXCEPTION '没有「%」这个班级', trim(klass_name); END IF;
  SELECT id, t.role INTO rid, r FROM public.class_roster t
   WHERE t.class_id = cid AND lower(trim(t.name)) = lower(trim(member_name))
     AND trim(t.student_no) = trim(member_no) AND t.claimed_by IS NULL
   LIMIT 1;
  IF rid IS NULL THEN RAISE EXCEPTION '名单里没有这个姓名和学号，请联系学委'; END IF;
  IF r = 'monitor' AND EXISTS (SELECT 1 FROM public.profiles WHERE class_id = cid AND role = 'monitor') THEN
    r := 'student';
  END IF;
  IF EXISTS (SELECT 1 FROM public.profiles WHERE owner_id = pid) THEN
    UPDATE public.profiles SET class_id = cid, name = trim(member_name),
      student_no = trim(member_no), role = r WHERE owner_id = pid;
  ELSE
    INSERT INTO public.profiles (owner_id, class_id, name, student_no, role)
    VALUES (pid, cid, trim(member_name), trim(member_no), 'student');
    UPDATE public.profiles SET role = r WHERE owner_id = pid;
  END IF;
  UPDATE public.class_roster SET claimed_by = pid WHERE id = rid;
  UPDATE public.submissions SET student_ref = pid
   WHERE student_ref = 'roster:' || rid::text AND class_id = cid;
  INSERT INTO public.submissions (assignment_id, student_ref, status, class_id, owner_id)
  SELECT a.id, pid, 'not_submitted', cid, a.owner_id
  FROM public.assignments a
  WHERE a.class_id = cid
    AND NOT EXISTS (SELECT 1 FROM public.submissions s WHERE s.assignment_id = a.id AND s.student_ref = pid);
  RETURN json_build_object('ok', true, 'classId', cid);
END $function$;
```

### 5. `leave_class()`：学委也能退

```sql
CREATE OR REPLACE FUNCTION public.leave_class() RETURNS json
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE uid TEXT := auth.uid(); cid BIGINT; r TEXT; rid BIGINT; n INT;
BEGIN
  IF uid IS NULL THEN RAISE EXCEPTION '请先登录'; END IF;
  SELECT class_id, role INTO cid, r FROM public.profiles WHERE owner_id = uid;
  IF cid IS NULL THEN RAISE EXCEPTION '你还没有加入班级'; END IF;
  -- 有席位就解开，没有就当场补一条（历史成员没有名单记录）
  UPDATE public.class_roster SET claimed_by = NULL
   WHERE class_id = cid AND claimed_by = uid RETURNING id INTO rid;
  IF rid IS NULL THEN
    INSERT INTO public.class_roster (class_id, name, student_no, role, claimed_by)
    SELECT p.class_id, p.name, COALESCE(p.student_no, ''),
           CASE WHEN p.role = 'monitor' THEN 'monitor' ELSE 'student' END, NULL
    FROM public.profiles p WHERE p.owner_id = uid RETURNING id INTO rid;
  END IF;
  -- 学委席位保留学委身份，回来还认领得回
  IF r = 'monitor' THEN UPDATE public.class_roster SET role = 'monitor' WHERE id = rid; END IF;
  -- 已交的进度交回席位
  UPDATE public.submissions SET student_ref = 'roster:' || rid::text
   WHERE class_id = cid AND student_ref = uid;
  -- role 必须降成 student：current_class_role() 不看 class_id，
  -- 留着 monitor 会让 class_info_update 这类只看角色的策略继续生效
  UPDATE public.profiles SET class_id = NULL, role = 'student' WHERE owner_id = uid;
  SELECT public.class_member_count(cid) INTO n;
  RETURN json_build_object('ok', true, 'wasMonitor', r = 'monitor', 'members', n);
END $fn$;

REVOKE ALL ON FUNCTION public.leave_class() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.leave_class() TO authenticated;
```

### 6. `delete_account()`：只删档案，席位和进度留下

```sql
CREATE OR REPLACE FUNCTION public.delete_account() RETURNS json
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE uid TEXT := auth.uid(); cid BIGINT; r TEXT; rid BIGINT;
BEGIN
  IF uid IS NULL THEN RAISE EXCEPTION '请先登录'; END IF;
  SELECT class_id, role INTO cid, r FROM public.profiles WHERE owner_id = uid;
  IF cid IS NOT NULL THEN
    UPDATE public.class_roster SET claimed_by = NULL
     WHERE class_id = cid AND claimed_by = uid RETURNING id INTO rid;
    IF rid IS NULL THEN
      INSERT INTO public.class_roster (class_id, name, student_no, role, claimed_by)
      SELECT p.class_id, p.name, COALESCE(p.student_no, ''),
             CASE WHEN p.role = 'monitor' THEN 'monitor' ELSE 'student' END, NULL
      FROM public.profiles p WHERE p.owner_id = uid RETURNING id INTO rid;
    END IF;
    IF r = 'monitor' THEN UPDATE public.class_roster SET role = 'monitor' WHERE id = rid; END IF;
    UPDATE public.submissions SET student_ref = 'roster:' || rid::text
     WHERE class_id = cid AND student_ref = uid;
  END IF;
  DELETE FROM public.profiles WHERE owner_id = uid;
  RETURN json_build_object('ok', true);
END $fn$;

REVOKE ALL ON FUNCTION public.delete_account() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.delete_account() TO authenticated;
```

评论和私聊不删（那是别人的数据），作者查不到时客户端显示「已注销的同学」。

### 7. 为什么必须走函数

`profiles` 的两条策略挡住了自助操作：

- `profiles_update` 的 WITH CHECK 禁止本人改自己的 `class_id`（防止学生自己换班）。
- `profiles_delete` 只允许学委删非学委，本人删不了自己。

所以都包在 `SECURITY DEFINER` 里，走表 owner 身份绕过 RLS，和 `dissolve_class` 一个路子。

```sql
NOTIFY pgrst, 'reload schema';   -- 新函数要让 PostgREST 重新扫一遍，否则 rpc/ 404
```

---

## 验证记录（2026-11-27）

用两个临时班（`class_info` 13、14）+ 假档案（`test-*`）跑函数体，**没动 1 号班（能动2401）的真实数据**，验完已清理。
两个班都刻意做成「成员没有任何名单记录」，和线上真实数据一致。

| 检查 | 结果 |
|---|---|
| 同学退出：`profiles.class_id` | `NULL`，姓名保留 ✔ |
| 同学退出：席位 | 没有记录时**当场新建**（id=5），`claimed_by=NULL` ✔ |
| 同学退出：进度 | `student_ref` → `roster:5`，状态未变 ✔ |
| 同学加回来 | 认领席位成功，进度改回 `uid`，角色 `student` ✔ |
| 学委退出 | 席位 id=6 保留 `role=monitor` 且未绑定，班里 0 个学委，本人 `role` 降为 `student` ✔ |
| 学委加回来 | 角色恢复成 `monitor`，席位重新绑定 ✔ |
| 注销 | profile 删除，席位和 `submissions` 都还在 ✔ |
| 真实数据 | 能动2401 仍 8 人，学委席位 id=4 已补，无假数据、无孤儿席位 ✔ |

**已知缺口**：学委退出后，班里没有任何人有学委权限（不能发布作业、不能揽收、不能管理成员），
直到学委用同样的姓名 + 学号加回来。如果学委彻底不回来，这个班只能靠管理侧 SQL 处理。
要不要做「无学委时成员可认领」的入口，等产品上确认。

**没验证的**：真机上点「退出」「注销」的完整链路（测试机 MIUI 拦了 adb 注入点击，页面渲染单独截过图）。

---

## 2026-11-27 · 创建班级（加入页下拉菜单）

「加入班级」页原来的班级名是手输的，改成下拉选现有班级、菜单最后一项「创建新班级」。

### 为什么要走函数

- `class_info.code` 是 `NOT NULL UNIQUE` 且**没有默认值、没有触发器**（当年用 `md5(random())` 手工回填的），
  客户端直接 `INSERT class_info` 必然失败 —— 老的 `/setup` 接口就是这么废掉的。
- `class_roster` 的 INSERT 策略要求 `class_id = current_class_id()` 且本人是学委，
  建班那一刻本人还没进班，`current_class_id()` 是 NULL，**建不出学委席位**。

两条加起来，只能一条 RPC 全做完：班级 + 档案 + 学委席位。

### 函数

```sql
CREATE OR REPLACE FUNCTION public.create_class(klass_name text, klass_course text, member_name text, member_no text)
RETURNS json LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE pid TEXT := auth.uid(); cid BIGINT; kcode TEXT;
BEGIN
  IF pid IS NULL THEN RAISE EXCEPTION '请先登录'; END IF;
  IF trim(COALESCE(klass_name,'')) = '' THEN RAISE EXCEPTION '请填写班级名'; END IF;
  IF trim(COALESCE(member_name,'')) = '' THEN RAISE EXCEPTION '请填写姓名'; END IF;
  IF trim(COALESCE(member_no,'')) = '' THEN RAISE EXCEPTION '请填写学号'; END IF;
  IF EXISTS (SELECT 1 FROM public.profiles WHERE owner_id = pid AND class_id IS NOT NULL) THEN
    RAISE EXCEPTION '你已经加入班级了';
  END IF;
  IF EXISTS (SELECT 1 FROM public.class_info WHERE lower(trim(name)) = lower(trim(klass_name))) THEN
    RAISE EXCEPTION '已经有「%」这个班级了', trim(klass_name);
  END IF;
  kcode := upper(substr(md5(random()::text || clock_timestamp()::text), 1, 6));
  INSERT INTO public.class_info (name, course, code, owner_id)
  VALUES (trim(klass_name), COALESCE(NULLIF(trim(COALESCE(klass_course,'')), ''), '作业'), kcode, pid)
  RETURNING id INTO cid;
  IF EXISTS (SELECT 1 FROM public.profiles WHERE owner_id = pid) THEN
    UPDATE public.profiles SET class_id = cid, name = trim(member_name),
      student_no = trim(member_no), role = 'monitor' WHERE owner_id = pid;
  ELSE
    INSERT INTO public.profiles (owner_id, class_id, name, student_no, role)
    VALUES (pid, cid, trim(member_name), trim(member_no), 'monitor');
  END IF;
  INSERT INTO public.class_roster (class_id, name, student_no, role, claimed_by)
  VALUES (cid, trim(member_name), trim(member_no), 'monitor', pid);
  RETURN json_build_object('ok', true, 'classId', cid, 'code', kcode);
END $fn$;

REVOKE ALL ON FUNCTION public.create_class(text, text, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.create_class(text, text, text, text) TO authenticated;
NOTIFY pgrst, 'reload schema';
```

建班时**先插档案再插席位**：档案的 INSERT 会走 `auto_profile_role` 触发器，
那时班里还没有学委席位，才会被判成 `monitor`；顺序反了就会建成一个学生。

### 验证记录

用 `test-c-1`（已登录、未加入班级的档案，和真实路径一致）跑函数体：

| 检查 | 结果 |
|---|---|
| `class_info` | 新班 id=15，`code` 自动生成（0D7AEF），课程取传入值 ✔ |
| `profiles` | 变成 `role=monitor`、`class_id=15` ✔ |
| `class_roster` | 自动建学委席位 `role=monitor, claimed_by=本人` ✔ |
| 重名班级 | 抛「已经有「xx」这个班级了」（靠 `class_info.name` 唯一索引兜底）✔ |
| 清理 | 删测试班后只剩能动2401，无残留 ✔ |

**真机**：用户自己在新版里走通了建班（建出「能动2402」，学委席位已绑定），页面下拉与「创建班级」按钮联动正常。
注意 `course` 字段目前全 App 不显示，留空时存成 `'作业'` 只是为了满足 NOT NULL。

---

## 2026-11-27 · 成员管理＝席位管理（席位不变量）

产品口径：**成员管理的每一项都是数据库里的一个席位**，成员和 `class_roster` 一一对应。

原来不是这样：线上 8 个人里只有学委有席位，7 个同学是「有档案、没席位」（历史路径直接建 profile）。
这会让「退出后凭姓名学号加回来」「删除后重新认领」这些路径在某些人身上失效。分三步补齐。

### 1. 回填现有成员的席位

```sql
INSERT INTO public.class_roster (class_id, name, student_no, role, claimed_by)
SELECT p.class_id, p.name, COALESCE(p.student_no, ''),
       CASE WHEN p.role = 'monitor' THEN 'monitor' ELSE 'student' END, p.owner_id
FROM public.profiles p
WHERE p.class_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM public.class_roster r
                  WHERE r.class_id = p.class_id AND r.claimed_by = p.owner_id);
```

### 2. 一个账号在一个班只占一个席位

```sql
CREATE UNIQUE INDEX IF NOT EXISTS class_roster_claim_uniq
  ON public.class_roster (class_id, claimed_by) WHERE claimed_by IS NOT NULL;
```

（先查过重复：`GROUP BY class_id, claimed_by HAVING COUNT(*) > 1` 无结果才建索引。）

### 3. 触发器：档案变了，席位跟着变

```sql
CREATE OR REPLACE FUNCTION public.sync_member_seat() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
BEGIN
  IF NEW.class_id IS NULL THEN RETURN NEW; END IF;          -- 退班/未入班，不动席位
  UPDATE public.class_roster
     SET name = COALESCE(NEW.name, name),
         student_no = COALESCE(NEW.student_no, student_no),
         role = CASE WHEN NEW.role = 'monitor' THEN 'monitor' ELSE 'student' END
   WHERE class_id = NEW.class_id AND claimed_by = NEW.owner_id;
  IF NOT FOUND THEN
    INSERT INTO public.class_roster (class_id, name, student_no, role, claimed_by)
    VALUES (NEW.class_id, COALESCE(NEW.name,''), COALESCE(NEW.student_no,''),
            CASE WHEN NEW.role = 'monitor' THEN 'monitor' ELSE 'student' END, NEW.owner_id)
    ON CONFLICT DO NOTHING;
  END IF;
  RETURN NEW;
END $fn$;

CREATE TRIGGER profiles_sync_seat
AFTER INSERT OR UPDATE OF class_id, name, student_no, role ON public.profiles
FOR EACH ROW EXECUTE FUNCTION public.sync_member_seat();
```

要点：

- 只盯 `class_id / name / student_no / role` 四列（`UPDATE OF`），改头像这类高频写入不触发。
- `class_id IS NULL` 直接返回：退班会把自己 role 降成 student，但那时已经脱离班级，不能去动班里的席位。
- 触发器里绝不 `RAISE`：这条链路上是注册和加入，历史上已经因为自锁挂过三次。
- 副产物：转让学委、编辑成员姓名都会自动同步到席位，不需要客户端额外调用。

### 4. 客户端（`CloudApi.members` / `deleteMember`）

- `/members` 改成**席位驱动**：遍历 `class_roster`，`claimed_by` 有值→成员（头像是从 `profiles` 补的），空→「未认领的席位」。
  返回结构仍叫 `members` / `pending`，字段也兼容，**老版本 App 不受影响**。
- 排序：学委在前，其余按席位 id（＝加入顺序）。
- 删成员：删档案 + 删进度，席位**只解开绑定**（还回「未认领」），对方凭姓名学号还能自己加回来；想彻底清掉再到「未认领的席位」里移除。

### 验证记录

| 检查 | 结果 |
|---|---|
| 回填后能动2401 | 8 个成员各有一条已绑定席位（id 4、9~15）✔ |
| 插档案（class_id 非空） | 自动生成席位，`role` 跟随档案 ✔ |
| 改姓名 + 改 role | 席位同步成新姓名、新角色，且没有重复席位 ✔ |
| 索引 | `class_id + claimed_by` 唯一，防并发重复 ✔ |
| 清理 | 测试班 17 及假档案已删，现只剩能动2401 的 10 条席位（8 已绑定 + 2 条「测试服」空席）✔ |

**没验证的**：真机上「删除成员→席位变回未认领→对方重新加入」这一整条链路还没有真人走过。
---

## 2026-11-27 · 转让学委改为席位驱动

### 问题

旧实现按**账号**转：先查目标 `profiles` 存在，再 PATCH 两边 `role`。所以「名单里有这个人、但还没绑手机」的空席位根本转不了。
按席位模型，学委身份属于席位，应该能转给任何一条席位，包不包含账号都行。

### 函数

```sql
CREATE OR REPLACE FUNCTION public.transfer_monitor(seat_id bigint) RETURNS json
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE
  uid TEXT := auth.uid();
  cid BIGINT := public.current_class_id();
  r TEXT := public.current_class_role();
  tgt public.class_roster;
  mine BIGINT;
  holder TEXT;
BEGIN
  IF uid IS NULL THEN RAISE EXCEPTION '请先登录'; END IF;
  IF cid IS NULL THEN RAISE EXCEPTION '你还没有加入班级'; END IF;
  IF r <> 'monitor' THEN RAISE EXCEPTION '只有学委可以转让'; END IF;
  SELECT * INTO tgt FROM public.class_roster WHERE id = seat_id AND class_id = cid;
  IF NOT FOUND THEN RAISE EXCEPTION '没有这个席位'; END IF;
  SELECT id INTO mine FROM public.class_roster WHERE class_id = cid AND claimed_by = uid LIMIT 1;
  IF mine = seat_id THEN RAISE EXCEPTION '不能转让给这个席位'; END IF;
  holder := tgt.claimed_by;
  -- 账号角色先改（触发器会把各自席位的角色同步过去）
  UPDATE public.profiles SET role = 'student' WHERE owner_id = uid;
  IF holder IS NOT NULL AND holder <> uid THEN
    UPDATE public.profiles SET role = 'monitor' WHERE owner_id = holder;
  END IF;
  -- 再把席位角色钉死：目标是空席位时没有账号可改，只能靠这两步
  UPDATE public.class_roster SET role = 'student'
   WHERE class_id = cid AND role = 'monitor' AND id <> seat_id;
  UPDATE public.class_roster SET role = 'monitor' WHERE id = seat_id;
  RETURN json_build_object('ok', true, 'claimed', holder IS NOT NULL, 'seatId', seat_id);
END $fn$;

REVOKE ALL ON FUNCTION public.transfer_monitor(bigint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.transfer_monitor(bigint) TO authenticated;
NOTIFY pgrst, 'reload schema';
```

要点：**先改档案、再钉席位**。反过来的话，改档案会触发 `sync_member_seat`，把刚钉好的席位角色又按档案角色冲掉。
目标席位没人绑定时，班里就暂时没有学委（不能发布/揽收），等那个人加入认领席位，`join_class` 会把学委身份还给他。

### 客户端

- 路由从 `/members/{uid}/transfer` 换成 `/seats/{seatId}/transfer`。
- 「转让学委」页改列**席位**：已认领的显示头像和姓名，未认领的显示「未加入」标记，两种都能点。
- 确认框按类型给不同提示（转给空席位时明确说「在那之前班里没人能发布和揽收作业」）。

### 验证记录（临时班 18）

| 检查 | 结果 |
|---|---|
| 转给已认领席位 | 目标席位 monitor、自己的席位 student，两边档案角色同步 ✔ |
| 转给空席位 | 席位 19 变 monitor 且 `claimed_by` 仍为空，班里 0 个学委 ✔ |
| 空席位被认领 | 同学丙加入后 `role=monitor`，席位绑定，班里恰好 1 个学委 ✔ |
| 真机 | 转让页正确列出 7 位同学 + 2 条「未加入」的空席位，均可点 ✔ |
| 清理 | 测试班 18 已删，无残留 ✔ |

**没验证的**：真机上真正按下「转让」（会真的换学委），只在 SQL 层验过；界面止步于列表页。
---

## 2026-11-27 · 班级公开内容按席位署名（评论、作业）

### 边界（产品定的）

- **账号**＝一个人的私有空间：手机号、头像、私聊。私聊像「一台电脑的不同用户」，永远归账号，不随席位走。
- **席位**＝班级里的公开位置：成员列表、作业、评论、作业进度。

所以只把「班级公开内容」的署名搬到席位上，私聊一个字不动。

### 表结构

```sql
ALTER TABLE public.comments    ADD COLUMN IF NOT EXISTS seat_id BIGINT;
ALTER TABLE public.assignments ADD COLUMN IF NOT EXISTS publisher_seat_id BIGINT;

-- 回填历史数据
UPDATE public.comments c SET seat_id = r.id
FROM public.class_roster r
WHERE r.class_id = c.class_id AND r.claimed_by = c.owner_id AND c.seat_id IS NULL;

UPDATE public.assignments a SET publisher_seat_id = r.id
FROM public.class_roster r
WHERE r.class_id = a.class_id AND r.claimed_by = a.owner_id AND a.publisher_seat_id IS NULL;
```

### 写入时自动补席位

```sql
CREATE OR REPLACE FUNCTION public.fill_content_seat() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
BEGIN
  IF TG_TABLE_NAME = 'comments' THEN
    IF NEW.seat_id IS NULL AND NEW.class_id IS NOT NULL THEN
      SELECT r.id INTO NEW.seat_id FROM public.class_roster r
       WHERE r.class_id = NEW.class_id AND r.claimed_by = NEW.owner_id LIMIT 1;
    END IF;
  ELSIF TG_TABLE_NAME = 'assignments' THEN
    IF NEW.publisher_seat_id IS NULL AND NEW.class_id IS NOT NULL THEN
      SELECT r.id INTO NEW.publisher_seat_id FROM public.class_roster r
       WHERE r.class_id = NEW.class_id AND r.claimed_by = NEW.owner_id LIMIT 1;
    END IF;
  END IF;
  RETURN NEW;
END $fn$;

CREATE TRIGGER comments_fill_seat    BEFORE INSERT ON public.comments    FOR EACH ROW EXECUTE FUNCTION public.fill_content_seat();
CREATE TRIGGER assignments_fill_seat BEFORE INSERT ON public.assignments FOR EACH ROW EXECUTE FUNCTION public.fill_content_seat();
```

老客户端不用改：`owner_id` 还是它写的那个，触发器自己补 `seat_id`。RLS 一条没动。

### 读取（客户端）

- 评论作者：`seat_id` 有值就用席位的姓名/角色，头像仍从 `profiles` 补；只有席位和档案都查不到才显示「已注销的同学」。
- 作业发布人：`publisher_seat_id` 优先，退回 `owner_id` 查档案。

### 验证记录

| 检查 | 结果 |
|---|---|
| 回填 | 6 条评论、1 次作业全部带上席位 id（6/6、1/1）✔ |
| 触发器 | 临时班 19 里插作业/评论，`publisher_seat_id`、`seat_id` 自动填成席位 22 ✔ |
| 清理 | 测试班 19 已删，能源2401 数据未动 ✔ |

**没验证的**：真机上「作者退班后评论仍显示真名」这个场景，需要真有人退班发过评论才能看到。

---

## 2026-11-27 · 成员管理：编辑改成编辑席位，删掉重置密码

- 「重置密码」整个删掉（登录早就换成手机号/WorkBuddy，这个按钮点了只会报错）。
- 成员那一行原来走 `PATCH /members/{账号}` 改档案，现在改成 `PATCH /seats/{席位 id}`：
  先改 `class_roster` 的姓名/学号，**已认领的席位再把那个账号的档案一起改**，免得个人页还显示旧名字。
- 「未认领的席位」也补了「编辑」：以前名单录错了学号只能移除重加，现在直接改。
- 自己改自己的名字还走个人页（`PATCH /members/{自己}`），改完触发器会把席位同步过去。

真机验证：成员行只剩「编辑 / 删除」，未认领席位是「编辑 / 移除」，解散班级仍在最下面。

---

## 2026-11-27 · 班级空间按席位列出

班级空间原来只列 `profiles`（有账号的人），空席位完全看不见 —— 学委转给空席位之后，没人知道学委挂在哪。
现在列**全部席位**：已认领的带头像、能发起会话；未认领的显示「未认领」和一条横杠（开不了会话）。

接口 `/chat/members` 同时返回两个数组：

- `members`：只含已认领的席位（字段和以前一致），老版本 App 读这个，行为不变；
- `seats`：全部席位，字段 `seatId / id / name / studentNo / role / claimed / self / avatar`。

排序：学委席位最前，其余按席位 id。徽章按**席位角色**给（所以空着的学委席位也会显示「学委」+「未认领」两个徽章）。

客户端「班级空间」优先读 `seats`，缓存里没有就退回 `members` 并当作全部已认领，避免升级瞬间读旧缓存闪一下。

真机验证：能动2401 显示「测试服（管理员）」带学委徽章（当前账号）、「测试服」「同学A」两条未认领、7 位同学带 ＋。

---

## 2026-11-27 · 导入能动2401 班名单（28 人到席位表）

数据源：`本地名单文件（含真实姓名学号，不入库）`（单表，A 列学号、B 列姓名，28 行，无空行无重复）。

```sql
INSERT INTO public.class_roster (class_id, name, student_no, role, claimed_by)
SELECT 1, v.name, v.no, 'student', NULL
FROM (VALUES ('张某','U2023xxxxxx'), ('李某','U2024xxxxxx'), …共 28 行，真实名单不入仓库…)
     AS v(name, no)
WHERE NOT EXISTS (
  SELECT 1 FROM public.class_roster r
  WHERE r.class_id = 1 AND upper(trim(r.student_no)) = upper(v.no)
);
```

用 `NOT EXISTS` 按学号去重，所以重复跑不会插重（库里已有 7 个已认领的同学和 1 条「同学A」空席位都被跳过）。

结果（`class_id = 1`）：**30 条席位** = 28 条真实学生 + 2 条历史测试数据。
28 条里 7 条已认领（同学C、同学B、同学E、同学F、同学G、同学H、同学D），21 条空着等认领；学号无重复。

待清理：
- `id=1 测试服 (u202413157a)`：早期测试数据，可删。
- `id=2 测试服（管理员）(U202413157b)`：**当前登录账号就绑在它上面**，且 `role=monitor`。
  正确收拾顺序是：先删 `id=4 同学A (U202413157)` 这条空席位 → 再到个人页把姓名/学号改成 `同学A / U202413157`（席位跟着改）
  → 最后删 `id=1`。直接删 `id=2` 会让账号「在班里但没有席位」，破坏「成员=席位」这个不变量。
- 名单里有 1 个 2023 级学号（其余 27 人都是 2024 级），按名单原样导入，如系留级/转专业需自行确认。

---

## 2026-11-27 · 作业管理按席位列人（补进度行）

问题：作业管理读的是 `submissions`（一人一条进度），而批量导入的 21 条席位**没有进度行**，所以管理页里看不见这些人。

### 1. 数据补一次

```sql
INSERT INTO public.submissions (assignment_id, student_ref, status, class_id, owner_id)
SELECT a.id, 'roster:' || r.id, 'not_submitted', a.class_id, a.owner_id
FROM public.assignments a
JOIN public.class_roster r ON r.class_id = a.class_id
WHERE a.class_id = 1 AND r.claimed_by IS NULL
  AND NOT EXISTS (SELECT 1 FROM public.submissions s
                  WHERE s.assignment_id = a.id AND s.student_ref = 'roster:' || r.id);
```

补完：能动2401 的「工程热力学」共 **29 条席位 ↔ 29 条进度行**，一一对应。

### 2. 代码里加自愈（`board()`）

`fillMissingSeatSubmissions(assignmentId)`：打开作业管理时，按席位逐个比对进度行，
缺的当场补一条 `not_submitted`（学委有 INSERT 权限）。这样以后**任何途径加进来的席位**（批量导入、退班补建、
直接改库）都不会在管理页凭空消失。

真机验证：作业管理页正常列出 8 位已认领的同学（同学A显示「学委」、同学B「已写完」），
21 条未认领席位在其下方（页面上滚动可见，当前无法注入点击，只验到数据层 29↔29）。

**副作用提醒**：首页作业卡片的「已交 / 总人数」现在按 29 个席位统计，所以在 21 人还没进来之前，
分母会偏大（真实在班 8 人）。这是「按席位统计」的必然结果，等同学陆续认领就正常了。

---

## 2026-11-27 · 系统公告补上通知链路

排查「发了系统消息大家能不能看到」时发现：

- 权限没问题：`chat_threads_select` / `chat_messages_select` 都是 `class_id = current_class_id() AND (kind='system' OR 我是成员)`，
  所以**全班都能读到**这条公告，一个班只有一条「系统通知」会话。
- 但**触达为零**：`postSystemMessage()` 只往 `chat_messages` 写，而通知栏提醒和后台轮询走的是另一张表 `messages`。
  对比「发布作业」那条路径：它是 `messages` + `chat_messages` 双写。
  结果就是公告发出去，谁不主动点「消息 → 系统通知」就永远看不到，也没有红点（App 没有未读状态）。

### 改法

`postSystemMessage()` 在写完会话后，再补一条班级消息（失败不影响主流程）：

```kotlin
write("POST", "messages", "", JSONObject()
    .put("kind", "notice").put("title", "班级公告").put("body", text.take(500)))
```

这样就和发布作业同一条链路了：

- App 在前台：`checkMessages()` 每 8 秒轮询 `/messages` → `MessageAlerts.publish()` 弹通知；
- App 在后台：`MessageJob` 每 15 分钟一次 → 同样弹通知；
- 老版本 App 也会收到，因为它们轮询的也是 `/messages`（这个能力一直都有）。

`messages.kind` 没有 CHECK 约束，所以新增 `notice` 不需要改表。

---

## 2026-11-27 · 底部「消息」红点消不掉

现象：来一条新消息，底部 dock 的「消息」出现红点，之后**怎么点都消不掉**。

原因：红点的判定是 `latestMessageId > prefs.seen_message`，但 `seen_message` **只在两个地方被写入**：
`checkMessages()` 首次初始化（`if (!prefs.contains("seen_message"))`）和「发布作业」成功后。
打开「消息」页、点开任何一个会话，都不写。所以红点一旦亮起，就再也不会灭。

修复（客户端）：

- `messages()` 进入时 `noteSeen(latestMessageId)`；
- `chatThread()` 打开任意会话时同样标记；
- `checkMessages()` 里如果 `currentTab == "message"`，把新消息也标记为已读（人正看着消息页，不该再冒红点）；
- `screen()` 记录 `currentTab`，供上面判断。

两种时序都覆盖：冷启动时 `latestMessageId` 还是 0（此时条件为假、本来就不显示红点），
轮询回来后如果人停在消息页会立刻标记已读；如果人已经离开消息页，下次进来也会标记。

---

## 2026-11-27 · 安卓 14 以上应用内更新失败（重要）

现象：点「立即更新」弹 toast `安装失败：The commit() status receiver should come from a mutable PendingIntent`。

原因：`installApk()` 里用 `PendingIntent.getBroadcast(..., FLAG_IMMUTABLE)` 作为
`PackageInstaller.Session.commit()` 的状态接收器。**Android 14 起强制要求这个 PendingIntent 是 mutable 的**
（系统要往里塞 `EXTRA_STATUS` 等结果），否则 `commit()` 直接抛 `IllegalArgumentException`。

### 改法

```kotlin
val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
PendingIntent.getBroadcast(this, sessionId, Intent(this, InstallResultReceiver::class.java),
    PendingIntent.FLAG_UPDATE_CURRENT or mutable)
```

这是显式 Intent（指向本应用的接收器），用 mutable 不违反 Android 12 对隐式 PendingIntent 的限制。

同时补上 `InstallResultReceiver` 对 `STATUS_PENDING_USER_ACTION` 的处理：把 `EXTRA_INTENT` 拉起来，
否则部分机型上点了更新不会有任何反应（只有系统主动弹框时才看得到）。这也正是 mutable 的意义所在。

### 验证（真机 Android 16）

用临时入口直接跑「下载 + 安装」：

```
I Homework: install status=-1 message=                                  ← PENDING_USER_ACTION，系统安装框被拉起
D PackageInstallerSession: ... INSTALL_FAILED_VERSION_DOWNGRADE ...      ← 我拿 36 覆盖 37，系统拒绝降级
I Homework: install status=4 message=INSTALL_FAILED_VERSION_DOWNGRADE
```

`commit()` 不再抛异常，状态回调完整走通（-1 → 4），失败原因也如实透传给用户。
（测试时还需 `appops set <pkg> REQUEST_INSTALL_PACKAGES allow`，否则会先跳「安装未知应用」设置页。）

### 后果

**37 之前的包在 Android 14+ 上无法自更新**，只能手动装一次；装上 37 之后自更新恢复正常。
Android 13 及以下的旧包不受影响，可以正常走应用内更新。

---

## 2026-11-27 · 应用内更新报「解析软件包时出现问题」

37 修掉 mutable PendingIntent 之后，安装会话终于能提交，紧接着暴露出下一层问题：系统解析失败。

排查：服务器上的包是好的。用 curl 拉线上 APK 与本地编译产物比对，
**md5 一致（`00abbdda…`）、790994 字节、`PK\x03\x04` 开头、能正常解包**。所以不是打包或发布的问题，
是**客户端拿到的字节不完整**（网络/网关中断时会给半截内容或一个 HTML 页面），丢给系统安装就是「解析软件包时出现问题」。

### 改法（`downloadUpdate`）

1. 用 `HttpURLConnection` 显式设置 15s 连接 / 60s 读取超时；
2. 校验三件事：字节数 ≥ 100KB、`Content-Length` 与实际读到的长度一致、
   **让 `PackageManager.getPackageArchiveInfo` 真正解析一遍**，包名必须是本应用、`versionCode` 必须等于 `version.json` 里的值；
3. 不合格就重下，最多 3 次，每次失败都写日志（`Homework: update bytes=… declared=… attempt=…`）；
4. 三次都不行：弹窗「更新包没下完整」，给一个「用浏览器打开」直接跳下载页的退路，而不是把坏文件丢给系统。

### 状态

代码已提交，**真机还没验**——测试机在验证途中掉线（adb: no devices），
`install-tool` 那一步只跑到 37（见上一节的 -1 → 4 记录）。38 的下载校验需要在手机重新连上后跑一次：
用临时入口只下载不安装，看 logcat 里 `bytes` 是否等于 792470。
