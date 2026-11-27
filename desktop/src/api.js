import { cloud } from "./cloud.js";
import {
  advanceLabel,
  canAdvance,
  canCollect,
  canRevert,
  nextStatus,
  previousStatus,
  statusLabel,
} from "./domain.js";

const CACHE_KEY = "homework-logistics-cache";
const AVATAR_KEY = "homework-logistics-avatars";

export const DEFAULT_BASE = "";

export function loadBase() {
  return "";
}

export function saveBase() {}

export function requestUrl(_base, path) {
  return path;
}

export function loadToken() {
  return "";
}

export function saveToken() {}

export function clearCache() {
  localStorage.removeItem(CACHE_KEY);
}

export function readCache(_token, path) {
  try {
    const all = JSON.parse(localStorage.getItem(CACHE_KEY) || "{}");
    return all[path] ?? null;
  } catch {
    return null;
  }
}

function remember(path, data) {
  const all = (() => {
    try {
      return JSON.parse(localStorage.getItem(CACHE_KEY) || "{}");
    } catch {
      return {};
    }
  })();
  all[path] = data;
  const keys = Object.keys(all);
  if (keys.length > 40) delete all[keys[0]];
  localStorage.setItem(CACHE_KEY, JSON.stringify(all));
}

export function readAvatar(src) {
  if (!src) return "";
  try {
    const all = JSON.parse(localStorage.getItem(AVATAR_KEY) || "{}");
    const value = all[src];
    if (!value) return "";
    if (typeof value === "string") return value.startsWith("data:") ? value : "";
    if (value.exp > Date.now()) return value.url || "";
    return "";
  } catch {
    return "";
  }
}

export function rememberAvatar(src, url, ttlSeconds = 0) {
  if (!src || !url) return;
  const all = (() => {
    try {
      return JSON.parse(localStorage.getItem(AVATAR_KEY) || "{}");
    } catch {
      return {};
    }
  })();
  all[src] = ttlSeconds ? { url, exp: Date.now() + ttlSeconds * 1000 } : url;
  const keys = Object.keys(all);
  while (keys.length > 40) delete all[keys.shift()];
  try {
    localStorage.setItem(AVATAR_KEY, JSON.stringify(all));
  } catch {
    localStorage.removeItem(AVATAR_KEY);
  }
}

const ROLE_LABELS = { student: "同学", monitor: "学习委员", ta: "助教" };

function explain(error) {
  const code = String(error?.code || error?.status || "");
  const message = String(error?.message || error?.details || "");
  if (code === "23505" || /duplicate|unique/i.test(message)) return new Error("这个学号已经在班里了");
  if (code === "42501" || code === "401" || code === "403") return new Error(message && /[\u4e00-\u9fff]/.test(message) ? message : "没有权限做这件事");
  if (/comment/i.test(message) && (/42P01/.test(code) || /does not exist|schema cache|could not find/i.test(message))) {
    return new Error("评论还没写进云数据库");
  }
  if (code === "42P01" || /relation .* does not exist/i.test(message)) return new Error("云数据库还没建表，请先让管理侧执行 schema.sql");
  if (/jwt|not authenticated|session missing|invalid claim/i.test(message)) return new Error("请先登录");
  if (/quota|额度|402|payment/i.test(message)) return new Error("云服务额度不足");
  if (/failed to fetch|network|timeout/i.test(message)) return new Error("网络失败，请稍后再试");
  return new Error(message || "请求失败");
}

async function many(query) {
  const { data, error } = await query;
  if (error) throw explain(error);
  return data ?? [];
}

async function sessionUser() {
  const { data, error } = await cloud.auth.getSession();
  if (error) throw explain(error);
  if (!data?.user) throw new Error("请先登录");
  return data.user;
}

function toUser(row) {
  return {
    id: row.owner_id,
    studentNo: row.student_no,
    name: row.name,
    role: row.role,
    roleLabel: ROLE_LABELS[row.role] || row.role,
    avatar: row.avatar_path || "",
  };
}

async function profileOf(ownerId) {
  const rows = await many(cloud.database.from("profiles").select("*").eq("owner_id", ownerId));
  return rows[0] || null;
}

