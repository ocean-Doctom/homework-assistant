import { useEffect, useRef, useState } from "react";
import { api, clearCache, formatTime, imagePayload, readAvatar, readCache, resolveImage, weekLabel } from "./api.js";
import { cloud, hasCloudSession, startWorkBuddyLogin, startupError } from "./cloud.js";

const USER_KEY = "homework-logistics-user";

function readUser() {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY) || "null");
  } catch {
    return null;
  }
}

function rememberUser(next) {
  if (next) localStorage.setItem(USER_KEY, JSON.stringify(next));
  else localStorage.removeItem(USER_KEY);
}

const FULL_STEPS = [
  ["not_submitted", "未提交", "作业已发布"],
  ["submitted_to_monitor", "已提交到学习委员", "同学交给学委，或学委代登记"],
  ["received_by_ta", "助教已接收", "由助教确认接收"],
  ["graded", "已批改", "由助教批改"],
  ["returned_to_monitor", "已移交学习委员", "由助教移交学委"],
  ["distributed", "已分发", "由学委发回同学"],
];

const COLLECT_STEPS = [
  ["not_submitted", "未写完", "作业已发布"],
  ["ready_to_submit", "已写完，随时可以提交", "同学标记写完之后，学委可以收"],
  ["collected_by_monitor", "学委已揽收", "学委勾选清单后记录"],
  ["retrieved_by_monitor", "学委已取回", "学委取回后记录"],
  ["distributed", "学委已分发", "学委分发后记录"],
];

const SEEN_KEY = "homework-logistics-seen-message";

function stepsFor(mode, status) {
  if (mode === "collect" || ["ready_to_submit", "collected_by_monitor", "retrieved_by_monitor", "distributed"].includes(status)) return COLLECT_STEPS;
  return FULL_STEPS;
}

function noteSeen(id) {
  const seen = Number(localStorage.getItem(SEEN_KEY) || 0);
  if (id > seen) localStorage.setItem(SEEN_KEY, String(id));
}

export function App() {
  const base = "";
  const token = "";
  const [user, setUser] = useState(readUser);
  const [needsSetup, setNeedsSetup] = useState(false);
  const [hasClass, setHasClass] = useState(false);
  const [ready, setReady] = useState(() => Boolean(readUser()) || !hasCloudSession());
  const [error, setError] = useState("");
  const [view, setView] = useState("list");
  const [assignmentId, setAssignmentId] = useState(null);
  const [editingSelf, setEditingSelf] = useState(false);
  const [inbox, setInbox] = useState(() => readCache("", "/messages")?.messages ?? []);
  const [seenId, setSeenId] = useState(() => Number(localStorage.getItem(SEEN_KEY) || 0));
  const [notice, setNotice] = useState(null);

  useEffect(() => {
    let cancelled = false;
    async function boot() {
      setError(startupError);
      try {
        const bootstrap = await api(base, "/bootstrap");
        if (cancelled) return;
        setNeedsSetup(Boolean(bootstrap.needsSetup));
        setHasClass(Boolean(bootstrap.hasClass));
        if (bootstrap.user) {
          rememberUser(bootstrap.user);
          setUser(bootstrap.user);
          setNeedsSetup(false);
        } else if (!startupError) {
          rememberUser(null);
          setUser(null);
        }
      } catch (err) {
        if (cancelled) return;
        const message = startupError || err.message;
        if (!readUser() || message === "请先登录") {
          rememberUser(null);
          setUser(null);
          if (message !== "请先登录") setError(message);
        }
      } finally {
        if (!cancelled) setReady(true);
      }
    }
    boot();
    return () => {
      cancelled = true;
    };
  }, [base]);

  function useSession(_nextToken, nextUser) {
    rememberUser(nextUser);
    setUser(nextUser);
    setNeedsSetup(false);
    setView("list");
  }

  async function logout() {
    await cloud.auth.signOut();
    clearCache();
    rememberUser(null);
    setUser(null);
    setNeedsSetup(false);
    setView("list");
  }

  async function login() {
    setError("");
    try {
      await startWorkBuddyLogin();
    } catch (err) {
      setError(err.message);
    }
  }

  useEffect(() => {
    if (!user) return undefined;
    let cancelled = false;
    async function tick() {
      try {
        const data = await api(base, "/messages", { token });
        if (cancelled) return;
        const messages = data.messages || [];
        setInbox(messages);
        const max = messages.reduce((highest, item) => Math.max(highest, item.id), 0);
        if (localStorage.getItem(SEEN_KEY) == null) {
          localStorage.setItem(SEEN_KEY, String(max));
          setSeenId(max);
          return;
        }
        const seen = Number(localStorage.getItem(SEEN_KEY) || 0);
        setSeenId(seen);
        const fresh = messages.filter((item) => item.id > seen);
        if (fresh.length && view !== "messages") setNotice(fresh);
        if (view === "messages" && max > seen) {
          noteSeen(max);
          setSeenId(max);
        }
      } catch {
        /* 下一轮再试 */
      }
    }
    tick();
    const timer = setInterval(tick, 8000);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [user, token, base, view]);

  if (!user) {
    return (
      <LoginGate
        ready={ready}
        error={error}
        needsSetup={needsSetup}
        hasClass={hasClass}
        base={base}
        onDone={useSession}
      />
    );
  }

  // 网页端只保留登录：安卓 App 的 WebView 靠这一页做认证。
  // 业务功能（作业/消息/评论）已停用，等安卓端改成原生登录后彻底下线。
  return (
    <div className="gate">
      <div className="gate-mark">
        <img src="./mark.png" alt="" />
      </div>
      <h1>作业助手</h1>
      <p className="hint">网页端已停用，请在安卓 App 中使用。</p>
      <button
        type="button"
        className="gate-login"
        onClick={() => {
          rememberUser(null);
          window.location.reload();
        }}
      >
        退出登录
      </button>
    </div>
  );

  const section = view === "messages"
    ? "messages"
    : ["me", "manage", "roster", "publish", "transfer", "members"].includes(view) ? "me" : "home";

  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand">
          <strong>作业助手</strong>
        </div>
        <nav>
          <button type="button" className={section === "home" ? "active" : ""} onClick={() => { setView("list"); setAssignmentId(null); }}>
            主页
          </button>
          <button type="button" className={section === "messages" ? "active" : ""} onClick={() => {
            const max = inbox.reduce((highest, item) => Math.max(highest, item.id), seenId);
            noteSeen(max);
            setSeenId(max);
            setNotice(null);
            setView("messages");
          }}>
            消息{inbox.some((item) => item.id > seenId) && <i className="nav-dot" />}
          </button>
          <button type="button" className={section === "me" ? "active" : ""} onClick={() => setView("me")}>
            个人
          </button>
        </nav>
        <div className="side-user">
          <AvatarButton base={base} token={token} user={user} onUser={setUser} />
          <button type="button" className="name-button" onClick={() => setEditingSelf(true)}>
            <strong>{user.name}{user.role === "monitor" && <em className="role-tag">学委</em>}</strong>
            <span>{user.studentNo}</span>
          </button>
        </div>
      </aside>
      <main className="main">
        {error && <p className="banner">{error}</p>}
        {editingSelf && (
          <EditMember
            base={base}
            token={token}
            member={user}
            title="修改资料"
            onClose={() => setEditingSelf(false)}
            onSaved={(next) => { setUser(next); setEditingSelf(false); }}
          />
        )}
        {notice && (
          <div className="modal-back">
            <div className="card modal">
              <h2>新消息</h2>
              {notice.map((item) => <p key={item.id}>{item.body}：{item.title}</p>)}
              <button
                type="button"
                onClick={() => {
                  const max = notice.reduce((highest, item) => Math.max(highest, item.id), 0);
                  noteSeen(max);
                  setSeenId((current) => Math.max(current, max));
                  setNotice(null);
                }}
              >
                知道了
              </button>
            </div>
          </div>
        )}
        {view === "messages" && (
          <MessageList
            messages={inbox}
            onOpen={(id) => { setAssignmentId(id); setView("detail"); }}
          />
        )}
        {view === "list" && (
          <AssignmentList
            base={base}
            token={token}
            user={user}
            onOpen={(id) => { setAssignmentId(id); setView("detail"); }}
          />
        )}
        {view === "transfer" && (
          <TransferList
            base={base}
            token={token}
            onBack={() => setView("me")}
            onDone={(next) => { setUser(next); setView("me"); }}
          />
        )}
        {view === "members" && (
          <div className="list">
            <button type="button" className="ghost" onClick={() => setView("me")}>返回</button>
            <Members base={base} token={token} />
          </div>
        )}
        {view === "subjects" && (
          <Subjects base={base} token={token} onBack={() => setView("me")} />
        )}
        {view === "publish" && (
          <PublishForm base={base} token={token} onDone={() => setView("manage")} />
        )}
        {view === "manage" && (
          <ManageList base={base} token={token} onOpen={(id) => { setAssignmentId(id); setView("roster"); }} onPublish={() => setView("publish")} />
        )}
        {view === "roster" && (
          <ManageRoster base={base} token={token} id={assignmentId} onBack={() => setView("manage")} />
        )}
        {view === "detail" && (
          <AssignmentDetail base={base} token={token} user={user} id={assignmentId} />
        )}
        {view === "me" && (
          <Profile
            base={base}
            token={token}
            user={user}
            onUser={setUser}
            onLogout={logout}
            onEdit={() => setEditingSelf(true)}
            onManage={user.role === "monitor" ? () => setView("manage") : null}
            onSubjects={user.role === "monitor" ? () => setView("subjects") : null}
            onTransfer={user.role === "monitor" ? () => setView("transfer") : null}
            onMembers={user.role === "monitor" ? () => setView("members") : null}
          />
        )}
      </main>
    </div>
  );
}

