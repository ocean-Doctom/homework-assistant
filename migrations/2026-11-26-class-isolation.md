# 云端数据库变更记录

git 管不到数据库。这里按时间记录每一次手工执行的 SQL，用于追溯和重建。
**以后每次改表结构、函数、触发器或 RLS 策略，都在这里追加一节。**

---

## 2026-11-26 · 多班级隔离（class_id）

### 1. 新增字段

```sql
-- 班级表加邀请码
ALTER TABLE class_info ADD COLUMN IF NOT EXISTS code TEXT;
UPDATE class_info SET code = upper(substr(md5(random()::text), 1, 6)) WHERE code IS NULL;
ALTER TABLE class_info ALTER COLUMN code SET NOT NULL;
ALTER TABLE class_info ADD CONSTRAINT class_info_code_key UNIQUE (code);
ALTER TABLE class_info ADD CONSTRAINT class_info_name_key UNIQUE (name);  -- 班名唯一

-- 成员表指向班级（注意：注册时由客户端显式传 class_id，不能用 DEFAULT）
ALTER TABLE profiles ADD COLUMN IF NOT EXISTS class_id BIGINT;
UPDATE profiles SET class_id = (SELECT MIN(id) FROM class_info) WHERE class_id IS NULL;
ALTER TABLE profiles ALTER COLUMN class_id SET NOT NULL;
```

### 2. 业务表统一加 class_id

覆盖 11 张表：`assignments` `assignment_images` `submissions` `events` `subjects`
`comments` `comment_images` `messages` `chat_threads` `chat_messages` `chat_message_images`

```sql
-- 每张表统一执行
ALTER TABLE <t> ADD COLUMN IF NOT EXISTS class_id BIGINT;
UPDATE <t> SET class_id = 1 WHERE class_id IS NULL;          -- 历史数据归入第一个班
ALTER TABLE <t> ALTER COLUMN class_id SET NOT NULL;
ALTER TABLE <t> ALTER COLUMN class_id SET DEFAULT public.current_class_id();
```

`DEFAULT current_class_id()` 是让老客户端不用改查询也能正确归属的关键。

### 3. 关键函数

```sql
-- 当前登录用户所在班级（RLS 的基础）
CREATE OR REPLACE FUNCTION public.current_class_id() RETURNS BIGINT
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
  SELECT class_id FROM public.profiles WHERE owner_id = auth.uid() LIMIT 1
$$;

-- 数一个班的人数（绕过 RLS，供策略使用）
CREATE OR REPLACE FUNCTION public.class_member_count(cid BIGINT) RETURNS INT
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
  SELECT COUNT(*)::INT FROM public.profiles WHERE class_id = cid
$$;

-- 解散班级：只有学委 + 班里只剩一人时允许，整体事务删除
CREATE OR REPLACE FUNCTION public.dissolve_class() RETURNS INT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE
  cid BIGINT := public.current_class_id();
  r TEXT := public.current_class_role();
  n INT;
BEGIN
  IF cid IS NULL THEN RAISE EXCEPTION '你还没加入任何班级'; END IF;
  IF r <> 'monitor' THEN RAISE EXCEPTION '只有学委可以解散班级'; END IF;
  n := public.class_member_count(cid);
  IF n > 1 THEN RAISE EXCEPTION '班里还有 % 位成员，不能解散', n; END IF;
  DELETE FROM comments WHERE class_id = cid;
  DELETE FROM comment_images WHERE class_id = cid;
  DELETE FROM assignments WHERE class_id = cid;
  DELETE FROM assignment_images WHERE class_id = cid;
  DELETE FROM messages WHERE class_id = cid;
  DELETE FROM subjects WHERE class_id = cid;
  DELETE FROM chat_threads WHERE class_id = cid;
  DELETE FROM chat_messages WHERE class_id = cid;
  DELETE FROM chat_message_images WHERE class_id = cid;
  DELETE FROM submissions WHERE class_id = cid;
  DELETE FROM events WHERE class_id = cid;
  DELETE FROM profiles WHERE class_id = cid;
  DELETE FROM class_info WHERE id = cid;
  RETURN 0;
END $fn$;
```