async function myProfile() {
  const user = await sessionUser();
  return profileOf(user.id);
}

async function requireProfile() {
  const row = await myProfile();
  if (!row) throw new Error("请先填写学号");
  return row;
}

function deadlineValue(value) {
  const text = String(value ?? "").trim();
  if (!text) return null;
  const date = new Date(text);
  if (Number.isNaN(date.getTime())) throw new Error("截止时间无法识别");
  return date.toISOString();
}

function requireText(value, label) {
  const text = String(value ?? "").trim();
  if (!text) throw new Error(`请填写${label}`);
  return text;
}

function viewerFrom(row) {
  return { id: row.owner_id, role: row.role };
}

async function eventsFor(submissionId, mode) {
  const events = await many(
    cloud.database.from("events").select("*").eq("submission_id", submissionId).order("id"),
  );
  const actors = [...new Set(events.map((event) => event.owner_id))];
  const profiles = actors.length
    ? await many(cloud.database.from("profiles").select("owner_id,name").in("owner_id", actors))
    : [];
  const names = new Map(profiles.map((row) => [row.owner_id, row.name]));
  return events.map((event) => ({
    id: event.id,
    status: event.status,
    statusLabel: statusLabel(event.status, mode),
    note: event.note,
    actorName: names.get(event.owner_id) || "",
    createdAt: event.created_at,
  }));
}

async function presentSubmission(row, viewerRow, mode) {
  const student = await profileOf(row.student_ref);
  const viewer = viewerFrom(viewerRow);
  const submission = { id: row.id, studentId: row.student_ref, status: row.status };
  return {
    id: row.id,
    assignmentId: row.assignment_id,
    student: student
      ? toUser(student)
      : { id: row.student_ref, studentNo: "", name: "", role: "student", roleLabel: "同学", avatar: "" },
    status: row.status,
    statusLabel: statusLabel(row.status, mode),
    updatedAt: row.updated_at,
    events: await eventsFor(row.id, mode),
    actions: {
      advance: canAdvance(viewer, submission, mode),
      revert: canRevert(viewer, submission, mode),
      collect: canCollect(viewer, submission, mode),
      advanceLabel: advanceLabel(viewer, submission, mode),
    },
  };
}

async function assignmentMode(id) {
  const rows = await many(cloud.database.from("assignments").select("mode").eq("id", id));
  return rows[0]?.mode || "collect";
}

function progressOf(rows, mode, mineStatus) {
  const bucket = {};
  for (const row of rows) bucket[row.status] = (bucket[row.status] || 0) + 1;
  const total = rows.length;
  const notSubmitted = bucket.not_submitted || 0;
  const collected = bucket.collected_by_monitor || 0;
  const ready = bucket.ready_to_submit || 0;
  return {
    total,
    handedIn: mode === "collect" ? ready + collected : total - notSubmitted,
    distributed: bucket.distributed || 0,
    collected,
    pending: (bucket.submitted_to_monitor || 0) + (bucket.received_by_ta || 0) + (bucket.graded || 0),
    mineStatus,
    mineStatusLabel: mineStatus ? statusLabel(mineStatus, mode) : null,
  };
}

async function listAssignments() {
  const me = await requireProfile();
  const assignments = await many(cloud.database.from("assignments").select("*").order("id", { ascending: false }));
  const profiles = await many(cloud.database.from("profiles").select("owner_id,name"));
  const names = new Map(profiles.map((row) => [row.owner_id, row.name]));
  const subs = await many(cloud.database.from("submissions").select("assignment_id,status,student_ref"));
  return {
    assignments: assignments.map((row) => {
      const related = subs.filter((item) => item.assignment_id === row.id);
      const mine = related.find((item) => item.student_ref === me.owner_id);
      const mode = row.mode || "collect";
      return {
        id: row.id,
        title: row.title,
        description: row.description,
        week: row.week || "",
        deadline: row.deadline,
        mode,
        createdAt: row.created_at,
        publisherName: names.get(row.owner_id) || "",
        progress: progressOf(related, mode, mine?.status ?? null),
      };
    }),
  };
}