function AvatarButton({ base, token, user, onUser, large = false }) {
  const inputRef = useRef(null);
  const [error, setError] = useState("");

  async function onFile(event) {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!file) return;
    setError("");
    try {
      const result = await api(base, "/me/avatar", { method: "POST", token, body: await imagePayload(file, 512) });
      onUser(result.user);
    } catch (err) {
      setError(err.message);
    }
  }

  return (
    <>
      <button type="button" className="avatar-button" onClick={() => inputRef.current?.click()}>
        <Photo base={base} token={token} src={user.avatar} alt={user.name} className={large ? "avatar" : "avatar small"} />
      </button>
      <input ref={inputRef} type="file" accept="image/*" hidden onChange={onFile} />
      {error && <p className="result error">{error}</p>}
    </>
  );
}

function chooseImage() {
  return new Promise((resolve) => {
    const input = document.createElement("input");
    input.type = "file";
    input.accept = "image/*";
    let settled = false;
    const finish = (file) => {
      if (settled) return;
      settled = true;
      resolve(file);
    };
    input.addEventListener("change", () => finish(input.files?.[0] ?? null));
    window.addEventListener("focus", () => setTimeout(() => finish(input.files?.[0] ?? null), 400), { once: true });
    input.click();
  });
}

function MenuGlyph({ kind }) {
  const paths = {
    work: (
      <>
        <rect x="5" y="4" width="14" height="16" rx="2" />
        <path d="M9 3.5h6v3H9z" />
        <path d="M8 11h8M8 15h5" />
      </>
    ),
    transfer: (
      <>
        <path d="M4 8h12" />
        <path d="M13 5l4 3-4 3" />
        <path d="M20 16H8" />
        <path d="M11 13l-4 3 4 3" />
      </>
    ),
    people: (
      <>
        <circle cx="9" cy="8" r="2.2" />
        <path d="M4.5 17c.7-2.6 2.4-3.8 4.5-3.8s3.8 1.2 4.5 3.8" />
        <circle cx="16.2" cy="9" r="1.7" />
        <path d="M13.6 17c.4-1.8 1.5-2.7 2.7-2.7 1.2 0 2.1.8 2.6 2.4" />
      </>
    ),
    logout: (
      <>
        <path d="M10 5H6v14h4" />
        <path d="M11 12h8" />
        <path d="M16 8l4 4-4 4" />
      </>
    ),
  };
  return (
    <span className={`menu-glyph ${kind}`}>
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
        {paths[kind]}
      </svg>
    </span>
  );
}