### 4. 触发器

```sql
-- 插入成员时自动决定角色：本班第一个是学委，其余是同学
CREATE OR REPLACE FUNCTION public.auto_profile_role() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
BEGIN
  IF (SELECT COUNT(*) FROM profiles WHERE class_id = NEW.class_id) = 0 THEN
    NEW.role := 'monitor';
  ELSE
    NEW.role := 'student';
  END IF;
  RETURN NEW;
END $fn$;

CREATE TRIGGER profiles_auto_role BEFORE INSERT ON public.profiles
FOR EACH ROW EXECUTE FUNCTION public.auto_profile_role();

-- 新成员加入时，自动补上本班所有已有作业的进度
CREATE OR REPLACE FUNCTION public.backfill_submissions() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
BEGIN
  INSERT INTO submissions (assignment_id, student_ref, status, class_id, owner_id)
  SELECT a.id, NEW.owner_id, 'not_submitted', NEW.class_id, a.owner_id
  FROM assignments a
  WHERE a.class_id = NEW.class_id AND NEW.role <> 'ta'
    AND NOT EXISTS (SELECT 1 FROM submissions s
                    WHERE s.assignment_id = a.id AND s.student_ref = NEW.owner_id);
  RETURN NEW;
END $fn$;

CREATE TRIGGER profiles_backfill_submissions AFTER INSERT ON public.profiles
FOR EACH ROW EXECUTE FUNCTION public.backfill_submissions();
```

### 5. RLS 策略

所有策略都从"全局可见"改成"按 `class_id` 隔离"。完整清单见下表（
`class_info` 的 SELECT 保持 `true`，因为注册时要按班名查找）。

| 表 | SELECT | INSERT | UPDATE | DELETE |
|---|---|---|---|---|
| class_info | `true` | `owner_id = uid` | 本班学委 | — |
| profiles | `class_id = cid OR owner_id = uid` | `owner_id = uid AND class_id IS NOT NULL` | 本班学委或本人 | 学委删非学委 |
| assignments | `class_id = cid` | 本班学委 | 本班学委 | 本班学委 |
| submissions | `class_id = cid` | 本班学委 | 本人或学委 | 学委 |
| events | `class_id = cid` | `owner_id = uid` | — | — |
| subjects | `class_id = cid` | 本班学委 | — | 学委 |
| comments | `class_id = cid` | `owner_id = uid` | — | 本人或学委 |
| messages | `class_id = cid` | 本班学委 | — | 学委 |
| chat_threads | `class_id = cid AND (系统会话或参与者)` | 仅 direct 且自己参与 | — | 同上 |
| chat_messages | `class_id = cid AND 会话可读` | 本人 + 会话可写 | 本人 | 本人 |

`cid` = `public.current_class_id()`，`uid` = `auth.uid()`。

---

## 踩过的坑（都曾导致注册失败）

1. **策略里用子查询查自己的表会自锁**：`profiles` 的 INSERT 策略里查 `profiles` 判断人数，
   子查询受 `profiles_select` 限制，新用户 `current_class_id()` 是 NULL → 查不到人 → 判定错误。
   **解法**：改用 `SECURITY DEFINER` 函数（`class_member_count`）。

2. **`INSERT ... RETURNING` 需要 SELECT 权限**：PostgREST 的 `Prefer: return=representation`
   会校验新行能不能被查出来。新用户刚插入时 `current_class_id()` 仍为 NULL → 被拒。
   **解法**：`profiles_select` 加 `OR owner_id = auth.uid()`。

3. **老成员加入后不补作业进度**：作业发布时只给当时的成员建 `submissions`。
   **解法**：`profiles_backfill_submissions` 触发器。