async function assignmentDetail(id) {
  await requireProfile();
  const rows = await many(cloud.database.from("assignments").select("*").eq("id", id));
  const row = rows[0];
  if (!row) throw new Error("没有这次作业");
  const publisher = await profileOf(row.owner_id);
  const images = await many(
    cloud.database.from("assignment_images").select("id,storage_path").eq("assignment_id", id).order("id"),
  );
  return {
    id: row.id,
    title: row.title,
    description: row.description,
    week: row.week || "",
    deadline: row.deadline,
    mode: row.mode || "collect",
    createdAt: row.created_at,
    publisherName: publisher?.name || "",
    images: images.map((image) => ({ id: image.id, url: image.storage_path })),
  };
}

async function payloadBlob(body) {
  const mime = String(body?.mime || "image/jpeg");
  if (!["image/jpeg", "image/png", "image/gif", "image/webp"].includes(mime)) {
    throw new Error("请上传 jpg、png、gif 或 webp 图片");
  }
  let raw = String(body?.data || "");
  if (raw.startsWith("data:")) raw = raw.slice(raw.indexOf(",") + 1);
  const binary = atob(raw);
  if (binary.length > 4 * 1024 * 1024) throw new Error("图片太大");
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
  return new Blob([bytes], { type: mime });
}

async function uploadImage(body) {
  const user = await sessionUser();
  const blob = await payloadBlob(body);
  const storagePath = cloud.storage.sharedPath(user.id, `images/${crypto.randomUUID()}.jpg`);
  const { error } = await cloud.storage.upload(storagePath, blob, { contentType: blob.type || "image/jpeg" });
  if (error) throw explain(error);
  return storagePath;
}

async function presentComments(rows) {
  if (!rows.length) return [];
  const ids = rows.map((row) => row.id);
  const owners = [...new Set(rows.map((row) => row.owner_id).filter(Boolean))];
  const [profiles, images] = await Promise.all([
    many(cloud.database.from("profiles").select("owner_id,name,role,avatar_path").filter("owner_id", "in", `(${owners.join(",")})`)),
    many(cloud.database.from("comment_images").select("id,comment_id,storage_path").filter("comment_id", "in", `(${ids.join(",")})`).order("id")),
  ]);
  const people = new Map(profiles.map((row) => [row.owner_id, row]));
  return rows.map((row) => {
    const person = people.get(row.owner_id);
    return {
      id: row.id,
      body: row.body || "",
      createdAt: row.created_at,
      author: person
        ? { id: person.owner_id, name: person.name, role: person.role, avatar: person.avatar_path || "" }
        : { id: row.owner_id, name: "", role: "", avatar: "" },
      images: images
        .filter((image) => String(image.comment_id) === String(row.id))
        .map((image) => ({ id: image.id, url: image.storage_path })),
    };
  });
}

async function listComments(id) {
  await requireProfile();
  const existing = await many(cloud.database.from("assignments").select("id").eq("id", id));
  if (!existing.length) throw new Error("没有这次作业");
  const rows = await many(
    cloud.database.from("comments").select("*").eq("assignment_id", id).order("created_at").order("id"),
  );
  return { comments: await presentComments(rows) };
}

async function createComment(id, body) {
  await requireProfile();
  const existing = await many(cloud.database.from("assignments").select("id").eq("id", id));
  if (!existing.length) throw new Error("没有这次作业");
  const text = String(body?.body ?? "").trim().slice(0, 500);
  const inserted = await many(
    cloud.database.from("comments").insert({ assignment_id: Number(id), body: text }).select(),
  );
  if (!inserted.length) throw new Error("没有权限发表评论");
  const presented = await presentComments(inserted);
  return { comment: presented[0] };
}

async function uploadCommentImage(id, body) {
  const me = await requireProfile();
  const comments = await many(cloud.database.from("comments").select("id,owner_id").eq("id", id));
  const comment = comments[0];
  if (!comment) throw new Error("没有这条评论");
  if (comment.owner_id !== me.owner_id) throw new Error("只能给自己的评论加图片");
  const count = await many(cloud.database.from("comment_images").select("id").eq("comment_id", id));
  if (count.length >= 4) throw new Error("一条评论最多 4 张图片");
  const storagePath = await uploadImage(body);
  const inserted = await many(
    cloud.database.from("comment_images").insert({ comment_id: Number(id), storage_path: storagePath }).select(),
  );
  if (!inserted.length) {
    await cloud.storage.remove([storagePath]);
    throw new Error("没有权限上传图片");
  }
  return { id: inserted[0].id, url: storagePath };
}