function MenuRow({ kind, title, danger = false, onClick }) {
  return (
    <button type="button" className={danger ? "menu-row danger" : "menu-row"} onClick={onClick}>
      <MenuGlyph kind={kind} />
      <span className="title">{title}</span>
      <span className="chev">›</span>
    </button>
  );
}

function Profile({ base, token, user, onUser, onLogout, onEdit, onManage, onSubjects, onTransfer, onMembers }) {
  async function dissolve() {
    const ok = window.confirm(
      "解散班级会删除本班的作业、评论和消息，而且只有在班里只剩你一个人时才允许。确定解散吗？",
    );
    if (!ok) return;
    try {
      await api(base, "/class/dissolve", { method: "POST", token });
      rememberUser(null);
      window.location.reload();
    } catch (error) {
      window.alert(error.message || "解散失败");
    }
  }

  return (
    <div className="me-page">
      <section className="card profile">
        <div className="identity">
          <AvatarButton base={base} token={token} user={user} onUser={onUser} large />
          <div>
            <h2>
              <button type="button" className="name-button" onClick={onEdit}>
                {user.name}{user.role === "monitor" && <em className="role-tag">学委</em>}
              </button>
            </h2>
            <p className="hint">{user.studentNo}</p>
          </div>
        </div>
      </section>
      {(onManage || onSubjects || onTransfer || onMembers) && (
        <nav className="menu">
          {onManage && <MenuRow kind="work" title="作业管理" onClick={onManage} />}
          {onSubjects && <MenuRow kind="work" title="科目管理" onClick={onSubjects} />}
          {onTransfer && <MenuRow kind="transfer" title="转让学委" onClick={onTransfer} />}
          {onMembers && <MenuRow kind="people" title="成员管理" onClick={onMembers} />}
        </nav>
      )}
      <nav className="menu">
        {user.role === "monitor" && (
          <MenuRow kind="logout" title="解散班级" danger onClick={dissolve} />
        )}
        <MenuRow kind="logout" title="退出登录" danger onClick={onLogout} />
      </nav>
    </div>
  );
}

function Photo({ src, alt, className = "avatar" }) {
  const [url, setUrl] = useState(() => readAvatar(src));

  useEffect(() => {
    if (!src) {
      setUrl("");
      return undefined;
    }
    const saved = readAvatar(src);
    if (saved) {
      setUrl(saved);
      return undefined;
    }
    let cancelled = false;
    resolveImage(src).then((next) => {
      if (!cancelled) setUrl(next);
    }).catch(() => {
      if (!cancelled) setUrl("");
    });
    return () => {
      cancelled = true;
    };
  }, [src]);

  if (!url) return <span className={`${className} letter`}>{alt?.slice(0, 1) || "?"}</span>;
  return <img className={className} src={url} alt={alt || ""} />;
}

function ServerForm({ baseUrl, setBaseUrl }) {
  return (
    <label className="field">
      后端地址
      <input value={baseUrl} onChange={(event) => setBaseUrl(event.target.value)} spellCheck={false} />
    </label>
  );
}

function LoginGate({ ready, error, needsSetup, hasClass, base, onDone }) {
  const [phone, setPhone] = useState("");
  const [code, setCode] = useState("");
  const challengeRef = useRef(null);
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState("");

  async function sendCode() {
    const value = phone.trim();
    if (!/^1\d{10}$/.test(value)) {
      setNote("请输入 11 位手机号");
      return;
    }
    setBusy(true);
    setNote("");
    // signInWithOtp 返回的 challenge 自带 verify()，登录/注册由 SDK 判定，
    // 不再依赖 isExistingUser 自己猜（猜错会导致"已有账号"死循环）
    const { data, error: sendError } = await cloud.auth.signInWithOtp({ phone: value });
    setBusy(false);
    if (sendError) {
      setNote(sendError.message || "验证码发送失败");
      return;
    }
    challengeRef.current = data;
    setNote("验证码已发送，请查收短信");
  }

  async function submit(event) {
    event.preventDefault();
    if (!challengeRef.current) {
      setNote("请先获取验证码");
      return;
    }
    if (!code.trim()) {
      setNote("请输入验证码");
      return;
    }
    setBusy(true);
    setNote("");
    let result;
    try {
      result = await challengeRef.current.verify({ token: code.trim() });
    } catch (error) {
      setBusy(false);
      setNote(error.message || "登录失败");
      return;
    }
    const { data, error: verifyError } = result;
    setBusy(false);
    if (verifyError) {
      setNote(verifyError.message || "登录失败");
      return;
    }
    onDone(data);
  }

  return (
    <div className="gate">
      <div className="gate-mark">
        <img src="./mark.png" alt="" />
      </div>
      <h1>作业助手</h1>
      {error && <p className="banner">{error}</p>}
      {!ready && <p className="hint">正在连接…</p>}
      {ready && needsSetup && <SetupForm base={base} hasClass={hasClass} onDone={onDone} />}
      {ready && !needsSetup && (
        <form className="card gate-form" onSubmit={submit}>
          <label className="field">
            <span>手机号</span>
            <input
              value={phone}
              onChange={(event) => setPhone(event.target.value)}
              placeholder="11 位手机号"
              inputMode="numeric"
              autoComplete="tel"
            />
          </label>
          <label className="field">
            <span>短信验证码</span>
            <div className="code-row">
              <input
                value={code}
                onChange={(event) => setCode(event.target.value)}
                placeholder="6 位验证码"
                inputMode="numeric"
                autoComplete="one-time-code"
              />
              <button type="button" className="code-send" onClick={sendCode} disabled={busy}>
                获取验证码
              </button>
            </div>
          </label>
          {note && <p className="hint">{note}</p>}
          <button type="submit" className="gate-login" disabled={busy}>
            登录 / 注册
          </button>
          <button
            type="button"
            className="gate-alt"
            onClick={async () => {
              try {
                await startWorkBuddyLogin();
              } catch (event) {
                setNote(event.message || "登录失败");
              }
            }}
          >
            改用 WorkBuddy 账号
          </button>
        </form>
      )}
    </div>
  );
}

