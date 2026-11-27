package edu.hust.homeworklogistics

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Offline backend for 离线体验. It answers the same paths and JSON shapes as the
 * real server and keeps its data in SharedPreferences, so it survives restarts.
 */
class DemoBackend private constructor(context: Context) : Backend {
    companion object {
        const val TOKEN_PREFIX = "demo-"

        @Volatile
        private var instance: DemoBackend? = null

        fun get(context: Context): DemoBackend =
            instance ?: synchronized(this) {
                instance ?: DemoBackend(context.applicationContext).also { instance = it }
            }

        /** studentNo, password, display name, role label */
        val ACCOUNTS = listOf(
            listOf("U2026001", "pass1234", "李学委"),
            listOf("U2026002", "U2026002", "Wang"),
        )
    }

    private val prefs = context.getSharedPreferences("demo", Context.MODE_PRIVATE)
    private val uploads = File(context.filesDir, "uploads").apply { mkdirs() }
    private lateinit var state: JSONObject

    init {
        val saved = prefs.getString("state", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (saved != null) state = saved else seed()
        if (!state.has("comments")) state.put("comments", JSONArray())
    }

    @Synchronized
    fun reset() = seed()

    override fun get(path: String, token: String) = handle("GET", path, token, JSONObject())

    override fun post(path: String, token: String, body: JSONObject) = handle("POST", path, token, body)

    override fun patch(path: String, token: String, body: JSONObject) = handle("PATCH", path, token, body)

    override fun delete(path: String, token: String) = handle("DELETE", path, token, JSONObject())

    @Synchronized
    override fun bytes(path: String, token: String): ByteArray {
        current(token)
        val name = path.substringAfterLast('/')
        if (!name.matches(Regex("[A-Za-z0-9._-]+"))) fail("没有这张图片")
        val file = File(uploads, name)
        if (!file.isFile) fail("没有这张图片")
        return file.readBytes()
    }

    @Synchronized
    private fun handle(method: String, path: String, token: String, body: JSONObject): JSONObject {
        val clean = path.substringBefore('?').trimEnd('/')
        val parts = clean.trim('/').split('/')
        return when {
            method == "GET" && clean == "/health" -> JSONObject().put("ok", true).put("service", "homework-logistics")
            method == "GET" && clean == "/bootstrap" -> JSONObject().put("needsSetup", false)
            method == "POST" && clean == "/setup" -> fail("离线体验里班级已经建好了")
            method == "POST" && clean == "/login" -> login(body)
            method == "GET" && clean == "/me" -> JSONObject().put("user", publicUser(current(token)))
            method == "POST" && clean == "/me/avatar" -> setAvatar(token, body)
            method == "GET" && clean == "/members" -> members(token)
            method == "POST" && clean == "/members" -> addMember(token, body)
            method == "GET" && clean == "/assignments" -> assignments(token)
            method == "GET" && clean == "/messages" -> messages(token)
            method == "POST" && clean == "/assignments" -> publish(token, body)
            method == "POST" && parts.size == 3 && parts[0] == "assignments" && parts[2] == "images" ->
                addImage(token, parts[1], body)
            method == "GET" && parts.size == 3 && parts[0] == "assignments" && parts[2] == "comments" ->
                listComments(token, parts[1])
            method == "POST" && parts.size == 3 && parts[0] == "assignments" && parts[2] == "comments" ->
                addComment(token, parts[1], body)
            method == "POST" && parts.size == 3 && parts[0] == "comments" && parts[2] == "images" ->
                addCommentImage(token, parts[1], body)
            method == "DELETE" && parts.size == 2 && parts[0] == "comments" ->
                deleteComment(token, parts[1])
            method == "GET" && parts.size == 2 && parts[0] == "assignments" -> assignment(token, parts[1])
            method == "GET" && parts.size == 3 && parts[0] == "assignments" && parts[2] == "mine" -> mine(token, parts[1])
            method == "GET" && parts.size == 3 && parts[0] == "assignments" && parts[2] == "submissions" -> submissions(token, parts[1])
            method == "PATCH" && parts.size == 2 && parts[0] == "assignments" -> updateAssignment(token, parts[1], body)
            method == "DELETE" && parts.size == 2 && parts[0] == "assignments" -> deleteAssignment(token, parts[1])
            method == "POST" && parts.size == 3 && parts[0] == "assignments" && (parts[2] == "retrieve" || parts[2] == "distribute") ->
                shiftClass(token, parts[1], parts[2])
            method == "PATCH" && parts.size == 2 && parts[0] == "members" -> updateMember(token, parts[1], body)
            method == "DELETE" && parts.size == 2 && parts[0] == "members" -> deleteMember(token, parts[1])
            method == "POST" && parts.size == 3 && parts[0] == "members" && parts[2] == "password" ->
                resetPassword(token, parts[1], body)
            method == "POST" && parts.size == 3 && parts[0] == "members" && parts[2] == "transfer" ->
                transferMonitor(token, parts[1], body)
            method == "POST" && parts.size == 3 && parts[0] == "submissions" && parts[2] == "collect" ->
                collect(token, parts[1], body)
            method == "POST" && parts.size == 3 && parts[0] == "submissions" && (parts[2] == "advance" || parts[2] == "revert") ->
                move(token, parts[1], parts[2], body)
            else -> fail("离线体验暂不支持这个操作")
        }
    }

    // ---------- helpers ----------

    private fun fail(message: String): Nothing = throw IllegalStateException(message)

    private fun rows(name: String): List<JSONObject> {
        val array = state.getJSONArray(name)
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    private fun add(name: String, row: JSONObject) {
        state.getJSONArray(name).put(row)
    }

    private fun nextId(): Int {
        val id = state.optInt("nextId", 1000)
        state.put("nextId", id + 1)
        return id
    }

    private fun save() {
        prefs.edit().putString("state", state.toString()).apply()
    }

    private fun user(id: Int) = rows("users").firstOrNull { it.getInt("id") == id }

    private fun current(token: String): JSONObject {
        if (!token.startsWith(TOKEN_PREFIX)) fail("请先登录")
        val id = token.removePrefix(TOKEN_PREFIX).toIntOrNull() ?: fail("请先登录")
        return user(id) ?: fail("请先登录")
    }

    private fun text(body: JSONObject, key: String) =
        if (body.isNull(key)) "" else body.optString(key).trim()

    private fun requireText(body: JSONObject, key: String, label: String): String {
        val value = text(body, key)
        if (value.isEmpty()) fail("请填写$label")
        return value
    }

    private fun publicUser(row: JSONObject) = JSONObject()
        .put("id", row.getInt("id"))
        .put("studentNo", row.getString("studentNo"))
        .put("name", row.getString("name"))
        .put("role", row.getString("role"))
        .put("roleLabel", Domain.ROLE_LABELS[row.getString("role")])
        .put("avatar", row.optString("avatar").let { if (it.isBlank()) "" else "/files/$it" })

    private fun presentAssignment(row: JSONObject) = JSONObject()
        .put("id", row.getInt("id"))
        .put("title", row.getString("title"))
        .put("description", row.optString("description"))
        .put("deadline", row.optString("deadline").ifBlank { null } ?: JSONObject.NULL)
        .put("mode", row.optString("mode").ifBlank { "full" })
        .put("createdAt", row.getString("createdAt"))
        .put("publisherName", user(row.getInt("publisherId"))?.getString("name") ?: "")
        .put("images", imageUrls(row))

    private fun presentSubmission(row: JSONObject, viewer: JSONObject): JSONObject {
        val student = user(row.getInt("studentId"))!!
        val status = row.getString("status")
        val viewerId = viewer.getInt("id")
        val role = viewer.getString("role")
        val studentId = row.getInt("studentId")
        val mode = rows("assignments").firstOrNull { it.getInt("id") == row.getInt("assignmentId") }?.optString("mode").orEmpty().ifBlank { "full" }
        val events = JSONArray()
        rows("events")
            .filter { it.getInt("submissionId") == row.getInt("id") }
            .sortedBy { it.getInt("id") }
            .forEach { event ->
                events.put(
                    JSONObject()
                        .put("id", event.getInt("id"))
                        .put("status", event.getString("status"))
                        .put("statusLabel", Domain.label(event.getString("status"), mode))
                        .put("note", event.optString("note"))
                        .put("actorName", user(event.getInt("actorId"))?.getString("name") ?: "")
                        .put("createdAt", event.getString("createdAt")),
                )
            }
        return JSONObject()
            .put("id", row.getInt("id"))
            .put("assignmentId", row.getInt("assignmentId"))
            .put("student", publicUser(student))
            .put("status", status)
            .put("statusLabel", Domain.label(status, mode))
            .put("updatedAt", row.getString("updatedAt"))
            .put("events", events)
            .put(
                "actions",
                JSONObject()
                    .put("advance", Domain.canAdvance(viewerId, role, studentId, status, mode))
                    .put("revert", Domain.canRevert(viewerId, role, studentId, status, mode))
                    .put("collect", Domain.canCollect(role, status, mode))
                    .put("advanceLabel", Domain.advanceLabel(viewerId, role, studentId, status, mode) ?: JSONObject.NULL),
            )
    }

    private fun insertSubmission(assignmentId: Int, studentId: Int, actorId: Int, at: String): JSONObject {
        val submission = JSONObject()
            .put("id", nextId())
            .put("assignmentId", assignmentId)
            .put("studentId", studentId)
            .put("status", "not_submitted")
            .put("updatedAt", at)
        add("submissions", submission)
        addEvent(submission.getInt("id"), "not_submitted", actorId, "", at)
        return submission
    }

    private fun addEvent(submissionId: Int, status: String, actorId: Int, note: String, at: String) {
        add(
            "events",
            JSONObject()
                .put("id", nextId())
                .put("submissionId", submissionId)
                .put("status", status)
                .put("actorId", actorId)
                .put("note", note)
                .put("createdAt", at),
        )
    }

    // ---------- routes ----------

    private fun login(body: JSONObject): JSONObject {
        val studentNo = requireText(body, "studentNo", "学号")
        val password = if (body.isNull("password")) "" else body.optString("password")
        val row = rows("users").firstOrNull { it.getString("studentNo") == studentNo }
        if (row == null || row.getString("password") != password) fail("学号或密码不对")
        return JSONObject().put("token", TOKEN_PREFIX + row.getInt("id")).put("user", publicUser(row))
    }

    private fun members(token: String): JSONObject {
        val viewer = current(token)
        if (viewer.getString("role") != "monitor") fail("只有学习委员可以看成员")
        val order = mapOf("monitor" to 0, "ta" to 1, "student" to 2)
        val list = JSONArray()
        rows("users")
            .filter { it.getString("role") != "ta" }
            .sortedWith(compareBy<JSONObject>({ order[it.getString("role")] ?: 3 }, { it.getString("studentNo") }))
            .forEach { list.put(publicUser(it)) }
        return JSONObject().put("members", list)
    }

    private fun addMember(token: String, body: JSONObject): JSONObject {
        val viewer = current(token)
        if (viewer.getString("role") != "monitor") fail("只有学习委员可以加人")
        val name = requireText(body, "name", "姓名")
        val studentNo = requireText(body, "studentNo", "学号")
        val role = "student"
        val password = text(body, "password").ifEmpty { studentNo }
        if (rows("users").any { it.getString("studentNo") == studentNo }) fail("这个学号已经在班里了")
        val now = Instant.now().toString()
        val created = JSONObject()
            .put("id", nextId())
            .put("studentNo", studentNo)
            .put("name", name)
            .put("role", role)
            .put("password", password)
        add("users", created)
        if (role == "student") {
            rows("assignments").forEach {
                insertSubmission(it.getInt("id"), created.getInt("id"), it.getInt("publisherId"), now)
            }
        }
        save()
        return JSONObject().put("user", publicUser(created))
    }

    private fun assignments(token: String): JSONObject {
        current(token)
        val list = JSONArray()
        rows("assignments").sortedByDescending { it.getInt("id") }.forEach { list.put(presentAssignment(it)) }
        return JSONObject().put("assignments", list)
    }

    private fun publish(token: String, body: JSONObject): JSONObject {
        val viewer = current(token)
        if (viewer.getString("role") != "monitor") fail("只有学习委员可以发布作业")
        val title = requireText(body, "title", "标题")
        val now = Instant.now().toString()
        val row = JSONObject()
            .put("id", nextId())
            .put("title", title)
            .put("description", text(body, "description"))
            .put("deadline", text(body, "deadline"))
            .put("mode", if (text(body, "mode") == "full") "full" else "collect")
            .put("publisherId", viewer.getInt("id"))
            .put("createdAt", now)
        add("assignments", row)
        rows("users")
            .filter { it.getString("role") == "student" || it.getString("role") == "monitor" }
            .forEach { insertSubmission(row.getInt("id"), it.getInt("id"), viewer.getInt("id"), now) }
        val messageId = addMessage(title, "${viewer.getString("name")}发布了作业", row.getInt("id"), now)
        save()
        return JSONObject().put("id", row.getInt("id")).put("messageId", messageId)
    }

    private fun messages(token: String): JSONObject {
        current(token)
        if (!state.has("messages")) state.put("messages", JSONArray())
        val list = JSONArray()
        rows("messages").sortedByDescending { it.getInt("id") }.take(50).forEach { row ->
            list.put(
                JSONObject()
                    .put("id", row.getInt("id"))
                    .put("kind", row.optString("kind"))
                    .put("title", row.optString("title"))
                    .put("body", row.optString("body"))
                    .put("assignmentId", row.optInt("assignmentId"))
                    .put("createdAt", row.optString("createdAt")),
            )
        }
        return JSONObject().put("messages", list)
    }

    private fun addMessage(title: String, body: String, assignmentId: Int, at: String): Int {
        if (!state.has("messages")) state.put("messages", JSONArray())
        val id = nextId()
        add(
            "messages",
            JSONObject()
                .put("id", id)
                .put("kind", "assignment")
                .put("title", title)
                .put("body", body)
                .put("assignmentId", assignmentId)
                .put("createdAt", at),
        )
        return id
    }

    private fun updateAssignment(token: String, id: String, body: JSONObject): JSONObject {
        val viewer = current(token)
        if (viewer.getString("role") != "monitor") fail("只有学习委员可以修改作业")
        val row = findAssignment(id)
        row.put("title", requireText(body, "title", "标题"))
        row.put("description", text(body, "description"))
        row.put("deadline", text(body, "deadline"))
        save()
        return JSONObject().put("ok", true)
    }

    private fun deleteAssignment(token: String, id: String): JSONObject {
        val viewer = current(token)
        if (viewer.getString("role") != "monitor") fail("只有学习委员可以删除作业")
        val row = findAssignment(id)
        val assignmentId = row.getInt("id")
        val submissionIds = rows("submissions")
            .filter { it.getInt("assignmentId") == assignmentId }
            .map { it.getInt("id") }
            .toSet()
        keepRows("events") { it.getInt("submissionId") !in submissionIds }
        keepRows("submissions") { it.getInt("assignmentId") != assignmentId }
        keepRows("assignments") { it.getInt("id") != assignmentId }
        if (state.has("comments")) keepRows("comments") { it.optInt("assignmentId") != assignmentId }
        if (state.has("messages")) keepRows("messages") { it.optInt("assignmentId") != assignmentId }
        save()
        return JSONObject().put("ok", true)
    }

    private fun shiftClass(token: String, assignmentId: String, step: String): JSONObject {
        val viewer = current(token)
        if (viewer.getString("role") != "monitor") fail("只有学习委员可以操作")
        val from = if (step == "retrieve") "collected_by_monitor" else "retrieved_by_monitor"
        val to = if (step == "retrieve") "retrieved_by_monitor" else "distributed"
        val targets = rows("submissions").filter {
            it.getInt("assignmentId").toString() == assignmentId && it.getString("status") == from
        }
        if (targets.isEmpty()) fail(if (from == "collected_by_monitor") "还没有已揽收的同学" else "还没有可以分发的进度")
        val now = Instant.now().toString()
        targets.forEach { row ->
            row.put("status", to).put("updatedAt", now)
            addEvent(row.getInt("id"), to, viewer.getInt("id"), "", now)
        }
        save()
        return JSONObject().put("ok", true).put("count", targets.size)
    }

    private fun keepRows(name: String, keep: (JSONObject) -> Boolean) {
        val array = state.getJSONArray(name)
        val next = JSONArray()
        for (index in 0 until array.length()) {
            val row = array.getJSONObject(index)
            if (keep(row)) next.put(row)
        }
        state.put(name, next)
    }

    private fun resetPassword(token: String, id: String, body: JSONObject): JSONObject {
        val viewer = current(token)
        if (viewer.getString("role") != "monitor") fail("只有学习委员可以重置密码")
        val target = rows("users").firstOrNull { it.getInt("id").toString() == id } ?: fail("没有这个成员")
        if (target.getString("role") == "monitor" && target.getInt("id") != viewer.getInt("id")) fail("不能改其他学委的密码")
        target.put("password", requireText(body, "password", "新密码"))
        save()
        return JSONObject().put("ok", true)
    }

    private fun deleteMember(token: String, id: String): JSONObject {
        val viewer = current(token)
        if (viewer.getString("role") != "monitor") fail("只有学习委员可以删除成员")
        val target = rows("users").firstOrNull { it.getInt("id").toString() == id } ?: fail("没有这个成员")
        if (target.getInt("id") == viewer.getInt("id")) fail("不能删除自己")
        if (target.getString("role") == "monitor") fail("不能删除学委")
        val userId = target.getInt("id")
        val submissionIds = rows("submissions").filter { it.getInt("studentId") == userId }.map { it.getInt("id") }.toSet()
        keepRows("events") { it.getInt("submissionId") !in submissionIds }
        keepRows("submissions") { it.getInt("studentId") != userId }
        keepRows("users") { it.getInt("id") != userId }
        save()
        return JSONObject().put("ok", true)
    }

    private fun updateMember(token: String, id: String, body: JSONObject): JSONObject {
        val viewer = current(token)
        val target = rows("users").firstOrNull { it.getInt("id").toString() == id } ?: fail("没有这个成员")
        val self = target.getInt("id") == viewer.getInt("id")
        if (!self && viewer.getString("role") != "monitor") fail("只能修改自己的资料")
        if (!self && target.getString("role") == "monitor") fail("不能修改其他学委")
        val studentNo = requireText(body, "studentNo", "学号")
        if (rows("users").any { it.getInt("id") != target.getInt("id") && it.getString("studentNo") == studentNo }) {
            fail("这个学号已经在班里了")
        }
        target.put("name", requireText(body, "name", "姓名"))
        target.put("studentNo", studentNo)
        save()
        return JSONObject().put("user", publicUser(target))
    }

    private fun transferMonitor(token: String, id: String, body: JSONObject): JSONObject {
        val viewer = current(token)
        if (viewer.getString("role") != "monitor") fail("只有学习委员可以转让")
        if (body.optString("phrase") != "我已确认转让") fail("请输入“我已确认转让”")
        val target = rows("users").firstOrNull { it.getInt("id").toString() == id } ?: fail("没有这个成员")
        if (target.getInt("id") == viewer.getInt("id")) fail("不能转让给自己")
        viewer.put("role", "student")
        target.put("role", "monitor")
        save()
        return JSONObject().put("user", publicUser(viewer))
    }

    private fun findAssignment(id: String) =
        rows("assignments").firstOrNull { it.getInt("id").toString() == id } ?: fail("没有这次作业")

    private fun assignment(token: String, id: String): JSONObject {
        current(token)
        return presentAssignment(findAssignment(id))
    }

    private fun mine(token: String, id: String): JSONObject {
        val viewer = current(token)
        val row = rows("submissions").firstOrNull {
            it.getInt("assignmentId").toString() == id && it.getInt("studentId") == viewer.getInt("id")
        } ?: fail("这次作业没有你的进度")
        return JSONObject().put("submission", presentSubmission(row, viewer))
    }

    private fun submissions(token: String, id: String): JSONObject {
        val viewer = current(token)
        val role = viewer.getString("role")
        if (role == "student") fail("只能看自己的进度")
        val list = JSONArray()
        rows("submissions")
            .filter { it.getInt("assignmentId").toString() == id }
            .filter { user(it.getInt("studentId"))?.optString("role") != "ta" }
            .sortedBy { user(it.getInt("studentId"))?.getString("studentNo") ?: "" }
            .forEach { list.put(presentSubmission(it, viewer)) }
        return JSONObject().put("submissions", list)
    }

    private fun move(token: String, id: String, direction: String, body: JSONObject): JSONObject {
        val viewer = current(token)
        val row = rows("submissions").firstOrNull { it.getInt("id").toString() == id } ?: fail("没有这条进度")
        val status = row.getString("status")
        val viewerId = viewer.getInt("id")
        val role = viewer.getString("role")
        val studentId = row.getInt("studentId")
        val mode = modeOf(row.getInt("assignmentId"))
        val allowed = if (direction == "advance") Domain.canAdvance(viewerId, role, studentId, status, mode)
        else Domain.canRevert(viewerId, role, studentId, status, mode)
        if (!allowed) fail("现在不能改这一步")
        val list = Domain.statuses(mode)
        val index = list.indexOf(status)
        var target = if (direction == "advance") list.getOrNull(index + 1) else list.getOrNull(index - 1)
        if (direction == "revert" && mode == "collect" && status == "collected_by_monitor") {
            val hadReady = rows("events").any { it.getInt("submissionId") == row.getInt("id") && it.getString("status") == "ready_to_submit" }
            target = if (hadReady) "ready_to_submit" else "not_submitted"
        }
        val next = target ?: fail("现在不能改这一步")
        val note = text(body, "note").take(200)
        val now = Instant.now().toString()
        row.put("status", next).put("updatedAt", now)
        addEvent(row.getInt("id"), next, viewerId, note, now)
        save()
        return JSONObject().put("submission", presentSubmission(row, viewer))
    }

    private fun modeOf(assignmentId: Int) =
        rows("assignments").firstOrNull { it.getInt("id") == assignmentId }?.optString("mode").orEmpty().ifBlank { "full" }

    private fun collect(token: String, id: String, body: JSONObject): JSONObject {
        val viewer = current(token)
        val row = rows("submissions").firstOrNull { it.getInt("id").toString() == id } ?: fail("没有这条进度")
        val mode = modeOf(row.getInt("assignmentId"))
        if (!Domain.canCollect(viewer.getString("role"), row.getString("status"), mode)) fail("现在不能揽收")
        val now = Instant.now().toString()
        row.put("status", "collected_by_monitor").put("updatedAt", now)
        addEvent(row.getInt("id"), "collected_by_monitor", viewer.getInt("id"), text(body, "note").take(200), now)
        save()
        return JSONObject().put("submission", presentSubmission(row, viewer))
    }

    private fun imageUrls(row: JSONObject): JSONArray {
        val names = row.optJSONArray("images") ?: JSONArray()
        val out = JSONArray()
        for (index in 0 until names.length()) {
            out.put(JSONObject().put("id", index + 1).put("url", "/files/${names.getString(index)}"))
        }
        return out
    }

    private fun writeUpload(body: JSONObject): String {
        val raw = body.optString("data")
        if (raw.isBlank()) fail("请选择图片")
        val bytes = Base64.decode(raw, Base64.DEFAULT)
        if (bytes.isEmpty() || bytes.size > 4 * 1024 * 1024) fail("图片请小于 4MB")
        val name = UUID.randomUUID().toString().replace("-", "") + ".jpg"
        File(uploads, name).writeBytes(bytes)
        return name
    }

    private fun setAvatar(token: String, body: JSONObject): JSONObject {
        val viewer = current(token)
        viewer.put("avatar", writeUpload(body))
        save()
        return JSONObject().put("user", publicUser(viewer))
    }

    private fun addImage(token: String, id: String, body: JSONObject): JSONObject {
        val viewer = current(token)
        if (viewer.getString("role") != "monitor") fail("只有学习委员可以上传图片")
        val row = findAssignment(id)
        val images = row.optJSONArray("images") ?: JSONArray().also { row.put("images", it) }
        if (images.length() >= 6) fail("一次作业最多 6 张图片")
        images.put(writeUpload(body))
        save()
        return JSONObject().put("images", imageUrls(row))
    }

    private fun listComments(token: String, id: String): JSONObject {
        current(token)
        findAssignment(id)
        val array = JSONArray()
        rows("comments")
            .filter { it.optInt("assignmentId").toString() == id }
            .sortedBy { it.getInt("id") }
            .forEach { array.put(presentComment(it)) }
        return JSONObject().put("comments", array)
    }

    private fun addComment(token: String, id: String, body: JSONObject): JSONObject {
        val viewer = current(token)
        findAssignment(id)
        val bodyText = text(body, "body").take(500)
        val row = JSONObject()
            .put("id", nextId())
            .put("assignmentId", id.toInt())
            .put("authorId", viewer.getInt("id"))
            .put("body", bodyText)
            .put("createdAt", Instant.now().toString())
            .put("images", JSONArray())
        add("comments", row)
        save()
        return JSONObject().put("comment", presentComment(row))
    }

    private fun addCommentImage(token: String, id: String, body: JSONObject): JSONObject {
        val viewer = current(token)
        val row = rows("comments").firstOrNull { it.getInt("id").toString() == id } ?: fail("没有这条评论")
        if (row.getInt("authorId") != viewer.getInt("id")) fail("只能给自己的评论加图片")
        val images = row.optJSONArray("images") ?: JSONArray().also { row.put("images", it) }
        if (images.length() >= 4) fail("一条评论最多 4 张图片")
        val name = writeUpload(body)
        images.put(name)
        save()
        return JSONObject().put("id", images.length()).put("url", "/files/$name")
    }

    private fun deleteComment(token: String, id: String): JSONObject {
        val viewer = current(token)
        val row = rows("comments").firstOrNull { it.getInt("id").toString() == id } ?: fail("没有这条评论")
        val mine = row.getInt("authorId") == viewer.getInt("id")
        if (!mine && viewer.getString("role") != "monitor") fail("只能删除自己的评论")
        keepRows("comments") { it.getInt("id").toString() != id }
        save()
        return JSONObject().put("ok", true)
    }

    private fun presentComment(row: JSONObject): JSONObject {
        val author = user(row.getInt("authorId"))
        val images = JSONArray()
        val names = row.optJSONArray("images") ?: JSONArray()
        for (index in 0 until names.length()) {
            images.put(JSONObject().put("id", index + 1).put("url", "/files/${names.getString(index)}"))
        }
        return JSONObject()
            .put("id", row.getInt("id"))
            .put("body", row.optString("body"))
            .put("createdAt", row.optString("createdAt"))
            .put("author", if (author == null) JSONObject().put("id", "").put("name", "").put("role", "").put("avatar", "") else publicUser(author))
            .put("images", images)
    }

    // ---------- sample data ----------

    private fun seed() {
        state = JSONObject()
            .put("users", JSONArray())
            .put("assignments", JSONArray())
            .put("submissions", JSONArray())
            .put("events", JSONArray())
            .put("messages", JSONArray())
            .put("comments", JSONArray())
            .put("nextId", 100)
            .put("className", "能动2401")

        fun person(id: Int, no: String, name: String, role: String, password: String) = add(
            "users",
            JSONObject().put("id", id).put("studentNo", no).put("name", name).put("role", role).put("password", password),
        )
        person(1, "U2026001", "李学委", "monitor", "pass1234")
        person(3, "U2026002", "Wang", "student", "U2026002")
        person(4, "U2026003", "张晨", "student", "U2026003")
        person(5, "U2026004", "刘洋", "student", "U2026004")
        person(6, "U2026005", "陈静", "student", "U2026005")
        val classmates = listOf(1, 3, 4, 5, 6)

        val now = Instant.now()

        fun assignment(id: Int, title: String, description: String, deadline: String, hoursAgo: Long, collected: Set<Int>, ready: Set<Int>) {
            val created = now.minus(hoursAgo, ChronoUnit.HOURS)
            add(
                "assignments",
                JSONObject()
                    .put("id", id)
                    .put("title", title)
                    .put("description", description)
                    .put("deadline", deadline)
                    .put("mode", "collect")
                    .put("publisherId", 1)
                    .put("createdAt", created.toString()),
            )
            classmates.forEach { studentId ->
                val submission = insertSubmission(id, studentId, 1, created.toString())
                if (studentId in ready || studentId in collected) {
                    val at = created.plus(2, ChronoUnit.HOURS).toString()
                    addEvent(submission.getInt("id"), "ready_to_submit", studentId, "", at)
                    submission.put("status", "ready_to_submit").put("updatedAt", at)
                }
                if (studentId in collected) {
                    val at = created.plus(4, ChronoUnit.HOURS).toString()
                    addEvent(submission.getInt("id"), "collected_by_monitor", 1, "", at)
                    submission.put("status", "collected_by_monitor").put("updatedAt", at)
                }
            }
        }

        assignment(1, "第三章习题", "课本 P86 第 1–12 题", "2026-11-28T23:59", 96, setOf(1, 3), setOf(4))
        assignment(2, "实验报告一", "对流、沸腾、辐射换热实验报告", "2026-12-03T18:00", 48, emptySet(), setOf(1, 4))
        assignment(3, "第四章习题", "课本 P120 第 1–8 题", "2026-12-08T23:59", 3, emptySet(), emptySet())
        val paper = JSONObject()
            .put("id", 4)
            .put("title", "本周纸质作业")
            .put("description", "写在作业本上，交给学委。")
            .put("deadline", "")
            .put("mode", "collect")
            .put("publisherId", 1)
            .put("createdAt", now.toString())
        add("assignments", paper)
        classmates.forEach { insertSubmission(4, it, 1, now.toString()) }
        save()
    }
}