async function deleteComment(id) {
  const me = await requireProfile();
  const comments = await many(cloud.database.from("comments").select("id,owner_id").eq("id", id));
  const comment = comments[0];
  if (!comment) throw new Error("没有这条评论");
  if (comment.owner_id !== me.owner_id && me.role !== "monitor") throw new Error("只能删除自己的评论");
  const images = await many(cloud.database.from("comment_images").select("storage_path").eq("comment_id", id));
  const removed = await many(cloud.database.from("comments").delete().eq("id", id).select());
  if (!removed.length) throw new Error("没有权限删除评论");
  const paths = images.map((image) => image.storage_path).filter(Boolean);
  if (paths.length) await cloud.storage.remove(paths);
  return { ok: true };
}

async function moveSubmission(id, direction, body) {
  const me = await requireProfile();
  const rows = await many(cloud.database.from("submissions").select("*").eq("id", id));
  const row = rows[0];
  if (!row) throw new Error("没有这条进度");
  const mode = await assignmentMode(row.assignment_id);
  const submission = { id: row.id, studentId: row.student_ref, status: row.status };
  const viewer = viewerFrom(me);
  let status = null;
  if (direction === "collect") {
    if (!canCollect(viewer, submission, mode)) throw new Error("现在不能揽收");
    status = "collected_by_monitor";
  } else if (direction === "advance") {
    if (!canAdvance(viewer, submission, mode)) throw new Error("现在不能改这一步");
    status = nextStatus(row.status, mode);
  } else {
    if (!canRevert(viewer, submission, mode)) throw new Error("现在不能改这一步");
    if (mode === "collect" && row.status === "collected_by_monitor") {
      const events = await eventsFor(row.id, mode);
      status = events.some((event) => event.status === "ready_to_submit") ? "ready_to_submit" : "not_submitted";
    } else {
      status = previousStatus(row.status, mode);
    }
  }
  if (!status) throw new Error("现在不能改这一步");
  const note = String(body?.note ?? "").trim().slice(0, 200);
  const updated = await many(
    cloud.database.from("submissions").update({ status, updated_at: new Date().toISOString() }).eq("id", id).select(),
  );
  if (!updated.length) throw new Error("没有权限改这一步");
  const events = await many(
    cloud.database.from("events").insert({ submission_id: Number(id), status, note }).select(),
  );
  if (!events.length) throw new Error("没有权限记录进度");
  return { submission: await presentSubmission(updated[0], me, mode) };
}