function SetupForm({ base, hasClass, onDone }) {
  const [form, setForm] = useState({
    className: "",
    course: "",
    name: "",
    studentNo: "",
  });
  const [error, setError] = useState("");

  function set(key) {
    return (event) => setForm((current) => ({ ...current, [key]: event.target.value }));
  }

  async function submit(event) {
    event.preventDefault();
    setError("");
    try {
      const result = await api(base, "/setup", { method: "POST", body: form });
      const file = await chooseImage();
      let nextUser = result.user;
      if (file) {
        const uploaded = await api(base, "/me/avatar", {
          method: "POST",
          body: await imagePayload(file, 512),
        });
        nextUser = uploaded.user;
      }
      onDone("", nextUser);
      window.alert("设置完成，你可以点击主页头像修改。");
    } catch (err) {
      setError(err.message);
    }
  }

  return (
    <form className="card" onSubmit={submit}>
      <h2>加入班级</h2>
      <Field label="班级" value={form.className} onChange={set("className")} />
      <p className="hint">
        班级名要和学委建班时写的一字不差（例如「能动2401」），填对了就会加入这个班；如果这个班还不存在，会新建一个班并让你当学委。
      </p>
      <Field label="姓名" value={form.name} onChange={set("name")} />
      <Field label="学号" value={form.studentNo} onChange={set("studentNo")} />
      {error && <p className="result error">{error}</p>}
      <button type="submit">{hasClass ? "进入" : "加入 / 建立班级"}</button>
    </form>
  );
}

function assignmentTitle(item) {
  const week = weekLabel(item.week);
  return week ? `${item.title}（${week}）` : item.title;
}

function AssignmentCard({ item, onOpen }) {
  const progress = item.progress ?? {};
  return (
    <button type="button" className="card link" onClick={() => onOpen(item.id)}>
      <span className="row">
        <strong>{assignmentTitle(item)}</strong>
        {progress.mineStatusLabel && <em className={`chip ${progress.mineStatus}`}>{progress.mineStatusLabel}</em>}
      </span>
      <span>截止 {formatTime(item.deadline)}　{item.publisherName || "学委"} 发布</span>
    </button>
  );
}

function AssignmentList({ base, token, user, onOpen }) {
  const [assignments, setAssignments] = useState(() => readCache(token, "/assignments")?.assignments ?? null);
  const [error, setError] = useState("");
  const [collectedOpen, setCollectedOpen] = useState(false);

  useEffect(() => {
    api(base, "/assignments", { token })
      .then((data) => setAssignments(data.assignments))
      .catch((err) => setError(err.message));
  }, [base, token]);

  if (!assignments) return <p className={error ? "result error" : "hint"}>{error || "正在读取作业…"}</p>;

  const active = assignments.filter((item) => item.progress?.mineStatus !== "collected_by_monitor");
  const collected = assignments.filter((item) => item.progress?.mineStatus === "collected_by_monitor");

  return (
    <div className="list">
      {active.length === 0 && <p className="hint">{collected.length ? "没有待揽收的作业" : "没有作业"}</p>}
      {active.map((item) => <AssignmentCard key={item.id} item={item} onOpen={onOpen} />)}
      {collected.length > 0 && (
        <>
          <button type="button" className="fold" onClick={() => setCollectedOpen((open) => !open)}>
            查看已揽收的作业
          </button>
          {collectedOpen && collected.map((item) => <AssignmentCard key={item.id} item={item} onOpen={onOpen} />)}
        </>
      )}
    </div>
  );
}

function PublishForm({ base, token, onDone }) {
  const [subjects, setSubjects] = useState([]);
  const [form, setForm] = useState({ title: "", week: "", description: "", deadline: defaultDeadline(), mode: "collect" });
  const [photos, setPhotos] = useState([]);
  const [error, setError] = useState("");

  useEffect(() => {
    api(base, "/subjects", { token }).then((data) => setSubjects(data.subjects || [])).catch((err) => setError(err.message));
  }, [base, token]);

  async function addPhotos(event) {
    const files = [...event.target.files];
    event.target.value = "";
    const room = 6 - photos.length;
    if (room <= 0) {
      setError("一次作业最多 6 张图片");
      return;
    }
    const next = [];
    for (const file of files.slice(0, room)) {
      next.push({ url: URL.createObjectURL(file), body: await imagePayload(file) });
    }
    setPhotos((current) => [...current, ...next].slice(0, 6));
  }

  async function submit(event) {
    event.preventDefault();
    setError("");
    try {
      const created = await api(base, "/assignments", { method: "POST", token, body: form });
      if (created.messageId) noteSeen(created.messageId);
      for (const photo of photos) {
        await api(base, `/assignments/${created.id}/images`, { method: "POST", token, body: photo.body });
      }
      onDone();
    } catch (err) {
      setError(err.message);
    }
  }

  return (
    <form className="card" onSubmit={submit}>
      <h2>发布作业</h2>
      <label className="field">
        科目
        <select value={form.title} onChange={(event) => setForm({ ...form, title: event.target.value })}>
          <option value="">请选择</option>
          {subjects.map((subject) => <option key={subject.id} value={subject.name}>{subject.name}</option>)}
        </select>
      </label>
      {subjects.length === 0 && <p className="hint">还没有科目。请先到个人页的科目管理里添加。</p>}
      <Field label="周次" value={form.week} onChange={(event) => setForm({ ...form, week: event.target.value })} />
      <label className="field">
        说明
        <textarea value={form.description} onChange={(event) => setForm({ ...form, description: event.target.value })} />
      </label>
      <DeadlineFields value={form.deadline} onChange={(deadline) => setForm({ ...form, deadline })} />
      <label className="field">
        图片
        <input type="file" accept="image/*" multiple onChange={addPhotos} />
      </label>
      {photos.length > 0 && (
        <div className="thumbs">
          {photos.map((photo) => <img key={photo.url} src={photo.url} alt="" />)}
        </div>
      )}
      {error && <p className="result error">{error}</p>}
      <button type="submit">发布</button>
    </form>
  );
}

