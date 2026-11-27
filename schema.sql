-- 作业物流云数据库。由管理侧逐条执行，应用代码不会跑这些语句。
-- 不要 TRUNCATE，不要 DROP TABLE。库是空的，从零建。
-- auth.uid() 是 text，owner 列一律 TEXT。

CREATE TABLE public.profiles (
  owner_id TEXT PRIMARY KEY DEFAULT auth.uid(),
  student_no TEXT UNIQUE,
  name TEXT NOT NULL,
  role TEXT NOT NULL CHECK (role IN ('student', 'monitor', 'ta')),
  avatar_path TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE public.class_info (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name TEXT NOT NULL,
  course TEXT NOT NULL,
  owner_id TEXT NOT NULL DEFAULT auth.uid(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE public.assignments (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  title TEXT NOT NULL,
  description TEXT NOT NULL DEFAULT '',
  week TEXT NOT NULL DEFAULT '',
  deadline TIMESTAMPTZ,
  mode TEXT NOT NULL DEFAULT 'collect',
  owner_id TEXT NOT NULL DEFAULT auth.uid(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE public.assignment_images (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  assignment_id BIGINT NOT NULL REFERENCES public.assignments (id) ON DELETE CASCADE,
  storage_path TEXT NOT NULL,
  owner_id TEXT NOT NULL DEFAULT auth.uid(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE public.submissions (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  assignment_id BIGINT NOT NULL REFERENCES public.assignments (id) ON DELETE CASCADE,
  student_ref TEXT NOT NULL,
  status TEXT NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  owner_id TEXT NOT NULL DEFAULT auth.uid(),
  UNIQUE (assignment_id, student_ref)
);

CREATE TABLE public.events (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  submission_id BIGINT NOT NULL REFERENCES public.submissions (id) ON DELETE CASCADE,
  status TEXT NOT NULL,
  note TEXT NOT NULL DEFAULT '',
  owner_id TEXT NOT NULL DEFAULT auth.uid(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE public.messages (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  kind TEXT NOT NULL,
  title TEXT NOT NULL,
  body TEXT NOT NULL,
  assignment_id BIGINT,
  owner_id TEXT NOT NULL DEFAULT auth.uid(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE OR REPLACE FUNCTION public.current_class_role()
RETURNS TEXT
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
  SELECT role FROM public.profiles WHERE owner_id = auth.uid()
$$;

REVOKE ALL ON FUNCTION public.current_class_role() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.current_class_role() TO authenticated;

CREATE OR REPLACE FUNCTION public.enforce_submission_status()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  actor TEXT := public.current_class_role();
  mode TEXT;
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.status <> 'not_submitted' THEN
      RAISE EXCEPTION '新进度必须从未写完开始' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
  END IF;
  IF NEW.status = OLD.status THEN
    RETURN NEW;
  END IF;
  SELECT a.mode INTO mode FROM public.assignments a WHERE a.id = NEW.assignment_id;
  IF mode IS NULL THEN
    mode := 'collect';
  END IF;
  IF mode = 'collect' THEN
    IF OLD.status = 'not_submitted' AND NEW.status = 'ready_to_submit' AND NEW.student_ref = auth.uid() AND actor IN ('student', 'monitor') THEN
      RETURN NEW;
    END IF;
    IF OLD.status = 'ready_to_submit' AND NEW.status = 'not_submitted' AND NEW.student_ref = auth.uid() AND actor IN ('student', 'monitor') THEN
      RETURN NEW;
    END IF;
    IF OLD.status <> 'collected_by_monitor' AND NEW.status = 'collected_by_monitor' AND actor = 'monitor' THEN
      RETURN NEW;
    END IF;
    IF OLD.status = 'collected_by_monitor' AND actor = 'monitor' AND NEW.status IN ('ready_to_submit', 'not_submitted', 'retrieved_by_monitor') THEN
      RETURN NEW;
    END IF;
    IF OLD.status = 'retrieved_by_monitor' AND actor = 'monitor' AND NEW.status IN ('distributed', 'collected_by_monitor') THEN
      RETURN NEW;
    END IF;
    IF OLD.status = 'distributed' AND NEW.status = 'retrieved_by_monitor' AND actor = 'monitor' THEN
      RETURN NEW;
    END IF;
    RAISE EXCEPTION '现在不能改这一步' USING ERRCODE = '42501';
  END IF;
  IF actor = 'monitor' THEN
    RETURN NEW;
  END IF;
  RAISE EXCEPTION '现在不能改这一步' USING ERRCODE = '42501';
END;
$$;

REVOKE ALL ON FUNCTION public.enforce_submission_status() FROM PUBLIC;

CREATE TRIGGER submissions_status
BEFORE INSERT OR UPDATE OF status ON public.submissions
FOR EACH ROW
EXECUTE FUNCTION public.enforce_submission_status();

GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO authenticated, anon;

ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.profiles TO authenticated, anon;
DROP POLICY IF EXISTS profiles_select ON public.profiles;
CREATE POLICY profiles_select ON public.profiles FOR SELECT TO authenticated USING (true);
DROP POLICY IF EXISTS profiles_insert ON public.profiles;
CREATE POLICY profiles_insert ON public.profiles FOR INSERT TO authenticated WITH CHECK (owner_id = auth.uid() AND ((role = 'monitor' AND NOT EXISTS (SELECT 1 FROM public.profiles AS existing WHERE existing.owner_id <> auth.uid())) OR (role = 'student' AND EXISTS (SELECT 1 FROM public.profiles AS existing WHERE existing.owner_id <> auth.uid()))));
DROP POLICY IF EXISTS profiles_update ON public.profiles;
CREATE POLICY profiles_update ON public.profiles FOR UPDATE TO authenticated USING (owner_id = auth.uid() OR public.current_class_role() = 'monitor') WITH CHECK (public.current_class_role() = 'monitor' OR (owner_id = auth.uid() AND role = public.current_class_role()));
DROP POLICY IF EXISTS profiles_delete ON public.profiles;
CREATE POLICY profiles_delete ON public.profiles FOR DELETE TO authenticated USING (public.current_class_role() = 'monitor' AND owner_id <> auth.uid() AND role <> 'monitor');

ALTER TABLE public.class_info ENABLE ROW LEVEL SECURITY;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.class_info TO authenticated, anon;
DROP POLICY IF EXISTS class_info_select ON public.class_info;
CREATE POLICY class_info_select ON public.class_info FOR SELECT TO authenticated USING (true);
DROP POLICY IF EXISTS class_info_insert ON public.class_info;
CREATE POLICY class_info_insert ON public.class_info FOR INSERT TO authenticated WITH CHECK (owner_id = auth.uid() AND NOT EXISTS (SELECT 1 FROM public.class_info AS existing WHERE existing.owner_id <> auth.uid()));
DROP POLICY IF EXISTS class_info_update ON public.class_info;
CREATE POLICY class_info_update ON public.class_info FOR UPDATE TO authenticated USING (public.current_class_role() = 'monitor') WITH CHECK (public.current_class_role() = 'monitor');

ALTER TABLE public.assignments ENABLE ROW LEVEL SECURITY;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.assignments TO authenticated, anon;
DROP POLICY IF EXISTS assignments_select ON public.assignments;
CREATE POLICY assignments_select ON public.assignments FOR SELECT TO authenticated USING (true);
DROP POLICY IF EXISTS assignments_insert ON public.assignments;
CREATE POLICY assignments_insert ON public.assignments FOR INSERT TO authenticated WITH CHECK (owner_id = auth.uid() AND public.current_class_role() = 'monitor');
DROP POLICY IF EXISTS assignments_update ON public.assignments;
CREATE POLICY assignments_update ON public.assignments FOR UPDATE TO authenticated USING (public.current_class_role() = 'monitor') WITH CHECK (public.current_class_role() = 'monitor');
DROP POLICY IF EXISTS assignments_delete ON public.assignments;
CREATE POLICY assignments_delete ON public.assignments FOR DELETE TO authenticated USING (public.current_class_role() = 'monitor');

ALTER TABLE public.assignment_images ENABLE ROW LEVEL SECURITY;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.assignment_images TO authenticated, anon;
DROP POLICY IF EXISTS assignment_images_select ON public.assignment_images;
CREATE POLICY assignment_images_select ON public.assignment_images FOR SELECT TO authenticated USING (true);
DROP POLICY IF EXISTS assignment_images_insert ON public.assignment_images;
CREATE POLICY assignment_images_insert ON public.assignment_images FOR INSERT TO authenticated WITH CHECK (owner_id = auth.uid() AND public.current_class_role() = 'monitor');
DROP POLICY IF EXISTS assignment_images_delete ON public.assignment_images;
CREATE POLICY assignment_images_delete ON public.assignment_images FOR DELETE TO authenticated USING (public.current_class_role() = 'monitor');

ALTER TABLE public.submissions ENABLE ROW LEVEL SECURITY;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.submissions TO authenticated, anon;
DROP POLICY IF EXISTS submissions_select ON public.submissions;
CREATE POLICY submissions_select ON public.submissions FOR SELECT TO authenticated USING (true);
DROP POLICY IF EXISTS submissions_insert ON public.submissions;
CREATE POLICY submissions_insert ON public.submissions FOR INSERT TO authenticated WITH CHECK (owner_id = auth.uid() AND public.current_class_role() = 'monitor');
DROP POLICY IF EXISTS submissions_update ON public.submissions;
CREATE POLICY submissions_update ON public.submissions FOR UPDATE TO authenticated USING (public.current_class_role() = 'monitor' OR student_ref = auth.uid()) WITH CHECK (public.current_class_role() = 'monitor' OR student_ref = auth.uid());
DROP POLICY IF EXISTS submissions_delete ON public.submissions;
CREATE POLICY submissions_delete ON public.submissions FOR DELETE TO authenticated USING (public.current_class_role() = 'monitor');

ALTER TABLE public.events ENABLE ROW LEVEL SECURITY;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.events TO authenticated, anon;
DROP POLICY IF EXISTS events_select ON public.events;
CREATE POLICY events_select ON public.events FOR SELECT TO authenticated USING (true);
DROP POLICY IF EXISTS events_insert ON public.events;
CREATE POLICY events_insert ON public.events FOR INSERT TO authenticated WITH CHECK (owner_id = auth.uid());

ALTER TABLE public.messages ENABLE ROW LEVEL SECURITY;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.messages TO authenticated, anon;
DROP POLICY IF EXISTS messages_select ON public.messages;
CREATE POLICY messages_select ON public.messages FOR SELECT TO authenticated USING (true);
DROP POLICY IF EXISTS messages_insert ON public.messages;
CREATE POLICY messages_insert ON public.messages FOR INSERT TO authenticated WITH CHECK (owner_id = auth.uid() AND public.current_class_role() = 'monitor');
DROP POLICY IF EXISTS messages_delete ON public.messages;
CREATE POLICY messages_delete ON public.messages FOR DELETE TO authenticated USING (public.current_class_role() = 'monitor');

CREATE TABLE IF NOT EXISTS public.subjects (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name TEXT NOT NULL UNIQUE,
  owner_id TEXT NOT NULL DEFAULT auth.uid(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE public.assignments ADD COLUMN IF NOT EXISTS week TEXT NOT NULL DEFAULT '';

ALTER TABLE public.subjects ENABLE ROW LEVEL SECURITY;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.subjects TO authenticated;
DROP POLICY IF EXISTS subjects_select ON public.subjects;
CREATE POLICY subjects_select ON public.subjects FOR SELECT TO authenticated USING (true);
DROP POLICY IF EXISTS subjects_insert ON public.subjects;
CREATE POLICY subjects_insert ON public.subjects FOR INSERT TO authenticated WITH CHECK (owner_id = auth.uid() AND public.current_class_role() = 'monitor');
DROP POLICY IF EXISTS subjects_delete ON public.subjects;
CREATE POLICY subjects_delete ON public.subjects FOR DELETE TO authenticated USING (public.current_class_role() = 'monitor');

GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO authenticated;

-- 已有库追加：作业评论。不要重跑上面的语句，不要 DROP TABLE。
CREATE TABLE IF NOT EXISTS public.comments (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  assignment_id BIGINT NOT NULL REFERENCES public.assignments (id) ON DELETE CASCADE,
  body TEXT NOT NULL DEFAULT '',
  owner_id TEXT NOT NULL DEFAULT auth.uid(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.comment_images (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  comment_id BIGINT NOT NULL REFERENCES public.comments (id) ON DELETE CASCADE,
  storage_path TEXT NOT NULL,
  owner_id TEXT NOT NULL DEFAULT auth.uid(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE public.comments ENABLE ROW LEVEL SECURITY;
GRANT SELECT, INSERT, DELETE ON TABLE public.comments TO authenticated;
DROP POLICY IF EXISTS comments_select ON public.comments;
CREATE POLICY comments_select ON public.comments FOR SELECT TO authenticated USING (true);
DROP POLICY IF EXISTS comments_insert ON public.comments;
CREATE POLICY comments_insert ON public.comments FOR INSERT TO authenticated WITH CHECK (owner_id = auth.uid());
DROP POLICY IF EXISTS comments_delete ON public.comments;
CREATE POLICY comments_delete ON public.comments FOR DELETE TO authenticated USING (owner_id = auth.uid() OR public.current_class_role() = 'monitor');

ALTER TABLE public.comment_images ENABLE ROW LEVEL SECURITY;
GRANT SELECT, INSERT, DELETE ON TABLE public.comment_images TO authenticated;
DROP POLICY IF EXISTS comment_images_select ON public.comment_images;
CREATE POLICY comment_images_select ON public.comment_images FOR SELECT TO authenticated USING (true);
DROP POLICY IF EXISTS comment_images_insert ON public.comment_images;
CREATE POLICY comment_images_insert ON public.comment_images FOR INSERT TO authenticated WITH CHECK (
  owner_id = auth.uid()
  AND EXISTS (
    SELECT 1 FROM public.comments AS comment
    WHERE comment.id = comment_id AND comment.owner_id = auth.uid()
  )
);
DROP POLICY IF EXISTS comment_images_delete ON public.comment_images;
CREATE POLICY comment_images_delete ON public.comment_images FOR DELETE TO authenticated USING (owner_id = auth.uid() OR public.current_class_role() = 'monitor');

GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO authenticated;
NOTIFY pgrst, 'reload schema';