async function dispatch(path, method, body) {
  if (method === "GET" && path === "/health") return { ok: true, service: "homework-logistics" };
  if (method === "GET" && path === "/bootstrap") {
    const { data, error } = await cloud.auth.getSession();
    if (error) throw explain(error);
    if (!data?.user) return { needsSetup: false, hasClass: false, user: null };
    const profile = await profileOf(data.user.id);
    const klass = profile?.class_id
      ? (await many(cloud.database.from("class_info").select("id,name,code").eq("id", profile.class_id).limit(1)))[0]
      : null;
    return {
      needsSetup: !profile,
      hasClass: !!klass,
      className: klass?.name || "",
      classCode: klass?.code || "",
      user: profile ? toUser(profile) : null,
    };
  }
  if (method === "POST" && path === "/setup") {
    const user = await sessionUser();
    if (await profileOf(user.id)) throw new Error("学号已经绑定过了");
    const name = requireText(body?.name, "姓名");
    const studentNo = requireText(body?.studentNo, "学号");
    const className = requireText(body?.className, "班级");
    const course = String(body?.course || "").trim();

    // 按班名找班，找不到就新建一个（创建者当学委）
    let klass = (
      await many(cloud.database.from("class_info").select("id,name").eq("name", className).limit(1))
    )[0];
    if (!klass) {
      const created = await many(
        cloud.database.from("class_info").insert({ name: className, course }).select(),
      );
      if (!created.length) throw new Error("没有权限建立班级");
      klass = created[0];
    }

    // 本班还没有别人 → 我是学委；否则是同学
    const peers = await many(
      cloud.database
        .from("profiles")
        .select("owner_id")
        .eq("class_id", klass.id)
        .limit(1),
    );
    const role = peers.length === 0 ? "monitor" : "student";

    const rows = await many(
      cloud.database
        .from("profiles")
        .insert({ class_id: klass.id, student_no: studentNo, name, role })
        .select(),
    );
    if (!rows.length) throw new Error("没有权限绑定学号");
    return { token: "", user: toUser(rows[0]) };
  }
  if (method === "POST" && path === "/class/dissolve") {
    await requireProfile();
    const { error: rpcError } = await cloud.database.rpc("dissolve_class");
    if (rpcError) throw explain(rpcError);
    return { ok: true };
  }
  if (method === "GET" && path === "/me") {
    const row = await requireProfile();
    return { user: toUser(row) };
  }
  if (method === "GET" && path === "/members") {
    const me = await requireProfile();
    if (me.role !== "monitor") throw new Error("只有学习委员可以看成员");
    const rows = await many(cloud.database.from("profiles").select("*").neq("role", "ta").order("student_no"));
    rows.sort((a, b) => Number(b.role === "monitor") - Number(a.role === "monitor"));
    return { members: rows.map(toUser) };
  }
  if (method === "POST" && path === "/members") {
    throw new Error("同学需要使用自己的 WorkBuddy 账号登录并填写学号，不能代为注册");
  }
  if (method === "POST" && /\/members\/[^/]+\/password$/.test(path)) {
    throw new Error("登录已改为 WorkBuddy 账号，不能再设置密码");
  }
  const memberMatch = path.match(/^\/members\/([^/]+)$/);
  if (memberMatch && method === "PATCH") {
    const me = await requireProfile();
    const targetId = decodeURIComponent(memberMatch[1]);
    const target = await profileOf(targetId);
    if (!target) throw new Error("没有这个成员");
    const self = target.owner_id === me.owner_id;
    if (!self && me.role !== "monitor") throw new Error("只能修改自己的资料");
    if (!self && target.role === "monitor") throw new Error("不能修改其他学委");
    const name = requireText(body?.name, "姓名");
    const studentNo = requireText(body?.studentNo, "学号");
    const rows = await many(
      cloud.database.from("profiles").update({ name, student_no: studentNo }).eq("owner_id", targetId).select(),
    );
    if (!rows.length) throw new Error("没有权限修改资料");
    return { user: toUser(rows[0]) };
  }
  if (memberMatch && method === "DELETE") {
    const me = await requireProfile();
    if (me.role !== "monitor") throw new Error("只有学习委员可以删除成员");
    const targetId = decodeURIComponent(memberMatch[1]);
    if (targetId === me.owner_id) throw new Error("不能删除自己");
    const target = await profileOf(targetId);
    if (!target) throw new Error("没有这个成员");
    if (target.role === "monitor") throw new Error("不能删除学委");
    const removed = await many(cloud.database.from("profiles").delete().eq("owner_id", targetId).select());
    if (!removed.length) throw new Error("没有权限删除成员");
    const removedSubmissions = await cloud.database.from("submissions").delete().eq("student_ref", targetId);
    if (removedSubmissions.error) throw explain(removedSubmissions.error);
    if (target.avatar_path) await cloud.storage.remove([target.avatar_path]);
    return { ok: true };
  }
  const transferMatch = path.match(/^\/members\/([^/]+)\/transfer$/);
  if (transferMatch && method === "POST") {
    const me = await requireProfile();
    if (me.role !== "monitor") throw new Error("只有学习委员可以转让");
    if (String(body?.phrase ?? "") !== "我已确认转让") throw new Error("请输入“我已确认转让”");
    const targetId = decodeURIComponent(transferMatch[1]);
    if (targetId === me.owner_id) throw new Error("不能转让给自己");
    const target = await profileOf(targetId);
    if (!target) throw new Error("没有这个成员");
    const promoted = await many(
      cloud.database.from("profiles").update({ role: "monitor" }).eq("owner_id", targetId).select(),
    );
    if (!promoted.length) throw new Error("没有权限转让");
    const demoted = await many(
      cloud.database.from("profiles").update({ role: "student" }).eq("owner_id", me.owner_id).select(),
    );
    if (!demoted.length) throw new Error("没有权限转让");
    return { user: toUser(demoted[0]) };
  }
  if (method === "GET" && path === "/subjects") {
    await requireProfile();
    const rows = await many(cloud.database.from("subjects").select("id,name").order("name"));
    return { subjects: rows.map((row) => ({ id: row.id, name: row.name })) };
  }
  if (method === "POST" && path === "/subjects") {
    const me = await requireProfile();
    if (me.role !== "monitor") throw new Error("只有学习委员可以管理科目");
    const name = requireText(body?.name, "科目");
    const existing = await many(cloud.database.from("subjects").select("id").eq("name", name));
    if (existing.length) throw new Error("这个科目已经有了");
    const created = await many(cloud.database.from("subjects").insert({ name }).select());
    if (!created.length) throw new Error("没有权限添加科目");
    return { id: created[0].id, name: created[0].name };
  }
  const subjectMatch = path.match(/^\/subjects\/([^/]+)$/);
  if (subjectMatch && method === "DELETE") {
    const me = await requireProfile();
    if (me.role !== "monitor") throw new Error("只有学习委员可以管理科目");
    const removed = await many(cloud.database.from("subjects").delete().eq("id", decodeURIComponent(subjectMatch[1])).select());
    if (!removed.length) throw new Error("没有权限或没有这个科目");
    return { ok: true };
  }
  if (method === "GET" && path === "/assignments") return listAssignments();
  if (method === "POST" && path === "/assignments") {
    const me = await requireProfile();
    if (me.role !== "monitor") throw new Error("只有学习委员可以发布作业");
    const title = requireText(body?.title, "科目");
    const week = requireText(body?.week, "周次");
    const description = String(body?.description ?? "").trim();
    const deadline = deadlineValue(body?.deadline);
    const created = await many(
      cloud.database.from("assignments").insert({ title, description, week, deadline, mode: "collect" }).select(),
    );
    if (!created.length) throw new Error("没有权限发布作业");
    const people = await many(cloud.database.from("profiles").select("owner_id,role"));
    for (const person of people) {
      if (person.role !== "student" && person.role !== "monitor") continue;
      const submission = await many(
        cloud.database
          .from("submissions")
          .insert({ assignment_id: created[0].id, student_ref: person.owner_id, status: "not_submitted" })
          .select(),
      );
      if (!submission.length) throw new Error("没有权限建立进度");
      const event = await many(
        cloud.database.from("events").insert({ submission_id: submission[0].id, status: "not_submitted", note: "" }).select(),
      );
      if (!event.length) throw new Error("没有权限记录进度");
    }
    const message = await many(
      cloud.database
        .from("messages")
        .insert({ kind: "assignment", title, body: `${me.name}发布了${weekLabel(week)}的作业`, assignment_id: created[0].id })
        .select(),
    );
    if (!message.length) throw new Error("没有权限发布消息");
    return { id: created[0].id, messageId: message[0].id };
  }
  if (method === "GET" && path === "/messages") {
    await requireProfile();
    const rows = await many(cloud.database.from("messages").select("*").order("id", { ascending: false }).limit(50));
    return {
      messages: rows.map((row) => ({
        id: row.id,
        kind: row.kind,
        title: row.title,
        body: row.body,
        assignmentId: row.assignment_id,
        createdAt: row.created_at,
      })),
    };
  }
  const assignmentMatch = path.match(/^\/assignments\/([^/]+)$/);
  if (assignmentMatch && method === "GET") return assignmentDetail(decodeURIComponent(assignmentMatch[1]));
  if (assignmentMatch && method === "PATCH") {
    const me = await requireProfile();
    if (me.role !== "monitor") throw new Error("只有学习委员可以修改作业");
    const id = decodeURIComponent(assignmentMatch[1]);
    const title = requireText(body?.title, "科目");
    const week = requireText(body?.week, "周次");
    const description = String(body?.description ?? "").trim();
    const deadline = deadlineValue(body?.deadline);
    const rows = await many(
      cloud.database.from("assignments").update({ title, description, week, deadline }).eq("id", id).select(),
    );
    if (!rows.length) throw new Error("没有权限或没有这次作业");
    return { ok: true };
  }
  if (assignmentMatch && method === "DELETE") {
    const me = await requireProfile();
    if (me.role !== "monitor") throw new Error("只有学习委员可以删除作业");
    const id = decodeURIComponent(assignmentMatch[1]);
    const images = await many(cloud.database.from("assignment_images").select("storage_path").eq("assignment_id", id));
    let commentPaths = [];
    try {
      const comments = await many(cloud.database.from("comments").select("id").eq("assignment_id", id));
      if (comments.length) {
        const shots = await many(
          cloud.database.from("comment_images").select("storage_path").filter("comment_id", "in", `(${comments.map((row) => row.id).join(",")})`),
        );
        commentPaths = shots.map((image) => image.storage_path).filter(Boolean);
      }
    } catch {
      commentPaths = [];
    }
    const removedMessages = await cloud.database.from("messages").delete().eq("assignment_id", id);
    if (removedMessages.error) throw explain(removedMessages.error);
    const removed = await many(cloud.database.from("assignments").delete().eq("id", id).select());
    if (!removed.length) throw new Error("没有权限或没有这次作业");
    const paths = [...images.map((image) => image.storage_path).filter(Boolean), ...commentPaths];
    if (paths.length) await cloud.storage.remove(paths);
    return { ok: true };
  }
  const classStep = path.match(/^\/assignments\/([^/]+)\/(retrieve|distribute)$/);
  if (classStep && method === "POST") {
    const me = await requireProfile();
    if (me.role !== "monitor") throw new Error("只有学习委员可以操作");
    const assignmentId = decodeURIComponent(classStep[1]);
    const from = classStep[2] === "retrieve" ? "collected_by_monitor" : "retrieved_by_monitor";
    const to = classStep[2] === "retrieve" ? "retrieved_by_monitor" : "distributed";
    const existing = await many(cloud.database.from("assignments").select("id").eq("id", assignmentId));
    if (!existing.length) throw new Error("没有这次作业");
    const targets = await many(
      cloud.database.from("submissions").select("id").eq("assignment_id", assignmentId).eq("status", from),
    );
    if (!targets.length) throw new Error(from === "collected_by_monitor" ? "还没有已揽收的同学" : "还没有可以分发的进度");
    const now = new Date().toISOString();
    let count = 0;
    for (const target of targets) {
      const updated = await many(
        cloud.database.from("submissions").update({ status: to, updated_at: now }).eq("id", target.id).select(),
      );
      if (!updated.length) continue;
      const events = await many(
        cloud.database.from("events").insert({ submission_id: Number(target.id), status: to, note: "" }).select(),
      );
      if (!events.length) throw new Error("没有权限记录进度");
      count += 1;
    }
    if (!count) throw new Error("没有权限改这一步");
    return { ok: true, count };
  }
  const imageMatch = path.match(/^\/assignments\/([^/]+)\/images$/);
  if (imageMatch && method === "POST") {
    const me = await requireProfile();
    if (me.role !== "monitor") throw new Error("只有学习委员可以上传图片");
    const id = decodeURIComponent(imageMatch[1]);
    const existing = await many(cloud.database.from("assignments").select("id").eq("id", id));
    if (!existing.length) throw new Error("没有这次作业");
    const count = await many(cloud.database.from("assignment_images").select("id").eq("assignment_id", id));
    if (count.length >= 6) throw new Error("一次作业最多 6 张图片");
    const storagePath = await uploadImage(body);
    const inserted = await many(
      cloud.database.from("assignment_images").insert({ assignment_id: Number(id), storage_path: storagePath }).select(),
    );
    if (!inserted.length) throw new Error("没有权限上传图片");
    const images = await many(
      cloud.database.from("assignment_images").select("id,storage_path").eq("assignment_id", id).order("id"),
    );
    return { images: images.map((image) => ({ id: image.id, url: image.storage_path })) };
  }
  const mineMatch = path.match(/^\/assignments\/([^/]+)\/mine$/);
  if (mineMatch && method === "GET") {
    const me = await requireProfile();
    const id = decodeURIComponent(mineMatch[1]);
    const rows = await many(
      cloud.database.from("submissions").select("*").eq("assignment_id", id).eq("student_ref", me.owner_id),
    );
    if (!rows.length) throw new Error("这次作业没有你的进度");
    const mode = await assignmentMode(id);
    return { submission: await presentSubmission(rows[0], me, mode) };
  }
  const boardMatch = path.match(/^\/assignments\/([^/]+)\/submissions$/);
  if (boardMatch && method === "GET") {
    const me = await requireProfile();
    if (me.role === "student") throw new Error("只能看自己的进度");
    const id = decodeURIComponent(boardMatch[1]);
    const mode = await assignmentMode(id);
    let rows = await many(cloud.database.from("submissions").select("*").eq("assignment_id", id));
    if (me.role === "ta") rows = rows.filter((row) => row.status !== "not_submitted");
    const presented = [];
    for (const row of rows) presented.push(await presentSubmission(row, me, mode));
    presented.sort((a, b) => String(a.student.studentNo).localeCompare(String(b.student.studentNo)));
    return { submissions: presented };
  }
  if (method === "POST" && path === "/me/avatar") {
    const me = await requireProfile();
    const storagePath = await uploadImage(body);
    if (me.avatar_path) await cloud.storage.remove([me.avatar_path]);
    const rows = await many(
      cloud.database.from("profiles").update({ avatar_path: storagePath }).eq("owner_id", me.owner_id).select(),
    );
    if (!rows.length) throw new Error("没有权限修改头像");
    return { user: toUser(rows[0]) };
  }
  const moveMatch = path.match(/^\/submissions\/([^/]+)\/(advance|revert|collect)$/);
  if (moveMatch && method === "POST") return moveSubmission(decodeURIComponent(moveMatch[1]), moveMatch[2], body);
  const commentList = path.match(/^\/assignments\/([^/]+)\/comments$/);
  if (commentList && method === "GET") return listComments(decodeURIComponent(commentList[1]));
  if (commentList && method === "POST") return createComment(decodeURIComponent(commentList[1]), body);
  const commentImage = path.match(/^\/comments\/([^/]+)\/images$/);
  if (commentImage && method === "POST") return uploadCommentImage(decodeURIComponent(commentImage[1]), body);
  const commentItem = path.match(/^\/comments\/([^/]+)$/);
  if (commentItem && method === "DELETE") return deleteComment(decodeURIComponent(commentItem[1]));
  if (method === "GET" && path.startsWith("/files/")) throw new Error("没有这张图片");
  throw new Error("没有这个功能");
}