function ManageList({ base, token, onOpen, onPublish }) {
  const [assignments, setAssignments] = useState(() => readCache(token, "/assignments")?.assignments ?? null);
  const [error, setError] = useState("");

  function load() {
    api(base, "/assignments", { token })
      .then((data) => setAssignments(data.assignments))
      .catch((err) => setError(err.message));
  }

  useEffect(() => {
    load();
  }, [base, token]);

  async function remove(item) {
    if (!window.confirm(`删除「${item.title}」后，大家的进度也会一起消失。`)) return;
    setError("");
    try {
      await api(base, `/assignments/${item.id}`, { method: "DELETE", token });
      load();
    } catch (err) {
      setError(err.message);
    }
  }

  if (error && !assignments) return <p className="result error">{error}</p>;
  if (!assignments) return <p className="hint">正在读取…</p>;

  return (
    <div className="list">
      <button type="button" onClick={onPublish}>发布作业</button>
      {error && <p className="result error">{error}</p>}
      {assignments.length === 0 && <p className="hint">没有作业</p>}
      {assignments.map((item) => (
        <div key={item.id} className="manage-row">
          <button type="button" className="card link" onClick={() => onOpen(item.id)}>
            <strong>{assignmentTitle(item)}</strong>
            <span>截止 {formatTime(item.deadline)}</span>
          </button>
          <button type="button" className="danger" onClick={() => remove(item)}>删除</button>
        </div>
      ))}
    </div>
  );
}

function ManageRoster({ base, token, id, onBack }) {
  const cachedDetail = readCache(token, `/assignments/${id}`);
  const cachedBoard = readCache(token, `/assignments/${id}/submissions`);
  const [assignment, setAssignment] = useState(cachedDetail);
  const [rows, setRows] = useState(cachedBoard?.submissions ?? []);
  const [marks, setMarks] = useState(() => {
    const next = {};
    (cachedBoard?.submissions ?? []).forEach((item) => {
      next[item.id] = item.status === "collected_by_monitor";
    });
    return next;
  });
  const [editing, setEditing] = useState(false);
  const [error, setError] = useState("");
  const [draft, setDraft] = useState(null);
  const [subjects, setSubjects] = useState([]);

  async function load() {
    const detail = await api(base, `/assignments/${id}`, { token });
    const board = await api(base, `/assignments/${id}/submissions`, { token });
    setAssignment(detail);
    setRows(board.submissions);
    const next = {};
    board.submissions.forEach((item) => {
      next[item.id] = item.status === "collected_by_monitor";
    });
    setMarks(next);
    setEditing(false);
  }

  useEffect(() => {
    load().catch((err) => setError(err.message));
    api(base, "/subjects", { token }).then((data) => setSubjects(data.subjects || [])).catch(() => {});
  }, [base, token, id]);

  async function confirm() {
    setError("");
    try {
      for (const row of rows) {
        const was = row.status === "collected_by_monitor";
        const now = Boolean(marks[row.id]);
        if (was === now) continue;
        await api(base, `/submissions/${row.id}/${now ? "collect" : "revert"}`, {
          method: "POST",
          token,
          body: { note: "" },
        });
      }
      await load();
    } catch (err) {
      setError(err.message);
    }
  }

  async function shift(step) {
    setError("");
    try {
      await api(base, `/assignments/${id}/${step}`, { method: "POST", token, body: {} });
      await load();
    } catch (err) {
      setError(err.message);
    }
  }

  if (!assignment) return <p className="hint">{error || "正在读取…"}</p>;

  return (
    <div>
      <button type="button" className="ghost" onClick={onBack}>返回</button>
      <section className="card">
        <h2>{assignmentTitle(assignment)}</h2>
        <button type="button" className="ghost" onClick={() => setDraft({
          title: assignment.title,
          week: assignment.week || "",
          description: assignment.description || "",
          deadline: deadlineInput(assignment.deadline) || defaultDeadline(),
        })}>
          修改作业
        </button>
        {draft && (
          <form onSubmit={async (event) => {
            event.preventDefault();
            setError("");
            try {
              await api(base, `/assignments/${id}`, { method: "PATCH", token, body: draft });
              setDraft(null);
              await load();
            } catch (err) {
              setError(err.message);
            }
          }}>
            <label className="field">
              科目
              <select value={draft.title} onChange={(event) => setDraft({ ...draft, title: event.target.value })}>
                {draft.title && !subjects.some((subject) => subject.name === draft.title) && (
                  <option value={draft.title}>{draft.title}</option>
                )}
                <option value="">请选择</option>
                {subjects.map((subject) => <option key={subject.id} value={subject.name}>{subject.name}</option>)}
              </select>
            </label>
            <Field label="周次" value={draft.week} onChange={(event) => setDraft({ ...draft, week: event.target.value })} />
            <label className="field">
              说明
              <textarea value={draft.description} onChange={(event) => setDraft({ ...draft, description: event.target.value })} />
            </label>
            <DeadlineFields value={draft.deadline} onChange={(deadline) => setDraft({ ...draft, deadline })} />
            <button type="submit">保存修改</button>
          </form>
        )}
        <ul className="todo">
          {rows.map((row) => {
            const on = Boolean(marks[row.id]);
            const label = on ? "学委已揽收" : (row.status === "collected_by_monitor" ? "未写完" : row.statusLabel);
            return (
              <li key={row.id}>
                {editing && (
                  <button
                    type="button"
                    className={on ? "dot done" : "dot"}
                    onMouseDown={(event) => event.preventDefault()}
                    onClick={() => setMarks((current) => ({ ...current, [row.id]: !current[row.id] }))}
                  />
                )}
                <strong>{row.student.name}{row.student.role === "monitor" && <em className="role-tag">学委</em>}</strong>
                <span>{row.student.studentNo}</span>
                <em className={`chip ${on ? "collected_by_monitor" : row.status}`}>{label}</em>
              </li>
            );
          })}
        </ul>
        {error && <p className="result error">{error}</p>}
        {!editing && rows.some((row) => row.status === "collected_by_monitor") && (
          <button type="button" onClick={() => shift("retrieve")}>全班取回</button>
        )}
        {!editing && rows.some((row) => row.status === "retrieved_by_monitor") && (
          <button type="button" onClick={() => shift("distribute")}>全班分发</button>
        )}
        {editing
          ? <button type="button" onClick={confirm}>确定</button>
          : <button type="button" onClick={() => setEditing(true)}>修改状态</button>}
      </section>
    </div>
  );
}

