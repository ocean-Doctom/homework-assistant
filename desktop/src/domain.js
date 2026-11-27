export const FULL_STATUSES = [
  "not_submitted",
  "submitted_to_monitor",
  "received_by_ta",
  "graded",
  "returned_to_monitor",
  "distributed",
];

export const COLLECT_STATUSES = ["not_submitted", "ready_to_submit", "collected_by_monitor", "retrieved_by_monitor", "distributed"];

export const STATUSES = FULL_STATUSES;

const FULL_LABELS = {
  not_submitted: "未提交",
  submitted_to_monitor: "已提交到学习委员",
  received_by_ta: "助教已接收",
  graded: "已批改",
  returned_to_monitor: "已移交学习委员",
  distributed: "已分发",
};

const COLLECT_LABELS = {
  not_submitted: "未写完",
  ready_to_submit: "已写完，随时可以提交",
  collected_by_monitor: "学委已揽收",
  retrieved_by_monitor: "学委已取回",
  distributed: "学委已分发",
};

export const STATUS_LABELS = FULL_LABELS;

export const ROLE_LABELS = {
  student: "同学",
  monitor: "学习委员",
  ta: "助教",
};

const ADVANCE_LABELS = {
  not_submitted: { student: "我已交给学委", monitor: "登记已提交" },
  submitted_to_monitor: { ta: "确认接收" },
  received_by_ta: { ta: "标记已批改" },
  graded: { ta: "移交学习委员" },
  returned_to_monitor: { monitor: "标记已分发" },
};

export function statusesFor(mode) {
  return mode === "collect" ? COLLECT_STATUSES : FULL_STATUSES;
}

export function statusLabel(status, mode = "full") {
  if (mode === "collect" && COLLECT_LABELS[status]) return COLLECT_LABELS[status];
  return FULL_LABELS[status] ?? COLLECT_LABELS[status] ?? status;
}

export function nextStatus(status, mode = "full") {
  const list = statusesFor(mode);
  const index = list.indexOf(status);
  if (index < 0 || index === list.length - 1) return null;
  return list[index + 1];
}

export function previousStatus(status, mode = "full") {
  const list = statusesFor(mode);
  const index = list.indexOf(status);
  if (index <= 0) return null;
  return list[index - 1];
}

function ownsAdvance(role, status, isOwn) {
  if (status === "not_submitted") return role === "monitor" || (role === "student" && isOwn);
  if (status === "submitted_to_monitor" || status === "received_by_ta" || status === "graded") {
    return role === "ta";
  }
  if (status === "returned_to_monitor") return role === "monitor";
  return false;
}

export function canAdvance(user, submission, mode = "full") {
  const isOwn = user.id === submission.studentId;
  if (mode === "collect") {
    if (submission.status === "not_submitted") return isOwn && (user.role === "student" || user.role === "monitor");
    return false;
  }
  if (user.role === "monitor" && isOwn && submission.status === "not_submitted") return true;
  return ownsAdvance(user.role, submission.status, isOwn);
}

export function canCollect(user, submission, mode = "full") {
  return mode === "collect" && user.role === "monitor" && (submission.status === "not_submitted" || submission.status === "ready_to_submit");
}

export function canRevert(user, submission, mode = "full") {
  const isOwn = user.id === submission.studentId;
  if (mode === "collect") {
    if (submission.status === "ready_to_submit") return isOwn && (user.role === "student" || user.role === "monitor");
    if (submission.status === "collected_by_monitor") return user.role === "monitor";
    return false;
  }
  const previous = previousStatus(submission.status, mode);
  if (!previous) return false;
  return ownsAdvance(user.role, previous, isOwn);
}

export function advanceLabel(user, submission, mode = "full") {
  if (!canAdvance(user, submission, mode)) return null;
  if (mode === "collect") return "我已写完，随时可以提交";
  if (user.role === "monitor" && user.id === submission.studentId && submission.status === "not_submitted") {
    return "我已交给学委";
  }
  return ADVANCE_LABELS[submission.status]?.[user.role] ?? null;
}

export function receivesSubmission(role) {
  return role === "student" || role === "monitor";
}