export async function resolveImage(storagePath) {
  if (!storagePath) return "";
  if (storagePath.startsWith("data:") || storagePath.startsWith("http")) return storagePath;
  const saved = readAvatar(storagePath);
  if (saved) return saved;
  const { data, error } = await cloud.storage.createSignedUrl(storagePath, 600);
  if (error || !data?.signedUrl) return "";
  rememberAvatar(storagePath, data.signedUrl, 500);
  return data.signedUrl;
}

export async function api(_base, path, { method = "GET", body } = {}) {
  const data = await dispatch(path, method, body);
  if (method === "GET") remember(path, data);
  return data;
}

export async function imagePayload(file, maxEdge = 1600) {
  const bitmap = await createImageBitmap(file);
  const scale = Math.min(1, maxEdge / Math.max(bitmap.width, bitmap.height));
  const canvas = document.createElement("canvas");
  canvas.width = Math.max(1, Math.round(bitmap.width * scale));
  canvas.height = Math.max(1, Math.round(bitmap.height * scale));
  canvas.getContext("2d").drawImage(bitmap, 0, 0, canvas.width, canvas.height);
  const dataUrl = canvas.toDataURL("image/jpeg", 0.8);
  return { mime: "image/jpeg", data: dataUrl.slice(dataUrl.indexOf(",") + 1) };
}

export function weekLabel(week) {
  const text = String(week || "").trim();
  if (!text) return "";
  return /^\d+$/.test(text) ? `第${text}周` : text;
}

export function formatTime(value) {
  if (!value) return "无";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value).replace("T", " ");
  return date.toLocaleString("zh-CN", { hour12: false });
}