function EditMember({ base, token, member, title, onClose, onSaved }) {
  const [name, setName] = useState(member.name || "");
  const [studentNo, setStudentNo] = useState(member.studentNo || "");
  const [error, setError] = useState("");

  async function submit(event) {
    event.preventDefault();
    setError("");
    try {
      const result = await api(base, `/members/${member.id}`, {
        method: "PATCH",
        token,
        body: { name, studentNo },
      });
      onSaved(result.user);
    } catch (err) {
      setError(err.message);
    }
  }

  return (
    <div className="modal-back" onClick={onClose}>
      <form className="card modal" onClick={(event) => event.stopPropagation()} onSubmit={submit}>
        <h2>{title}</h2>
        <Field label="姓名" value={name} onChange={(event) => setName(event.target.value)} />
        <Field label="学号" value={studentNo} onChange={(event) => setStudentNo(event.target.value)} />
        {error && <p className="result error">{error}</p>}
        <div className="actions">
          <button type="button" className="ghost" onClick={onClose}>取消</button>
          <button type="submit">保存</button>
        </div>
      </form>
    </div>
  );
}

function TransferList({ base, token, onBack, onDone }) {
  const [members, setMembers] = useState(() => {
    const rows = readCache(token, "/members")?.members;
    return rows ? rows.filter((member) => member.role !== "monitor") : null;
  });
  const [target, setTarget] = useState(null);
  const [phrase, setPhrase] = useState("");
  const [error, setError] = useState("");

  useEffect(() => {
    api(base, "/members", { token })
      .then((data) => setMembers(data.members.filter((member) => member.role !== "monitor")))
      .catch((err) => setError(err.message));
  }, [base, token]);

  async function submit(event) {
    event.preventDefault();
    setError("");
    if (phrase !== "我已确认转让") {
      setError("请输入“我已确认转让”");
      return;
    }
    try {
      const result = await api(base, `/members/${target.id}/transfer`, {
        method: "POST",
        token,
        body: { phrase },
      });
      onDone(result.user);
    } catch (err) {
      setError(err.message);
    }
  }

  return (
    <div className="list">
      <button type="button" className="ghost" onClick={onBack}>返回</button>
      <h2>转让学委</h2>
      {error && !target && <p className="result error">{error}</p>}
      {!members && <p className="hint">正在读取…</p>}
      {members?.map((member) => (
        <button key={member.id} type="button" className="card link" onClick={() => { setError(""); setPhrase(""); setTarget(member); }}>
          <strong>{member.name}</strong>
          <span>{member.studentNo}</span>
        </button>
      ))}
      {target && (
        <div className="modal-back" onClick={() => setTarget(null)}>
          <form className="card modal" onClick={(event) => event.stopPropagation()} onSubmit={submit}>
            <h2>转让给 {target.name}</h2>
            <p>请在输入框中输入“我已确认转让”</p>
            <Field label="我已确认转让" value={phrase} onChange={(event) => setPhrase(event.target.value)} />
            {error && <p className="result error">{error}</p>}
            <div className="actions">
              <button type="button" className="ghost" onClick={() => setTarget(null)}>取消</button>
              <button type="submit">转让</button>
            </div>
          </form>
        </div>
      )}
    </div>
  );
}

function Subjects({ base, token, onBack }) {
  const [items, setItems] = useState([]);
  const [name, setName] = useState("");
  const [error, setError] = useState("");

  async function load() {
    const data = await api(base, "/subjects", { token });
    setItems(data.subjects || []);
  }

  useEffect(() => {
    load().catch((err) => setError(err.message));
  }, [base, token]);

  async function add(event) {
    event.preventDefault();
    setError("");
    try {
      await api(base, "/subjects", { method: "POST", token, body: { name } });
      setName("");
      await load();
    } catch (err) {
      setError(err.message);
    }
  }

  async function remove(item) {
    if (!window.confirm(`删除科目「${item.name}」？已经发出的作业还在。`)) return;
    setError("");
    try {
      await api(base, `/subjects/${item.id}`, { method: "DELETE", token });
      await load();
    } catch (err) {
      setError(err.message);
    }
  }

  return (
    <div className="list">
      <button type="button" className="ghost" onClick={onBack}>返回</button>
      <form className="card" onSubmit={add}>
        <h2>科目管理</h2>
        <Field label="科目" value={name} onChange={(event) => setName(event.target.value)} />
        {error && <p className="result error">{error}</p>}
        <button type="submit">添加</button>
      </form>
      {items.length === 0 && <p className="hint">还没有科目</p>}
      {items.map((item) => (
        <div key={item.id} className="manage-row">
          <div className="card"><strong>{item.name}</strong></div>
          <button type="button" className="danger" onClick={() => remove(item)}>删除</button>
        </div>
      ))}
    </div>
  );
}

function Members({ base, token }) {
  const [members, setMembers] = useState(() => readCache(token, "/members")?.members ?? []);
  const [editingMember, setEditingMember] = useState(null);
  const [error, setError] = useState("");

  async function reload() {
    const data = await api(base, "/members", { token });
    setMembers(data.members);
  }

  async function remove(member) {
    if (!window.confirm(`删除「${member.name}」后，这个人的作业进度也会一起消失。对方可以重新登录并填写学号加入。`)) return;
    setError("");
    try {
      await api(base, `/members/${member.id}`, { method: "DELETE", token });
      await reload();
    } catch (err) {
      setError(err.message);
    }
  }

  useEffect(() => {
    reload().catch((err) => setError(err.message));
  }, [base, token]);

  return (
    <div>
      {editingMember && (
        <EditMember
          base={base}
          token={token}
          member={editingMember}
          title="修改成员"
          onClose={() => setEditingMember(null)}
          onSaved={() => { setEditingMember(null); reload(); }}
        />
      )}
      <div className="card">
        <h2>成员</h2>
        <p className="hint">同学用自己的 WorkBuddy 账号登录后填写学号，就会出现在这里。</p>
        {error && <p className="result error">{error}</p>}
        {members.map((member) => (
          <div key={member.id} className="member">
            <p>{member.name}{member.role === "monitor" && <em className="role-tag">学委</em>} · {member.studentNo}</p>
            {member.role !== "monitor" && (
              <>
                <button type="button" className="ghost" onClick={() => { setError(""); setEditingMember(member); }}>
                  编辑
                </button>
                <button type="button" className="danger" onClick={() => remove(member)}>
                  删除
                </button>
              </>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}

function AssignmentDetail({ base, token, user, id }) {
  const [assignment, setAssignment] = useState(() => readCache(token, `/assignments/${id}`));
  const [mine, setMine] = useState(() => readCache(token, `/assignments/${id}/mine`)?.submission ?? null);
  const [error, setError] = useState("");

  async function reload() {
    const detail = await api(base, `/assignments/${id}`, { token });
    setAssignment(detail);
    if (user.role !== "ta") {
      try {
        const own = await api(base, `/assignments/${id}/mine`, { token });
        setMine(own.submission);
      } catch {
        setMine(null);
      }
    }
  }

  useEffect(() => {
    reload().catch((err) => setError(err.message));
  }, [base, token, id, user.role]);

  async function move(submissionId, direction, note) {
    const y = document.scrollingElement?.scrollTop ?? 0;
    setError("");
    try {
      const data = await api(base, `/submissions/${submissionId}/${direction}`, {
        method: "POST",
        token,
        body: { note },
      });
      const next = data.submission;
      setMine((current) => (current?.id === next.id ? next : current));
      requestAnimationFrame(() => document.scrollingElement?.scrollTo(0, y));
    } catch (err) {
      setError(err.message);
    }
  }

  if (!assignment) return <p className="hint">{error || "正在读取…"}</p>;

  return (
    <div>
      <section className="card">
        <h2>{assignmentTitle(assignment)}</h2>
        <p className="hint">截止 {formatTime(assignment.deadline)}　{assignment.publisherName || "学委"} 发布</p>
        {assignment.description && <p>{assignment.description}</p>}
        {assignment.images?.length > 0 && (
          <div className="photos">
            {assignment.images.map((image) => (
              <Photo key={image.id} base={base} token={token} src={image.url} alt="" className="shot" />
            ))}
          </div>
        )}
      </section>
      {error && <p className="banner">{error}</p>}
      {mine && (
        <section className="card">
          <h2>我的进度</h2>
          <Timeline submission={mine} onMove={move} mode={assignment.mode} />
        </section>
      )}
      <Comments base={base} token={token} user={user} assignmentId={id} />
    </div>
  );
}

function Comments({ base, token, user, assignmentId }) {
  const [comments, setComments] = useState([]);
  const [draft, setDraft] = useState("");
  const [photos, setPhotos] = useState([]);
  const [error, setError] = useState("");
  const [ready, setReady] = useState(false);
  const [blocked, setBlocked] = useState(false);
  const [busy, setBusy] = useState(false);

  async function reload() {
    const data = await api(base, `/assignments/${assignmentId}/comments`, { token });
    setComments(data.comments || []);
    setError("");
    setBlocked(false);
    setReady(true);
  }

  useEffect(() => {
    reload().catch((err) => {
      setError(err.message);
      setBlocked(true);
      setReady(true);
    });
  }, [base, token, assignmentId]);

  async function addPhotos(event) {
    const files = [...event.target.files];
    event.target.value = "";
    const room = 4 - photos.length;
    if (room <= 0) {
      setError("一条评论最多 4 张图片");
      return;
    }
    const next = [];
    for (const file of files.slice(0, room)) {
      next.push({ url: URL.createObjectURL(file), body: await imagePayload(file) });
    }
    setPhotos((current) => [...current, ...next].slice(0, 4));
  }

  async function send(event) {
    event.preventDefault();
    const text = draft.trim();
    if (!text && photos.length === 0) {
      setError("请填写评论或添加图片");
      return;
    }
    setBusy(true);
    setError("");
    try {
      const created = await api(base, `/assignments/${assignmentId}/comments`, {
        method: "POST",
        token,
        body: { body: text },
      });
      let failed = 0;
      for (const photo of photos) {
        try {
          await api(base, `/comments/${created.comment.id}/images`, { method: "POST", token, body: photo.body });
        } catch {
          failed += 1;
        }
      }
      if (!text && photos.length > 0 && failed === photos.length) {
        await api(base, `/comments/${created.comment.id}`, { method: "DELETE", token }).catch(() => {});
        throw new Error("图片没有传上去");
      }
      setDraft("");
      setPhotos([]);
      await reload();
      if (failed) setError(`评论已发出，有 ${failed} 张图片没传上`);
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  async function remove(id) {
    if (!window.confirm("删除这条评论？")) return;
    setError("");
    try {
      await api(base, `/comments/${id}`, { method: "DELETE", token });
      await reload();
    } catch (err) {
      setError(err.message);
    }
  }

  return (
    <section className="card">
      <h2>评论</h2>
      {error && <p className="banner">{error}</p>}
      {ready && !blocked && comments.length === 0 && <p className="hint">还没有评论</p>}
      {comments.map((comment) => {
        const author = comment.author || {};
        const canDelete = author.id === user?.id || user?.role === "monitor";
        return (
          <article key={comment.id} className="comment">
            <div className="comment-head">
              <Photo src={author.avatar} alt={author.name} className="avatar small" />
              <div className="who">
                <strong>{author.name || "同学"}{author.role === "monitor" ? " · 学委" : ""}</strong>
                <p>{formatTime(comment.createdAt)}</p>
              </div>
              {canDelete && (
                <button type="button" className="ghost" onClick={() => remove(comment.id)}>删除</button>
              )}
            </div>
            {comment.body && <p className="comment-body">{comment.body}</p>}
            {comment.images?.length > 0 && (
              <div className="photos">
                {comment.images.map((image) => (
                  <Photo key={image.id} base={base} token={token} src={image.url} alt="" className="shot" />
                ))}
              </div>
            )}
          </article>
        );
      })}
      {ready && !blocked && (
        <form className="comment-form" onSubmit={send}>
          <textarea
            value={draft}
            placeholder="写下评论，也可以只发图片"
            onChange={(event) => setDraft(event.target.value)}
          />
          <label className="field">
            图片
            <input type="file" accept="image/*" multiple onChange={addPhotos} />
          </label>
          {photos.length > 0 && (
            <div className="thumbs">
              {photos.map((photo) => (
                <button key={photo.url} type="button" className="thumb-button" onClick={() => setPhotos((current) => current.filter((item) => item.url !== photo.url))}>
                  <img src={photo.url} alt="" />
                </button>
              ))}
            </div>
          )}
          <button type="submit" disabled={busy}>{busy ? "发送中" : "发送"}</button>
        </form>
      )}
    </section>
  );
}

function ClassSummary({ submissions }) {
  const waiting = submissions.filter((item) => item.status === "not_submitted").length;
  const done = submissions.filter((item) => item.status === "distributed").length;
  const moving = submissions.length - waiting - done;
  return (
    <div className="summary">
      <span><b>{waiting}</b>未提交</span>
      <span><b>{moving}</b>流转中</span>
      <span><b>{done}</b>已分发</span>
    </div>
  );
}

function MessageList({ messages, onOpen }) {
  if (!messages.length) return <p className="hint">没有消息</p>;
  return (
    <div className="list">
      {messages.map((item) => (
        <button key={item.id} type="button" className="card link" onClick={() => item.assignmentId && onOpen(item.assignmentId)}>
          <span>{item.body}</span>
          <strong>{item.title}</strong>
          <span>{formatTime(item.createdAt)}</span>
        </button>
      ))}
    </div>
  );
}

function Timeline({ submission, onMove, mode = "full" }) {
  const steps = stepsFor(mode, submission.status);
  const current = Math.max(0, steps.findIndex(([status]) => status === submission.status));
  return (
    <div>
      <ol className="timeline">
        {steps.map(([status, label], index) => {
          const events = submission.events.filter((event) => event.status === status);
          const event = index <= current ? events[events.length - 1] : null;
          const state = index < current ? "done" : index === current ? "current" : "future";
          return (
            <li key={status} className={state}>
              <strong>{label}{events.length > 1 ? " · 有退回" : ""}</strong>
              {event && (
                <span>{event.actorName} · {formatTime(event.createdAt)}{event.note ? ` · ${event.note}` : ""}</span>
              )}
            </li>
          );
        })}
      </ol>
      {(submission.actions.advance || submission.actions.revert) && (
        <div className="actions">
          {submission.actions.advance && (
            <button type="button" onClick={() => onMove(submission.id, "advance", "")}>
              {submission.actions.advanceLabel}
            </button>
          )}
          {submission.actions.revert && (
            <button type="button" className="ghost" onClick={() => onMove(submission.id, "revert", "")}>
              退回上一步
            </button>
          )}
        </div>
      )}
    </div>
  );
}

function defaultDeadline() {
  const now = new Date();
  return composeDeadline({
    year: now.getFullYear(),
    month: now.getMonth() + 1,
    day: now.getDate(),
    hour: 23,
    minute: 59,
  });
}

function composeDeadline(parts) {
  const pad = (part) => String(part).padStart(2, "0");
  return `${parts.year}-${pad(parts.month)}-${pad(parts.day)}T${pad(parts.hour)}:${pad(parts.minute)}`;
}

function deadlineParts(value) {
  const text = deadlineInput(value) || defaultDeadline();
  const [date, time] = text.split("T");
  const [year, month, day] = date.split("-").map(Number);
  const [hour, minute] = time.split(":").map(Number);
  return { year, month, day, hour, minute };
}

function DeadlineFields({ value, onChange }) {
  const parts = deadlineParts(value);
  const nowYear = new Date().getFullYear();
  const years = [];
  for (let year = Math.min(nowYear, parts.year); year <= Math.max(nowYear + 1, parts.year); year += 1) years.push(year);
  const dayCount = new Date(parts.year, parts.month, 0).getDate();
  function update(patch) {
    const next = { ...parts, ...patch };
    const max = new Date(next.year, next.month, 0).getDate();
    if (next.day > max) next.day = max;
    onChange(composeDeadline(next));
  }
  return (
    <div className="field">
      截止时间
      <div className="deadline-picks">
        <select value={parts.year} onChange={(event) => update({ year: Number(event.target.value) })}>
          {years.map((year) => <option key={year} value={year}>{year}年</option>)}
        </select>
        <select value={parts.month} onChange={(event) => update({ month: Number(event.target.value) })}>
          {Array.from({ length: 12 }, (_, index) => index + 1).map((month) => <option key={month} value={month}>{month}月</option>)}
        </select>
        <select value={parts.day} onChange={(event) => update({ day: Number(event.target.value) })}>
          {Array.from({ length: dayCount }, (_, index) => index + 1).map((day) => <option key={day} value={day}>{day}日</option>)}
        </select>
      </div>
      <div className="deadline-picks">
        <select value={parts.hour} onChange={(event) => update({ hour: Number(event.target.value) })}>
          {Array.from({ length: 24 }, (_, hour) => hour).map((hour) => <option key={hour} value={hour}>{hour}时</option>)}
        </select>
        <select value={parts.minute} onChange={(event) => update({ minute: Number(event.target.value) })}>
          {Array.from({ length: 60 }, (_, minute) => minute).map((minute) => <option key={minute} value={minute}>{minute}分</option>)}
        </select>
      </div>
    </div>
  );
}

function deadlineInput(value) {
  if (!value) return "";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value).slice(0, 16);
  const pad = (part) => String(part).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function Field({ label, value, onChange, type = "text" }) {
  return (
    <label className="field">
      {label}
      <input type={type} value={value} onChange={onChange} />
    </label>
  );
}
