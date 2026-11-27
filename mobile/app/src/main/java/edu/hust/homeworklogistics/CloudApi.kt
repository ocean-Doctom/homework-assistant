package edu.hust.homeworklogistics

import android.content.SharedPreferences
import android.util.Base64
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

interface Backend {
    fun get(path: String, token: String = ""): JSONObject
    fun post(path: String, token: String = "", body: JSONObject = JSONObject()): JSONObject
    fun patch(path: String, token: String = "", body: JSONObject = JSONObject()): JSONObject
    fun delete(path: String, token: String = ""): JSONObject
    fun bytes(path: String, token: String): ByteArray
}

object CloudSession {
    private const val KEY = "cloud_session"
    var accessToken = ""
    var refreshToken = ""
    var expiresAt = 0L
    var uid = ""

    fun load(prefs: SharedPreferences) {
        val raw = prefs.getString(KEY, "").orEmpty()
        if (raw.isBlank()) return
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
        accessToken = json.optString("accessToken")
        refreshToken = json.optString("refreshToken")
        expiresAt = json.optLong("expiresAt")
        uid = json.optString("uid")
    }

    fun save(prefs: SharedPreferences) {
        prefs.edit().putString(
            KEY,
            JSONObject()
                .put("accessToken", accessToken)
                .put("refreshToken", refreshToken)
                .put("expiresAt", expiresAt)
                .put("uid", uid)
                .toString(),
        ).apply()
    }

    fun clear(prefs: SharedPreferences) {
        accessToken = ""
        refreshToken = ""
        expiresAt = 0L
        uid = ""
        prefs.edit().remove(KEY).apply()
    }

    fun apply(access: String, refresh: String, exp: Long, userId: String) {
        accessToken = access
        if (refresh.isNotBlank()) refreshToken = refresh
        expiresAt = exp
        if (userId.isNotBlank()) uid = userId
    }
}

class CloudApi(private val prefs: SharedPreferences) : Backend {
    private val host = "https://homework-assistant-14264.app.workbuddy.host"
    private val key = "wbpk_ofKMBbEQ72KsQszouCeHGw_yy7VeDCdh8d5aGN3RFhGIYPsJ8mtfESj"

    override fun get(path: String, token: String) = dispatch("GET", path, null)
    override fun post(path: String, token: String, body: JSONObject) = dispatch("POST", path, body)
    override fun patch(path: String, token: String, body: JSONObject) = dispatch("PATCH", path, body)
    override fun delete(path: String, token: String) = dispatch("DELETE", path, JSONObject())

    override fun bytes(path: String, token: String): ByteArray {
        val url = if (path.startsWith("http")) path else signedUrl(path)
        val connection = open(url, "GET", auth = false)
        return readBytes(connection)
    }

    private fun dispatch(method: String, path: String, body: JSONObject?): JSONObject {
        if (method == "GET" && path == "/health") return JSONObject().put("ok", true).put("service", "homework-logistics")
        ensureToken()
        if (method == "GET" && path == "/bootstrap") return bootstrap()
        if (method == "POST" && path == "/setup") return setup(body)
        if (method == "POST" && path == "/account/init") return initAccount()
        if (method == "POST" && path == "/class/join") return joinClass(body)
        if (method == "GET" && path == "/classes") return classes()
        if (method == "POST" && path == "/class/create") return createClass(body)
        if (method == "POST" && path == "/roster") return addRosterMember(body)
        val rosterDelete = Regex("^/roster/(\\d+)$").matchEntire(path)
        if (rosterDelete != null && method == "DELETE") return removeRosterMember(rosterDelete.groupValues[1])
        if (method == "POST" && path == "/class/dissolve") return dissolveClass()
        if (method == "POST" && path == "/class/leave") return leaveClass()
        if (method == "POST" && path == "/account/delete") return deleteAccount()
        if (method == "GET" && path == "/me") return JSONObject().put("user", toUser(requireProfile()))
        if (method == "GET" && path == "/subjects") return subjects()
        if (method == "POST" && path == "/subjects") return createSubject(body)
        val subject = Regex("^/subjects/([^/]+)$").matchEntire(path)
        if (subject != null && method == "DELETE") return deleteSubject(decode(subject.groupValues[1]))
        if (method == "GET" && path == "/members") return members()
        if (method == "POST" && path == "/members") throw IllegalStateException("同学需要使用自己的账号登录并填写学号")
        val member = Regex("^/members/([^/]+)$").matchEntire(path)
        if (member != null && method == "PATCH") return patchMember(decode(member.groupValues[1]), body)
        if (member != null && method == "DELETE") return deleteMember(decode(member.groupValues[1]))
        val seatTransfer = Regex("^/seats/([^/]+)/transfer$").matchEntire(path)
        if (seatTransfer != null && method == "POST") return transferMonitor(seatTransfer.groupValues[1].toLongOrNull() ?: 0L, body)
        val seatEdit = Regex("^/seats/([^/]+)$").matchEntire(path)
        if (seatEdit != null && method == "PATCH") return updateSeat(seatEdit.groupValues[1].toLongOrNull() ?: 0L, body)
        if (method == "GET" && path == "/assignments") return assignments()
        if (method == "POST" && path == "/assignments") return createAssignment(body)
        if (method == "GET" && path == "/messages") return messages()
        if (method == "GET" && path == "/chat/members") return chatMembers()
        if (method == "GET" && path == "/chat/threads") return chatThreads()
        if (method == "POST" && path == "/chat/threads") return openChatThread(body)
        if (method == "POST" && path == "/chat/system") return postSystemMessage(body)
        val chatThreadCall = Regex("^/chat/threads/([^/]+)/messages$").matchEntire(path)
        if (chatThreadCall != null && method == "GET") return listChatMessages(decode(chatThreadCall.groupValues[1]))
        if (chatThreadCall != null && method == "POST") return sendChatMessage(decode(chatThreadCall.groupValues[1]), body)
        val chatImage = Regex("^/chat/messages/([^/]+)/images$").matchEntire(path)
        if (chatImage != null && method == "POST") return uploadChatImage(decode(chatImage.groupValues[1]), body)
        val chatRevoke = Regex("^/chat/messages/([^/]+)/revoke$").matchEntire(path)
        if (chatRevoke != null && method == "POST") return revokeChatMessage(decode(chatRevoke.groupValues[1]))
        val chatDelete = Regex("^/chat/messages/([^/]+)$").matchEntire(path)
        if (chatDelete != null && method == "DELETE") return deleteChatMessage(decode(chatDelete.groupValues[1]))
        val chatThreadDelete = Regex("^/chat/threads/([^/]+)$").matchEntire(path)
        if (chatThreadDelete != null && method == "DELETE") return deleteChatThread(decode(chatThreadDelete.groupValues[1]))
        val assignment = Regex("^/assignments/([^/]+)$").matchEntire(path)
        if (assignment != null && method == "GET") return assignmentDetail(decode(assignment.groupValues[1]))
        if (assignment != null && method == "PATCH") return updateAssignment(decode(assignment.groupValues[1]), body)
        if (assignment != null && method == "DELETE") return deleteAssignment(decode(assignment.groupValues[1]))
        val images = Regex("^/assignments/([^/]+)/images$").matchEntire(path)
        if (images != null && method == "POST") return uploadAssignmentImage(decode(images.groupValues[1]), body)
        val comments = Regex("^/assignments/([^/]+)/comments$").matchEntire(path)
        if (comments != null && method == "GET") return listComments(decode(comments.groupValues[1]))
        if (comments != null && method == "POST") return createComment(decode(comments.groupValues[1]), body)
        val commentImage = Regex("^/comments/([^/]+)/images$").matchEntire(path)
        if (commentImage != null && method == "POST") return uploadCommentImage(decode(commentImage.groupValues[1]), body)
        val comment = Regex("^/comments/([^/]+)$").matchEntire(path)
        if (comment != null && method == "DELETE") return deleteComment(decode(comment.groupValues[1]))
        val mine = Regex("^/assignments/([^/]+)/mine$").matchEntire(path)
        if (mine != null && method == "GET") return mine(decode(mine.groupValues[1]))
        val board = Regex("^/assignments/([^/]+)/submissions$").matchEntire(path)
        if (board != null && method == "GET") return board(decode(board.groupValues[1]))
        val classStep = Regex("^/assignments/([^/]+)/(retrieve|distribute)$").matchEntire(path)
        if (classStep != null && method == "POST") {
            val forward = classStep.groupValues[2] == "retrieve"
            return shiftClass(
                decode(classStep.groupValues[1]),
                if (forward) "collected_by_monitor" else "retrieved_by_monitor",
                if (forward) "retrieved_by_monitor" else "distributed",
            )
        }
        if (method == "POST" && path == "/me/avatar") return avatar(body)
        val move = Regex("^/submissions/([^/]+)/(advance|revert|collect)$").matchEntire(path)
        if (move != null && method == "POST") return move(decode(move.groupValues[1]), move.groupValues[2], body)
        throw IllegalStateException("没有这个功能")
    }

    private fun bootstrap(): JSONObject {
        if (CloudSession.accessToken.isBlank()) return JSONObject().put("needsSetup", false).put("hasClass", false)
        val profile = rows("profiles", "select=*&owner_id=eq.${enc(CloudSession.uid)}").firstOrNull()
        val classInfo = profile?.let {
            rows("class_info", "select=id,name,code&id=eq.${num(it, "class_id")}").firstOrNull()
        }
        return JSONObject()
            .put("needsSetup", profile == null)
            .put("hasClass", classInfo != null)
            .put("className", classInfo?.optString("name") ?: "")
            .put("classCode", classInfo?.optString("code") ?: "")
    }

    /**
     * 建班 / 加入班：填了班级名就按名字找，找不到就建一个新班（创建者当学委），
     * 找到了就以同学身份加入。
     */
    private fun setup(body: JSONObject?): JSONObject {
        if (CloudSession.uid.isBlank()) throw IllegalStateException("请先登录")
        val className = body?.optString("className").orEmpty().trim()
        val course = body?.optString("course").orEmpty().trim()
        val name = body?.optString("name").orEmpty().trim()
        val studentNo = body?.optString("studentNo").orEmpty().trim()
        if (className.isBlank()) throw IllegalStateException("请填写班级")
        if (name.isBlank()) throw IllegalStateException("请填写姓名")
        if (studentNo.isBlank()) throw IllegalStateException("请填写学号")

        var klass = rows("class_info", "select=*&name=eq.${enc(className)}").firstOrNull()
        val creating = klass == null
        if (klass == null) {
            klass = write(
                "POST",
                "class_info",
                "",
                JSONObject().put("name", className).put("course", course).put("owner_id", CloudSession.uid),
            ).firstOrNull() ?: throw IllegalStateException("没有权限创建班级")
        }
        val classId = num(klass, "id")
        val created = write(
            "POST",
            "profiles",
            "",
            JSONObject()
                .put("owner_id", CloudSession.uid)
                .put("class_id", classId)
                .put("name", name)
                .put("student_no", studentNo)
                .put("role", if (creating) "monitor" else "student"),
        )
        if (created.isEmpty()) throw IllegalStateException("没有权限建立档案")
        return JSONObject().put("user", toUser(created[0]))
    }

    /** 登录后建一个「未加入班级」的档案，这样能先改资料、看知识库 */
    private fun initAccount(): JSONObject {
        if (CloudSession.uid.isBlank()) throw IllegalStateException("请先登录")
        val existing = rows("profiles", "select=*&owner_id=eq.${enc(CloudSession.uid)}").firstOrNull()
        if (existing != null) return JSONObject().put("user", toUser(existing))
        val created = write(
            "POST",
            "profiles",
            "",
            JSONObject()
                .put("owner_id", CloudSession.uid)
                .put("class_id", JSONObject.NULL)
                .put("name", "")
                .put("student_no", "")
                .put("role", "student"),
        )
        if (created.isEmpty()) throw IllegalStateException("建立账号失败")
        return JSONObject().put("user", toUser(created[0]))
    }

    /** 加入班级页的下拉菜单：所有班级名（class_info 的 SELECT 是公开的） */
    private fun classes(): JSONObject {
        val array = JSONArray()
        for (row in rows("class_info", "select=id,name&order=name.asc")) {
            array.put(JSONObject().put("id", num(row, "id")).put("name", row.optString("name")))
        }
        return JSONObject().put("classes", array)
    }

    /** 建一个新班，创建者直接是学委：一条 RPC 里把班级、档案、学委席位一起写好 */
    private fun createClass(body: JSONObject?): JSONObject {
        if (CloudSession.uid.isBlank()) throw IllegalStateException("请先登录")
        val klass = body?.optString("className").orEmpty().trim()
        val course = body?.optString("course").orEmpty().trim()
        val name = body?.optString("name").orEmpty().trim()
        val no = body?.optString("studentNo").orEmpty().trim()
        if (klass.isBlank()) throw IllegalStateException("请填写班级名")
        if (name.isBlank()) throw IllegalStateException("请填写姓名")
        if (no.isBlank()) throw IllegalStateException("请填写学号")
        write(
            "POST",
            "rpc/create_class",
            "",
            JSONObject()
                .put("klass_name", klass)
                .put("klass_course", course)
                .put("member_name", name)
                .put("member_no", no),
        )
        return JSONObject().put("ok", true)
    }

    /** 凭「班级名 + 姓名 + 学号」加入班级，必须在学委录入的名单里 */
    private fun joinClass(body: JSONObject?): JSONObject {
        requireProfile()
        val klass = body?.optString("className").orEmpty().trim()
        val name = body?.optString("name").orEmpty().trim()
        val no = body?.optString("studentNo").orEmpty().trim()
        if (klass.isBlank()) throw IllegalStateException("请填写班级")
        if (name.isBlank()) throw IllegalStateException("请填写姓名")
        if (no.isBlank()) throw IllegalStateException("请填写学号")
        write(
            "POST",
            "rpc/join_class",
            "",
            JSONObject().put("klass_name", klass).put("member_name", name).put("member_no", no),
        )
        return JSONObject().put("ok", true)
    }

    /** 学委把一名同学录进名单（对方还没有账号） */
    private fun addRosterMember(body: JSONObject?): JSONObject {
        val me = requireProfile()
        if (me.optString("role") != "monitor") throw IllegalStateException("只有学委可以管理名单")
        val name = body?.optString("name").orEmpty().trim()
        val no = body?.optString("studentNo").orEmpty().trim()
        if (name.isBlank()) throw IllegalStateException("请填写姓名")
        if (no.isBlank()) throw IllegalStateException("请填写学号")
        if (rows("class_roster", "select=id&student_no=eq.${enc(no)}").isNotEmpty()) {
            throw IllegalStateException("这个学号已经在名单里了")
        }
        val created = write(
            "POST",
            "class_roster",
            "",
            JSONObject().put("name", name).put("student_no", no),
        )
        if (created.isEmpty()) throw IllegalStateException("没有权限添加")
        // 给这个班的每份作业建一条占位进度，这样统计里能直接标记状态
        val rosterId = num(created[0], "id")
        val classId = me.optLong("classId")
        if (classId > 0) {
            for (a in rows("assignments", "select=id&class_id=eq.$classId")) {
                write(
                    "POST",
                    "submissions",
                    "",
                    JSONObject()
                        .put("assignment_id", num(a, "id"))
                        .put("student_ref", "roster:$rosterId")
                        .put("class_id", classId),
                )
            }
        }
        return JSONObject().put("ok", true)
    }

    private fun removeRosterMember(id: String): JSONObject {
        val me = requireProfile()
        if (me.optString("role") != "monitor") throw IllegalStateException("只有学委可以管理名单")
        runCatching { write("DELETE", "submissions", "student_ref=eq.roster:$id", null) }
        write("DELETE", "class_roster", "id=eq.$id", null)
        return JSONObject().put("ok", true)
    }

    /** 学委解散自己的班（数据库函数里有"只有一人时才允许"的校验） */
    private fun dissolveClass(): JSONObject {
        val me = requireProfile()
        if (me.optString("role") != "monitor") throw IllegalStateException("只有学委可以解散班级")
        write("POST", "rpc/dissolve_class", "", JSONObject())
        return JSONObject().put("ok", true)
    }

    /**
     * 退出班级：只断开账号与班级席位的连接。
     * 名单席位恢复成「未认领」（学委席位保留学委身份），已交的作业进度交还给席位、仍归学委管理，
     * 姓名学号留在档案里，凭它们随时可以加回来。
     */
    private fun leaveClass(): JSONObject {
        val me = requireProfile()
        if (me.isNull("class_id")) throw IllegalStateException("你还没有加入班级")
        write("POST", "rpc/leave_class", "", JSONObject())
        return JSONObject().put("ok", true)
    }

    /** 注销账号：删除个人档案（姓名、学号、头像），席位和已交的进度留给学委 */
    private fun deleteAccount(): JSONObject {
        val me = requireProfile()
        val avatar = me.optString("avatar_path")
        write("POST", "rpc/delete_account", "", JSONObject())
        if (avatar.isNotBlank() && avatar != "null") runCatching { removeObjects(listOf(avatar)) }
        return JSONObject().put("ok", true)
    }

    private fun requireProfile(): JSONObject {
        if (CloudSession.uid.isBlank()) throw IllegalStateException("请先登录")
        return rows("profiles", "select=*&owner_id=eq.${enc(CloudSession.uid)}").firstOrNull()
            ?: throw IllegalStateException("请先填写学号")
    }

    private fun subjects(): JSONObject {
        requireProfile()
        val list = rows("subjects", "select=id,name&order=name.asc")
        val array = JSONArray()
        for (row in list) array.put(JSONObject().put("id", num(row, "id")).put("name", row.optString("name")))
        return JSONObject().put("subjects", array)
    }

    private fun createSubject(body: JSONObject?): JSONObject {
        val me = requireProfile()
        if (me.optString("role") != "monitor") throw IllegalStateException("只有学习委员可以管理科目")
        val name = body?.optString("name").orEmpty().trim().ifBlank { throw IllegalStateException("请填写科目") }
        if (rows("subjects", "select=id&name=eq.${enc(name)}").isNotEmpty()) throw IllegalStateException("这个科目已经有了")
        val created = write("POST", "subjects", "", JSONObject().put("name", name))
        if (created.isEmpty()) throw IllegalStateException("没有权限添加科目")
        return JSONObject().put("id", num(created[0], "id")).put("name", created[0].optString("name"))
    }

    private fun deleteSubject(id: String): JSONObject {
        val me = requireProfile()
        if (me.optString("role") != "monitor") throw IllegalStateException("只有学习委员可以管理科目")
        val removed = write("DELETE", "subjects", "id=eq.$id", null)
        if (removed.isEmpty()) throw IllegalStateException("没有权限或没有这个科目")
        return JSONObject().put("ok", true)
    }

    private fun weekText(week: String): String {
        val text = week.trim()
        return if (text.all { it.isDigit() }) "第${text}周" else text
    }

    /**
     * 成员管理＝席位管理：名单以 class_roster 为准，一条席位对应一个人。
     * 已认领的进 members（附带档案里的头像），没认领的进 pending（「未认领的席位」）。
     * 返回结构和老版本一致，老包读 members/pending 不会挂。
     */
    private fun members(): JSONObject {
        val me = requireProfile()
        if (me.optString("role") != "monitor") throw IllegalStateException("只有学习委员可以看成员")
        val people = rows("profiles", "select=*").associateBy { it.optString("owner_id") }
        val claimed = mutableListOf<JSONObject>()
        val pending = JSONArray()
        for (seat in rows("class_roster", "select=*&order=id.asc")) {
            val who = if (seat.isNull("claimed_by")) "" else seat.optString("claimed_by")
            if (who.isBlank()) {
                pending.put(
                    JSONObject()
                        .put("id", num(seat, "id"))
                        .put("name", seat.optString("name"))
                        .put("studentNo", seat.optString("student_no"))
                        .put("role", seat.optString("role").ifBlank { "student" }),
                )
                continue
            }
            val person = people[who]
            val role = seat.optString("role").ifBlank { person?.optString("role").orEmpty().ifBlank { "student" } }
            if (person != null && person.optString("role") == "ta") continue
            claimed.add(
                JSONObject()
                    .put("id", who)
                    .put("seatId", num(seat, "id"))
                    .put("name", seat.optString("name").ifBlank { person?.optString("name").orEmpty() })
                    .put("studentNo", seat.optString("student_no").ifBlank { person?.optString("student_no").orEmpty() })
                    .put("role", role)
                    .put("avatar", person?.let { if (it.isNull("avatar_path")) "" else it.optString("avatar_path") } ?: "")
                    .put("self", who == CloudSession.uid),
            )
        }
        // 学委排最前，其余按席位顺序（＝加入顺序）
        claimed.sortWith(compareBy({ if (it.optString("role") == "monitor") 0 else 1 }, { it.optLong("seatId") }))
        val array = JSONArray()
        for (row in claimed) array.put(row)
        return JSONObject().put("members", array).put("pending", pending)
    }

    private fun patchMember(id: String, body: JSONObject?): JSONObject {
        val me = requireProfile()
        val target = rows("profiles", "select=*&owner_id=eq.${enc(id)}").firstOrNull() ?: throw IllegalStateException("没有这个成员")
        val self = id == me.optString("owner_id")
        if (!self && me.optString("role") != "monitor") throw IllegalStateException("只能修改自己的资料")
        if (!self && target.optString("role") == "monitor") throw IllegalStateException("不能修改其他学委")
        val name = body?.optString("name").orEmpty().trim().ifBlank { throw IllegalStateException("请填写姓名") }
        val studentNo = body?.optString("studentNo").orEmpty().trim().ifBlank { throw IllegalStateException("请填写学号") }
        val updated = write("PATCH", "profiles", "owner_id=eq.${enc(id)}", JSONObject().put("name", name).put("student_no", studentNo))
        if (updated.isEmpty()) throw IllegalStateException("没有权限修改资料")
        return JSONObject().put("user", toUser(updated[0]))
    }

    private fun deleteMember(id: String): JSONObject {
        val me = requireProfile()
        if (me.optString("role") != "monitor") throw IllegalStateException("只有学习委员可以删除成员")
        if (id == me.optString("owner_id")) throw IllegalStateException("不能删除自己")
        val target = rows("profiles", "select=*&owner_id=eq.${enc(id)}").firstOrNull() ?: throw IllegalStateException("没有这个成员")
        if (target.optString("role") == "monitor") throw IllegalStateException("不能删除学委")
        val removed = write("DELETE", "profiles", "owner_id=eq.${enc(id)}", null)
        if (removed.isEmpty()) throw IllegalStateException("没有权限删除成员")
        write("DELETE", "submissions", "student_ref=eq.${enc(id)}", null)
        // 席位不删、只解开绑定：还回「未认领的席位」，对方凭姓名学号能自己加回来。
        // 想彻底不要这个人，再到「未认领的席位」里移除。
        write("PATCH", "class_roster", "claimed_by=eq.${enc(id)}", JSONObject().put("claimed_by", JSONObject.NULL))
        val avatar = target.optString("avatar_path")
        if (avatar.isNotBlank()) runCatching { removeObjects(listOf(avatar)) }
        return JSONObject().put("ok", true)
    }

    /** 学委编辑席位：改席位上的姓名/学号；已认领的席位，那个账号的档案也跟着改 */
    private fun updateSeat(seatId: Long, body: JSONObject?): JSONObject {
        val me = requireProfile()
        if (me.optString("role") != "monitor") throw IllegalStateException("只有学习委员可以编辑席位")
        if (seatId <= 0) throw IllegalStateException("没有这个席位")
        val seat = rows("class_roster", "select=*&id=eq.$seatId").firstOrNull()
            ?: throw IllegalStateException("没有这个席位")
        val name = body?.optString("name").orEmpty().trim().ifBlank { throw IllegalStateException("请填写姓名") }
        val no = body?.optString("studentNo").orEmpty().trim().ifBlank { throw IllegalStateException("请填写学号") }
        write("PATCH", "class_roster", "id=eq.$seatId", JSONObject().put("name", name).put("student_no", no))
        val who = if (seat.isNull("claimed_by")) "" else seat.optString("claimed_by")
        if (who.isNotBlank()) {
            // 认领了这个席位的账号一起改，免得个人页还是旧名字
            write("PATCH", "profiles", "owner_id=eq.${enc(who)}", JSONObject().put("name", name).put("student_no", no))
        }
        return JSONObject().put("ok", true)
    }

    /**
     * 转让学委：按**席位**转，不按账号。可以转给还没绑手机的空席位——
     * 学委身份先落在席位上，那个人加入认领席位时自动成为学委。
     */
    private fun transferMonitor(seatId: Long, body: JSONObject?): JSONObject {
        val me = requireProfile()
        if (me.optString("role") != "monitor") throw IllegalStateException("只有学习委员可以转让")
        if (body?.optString("phrase") != "我已确认转让") throw IllegalStateException("请输入“我已确认转让”")
        if (seatId <= 0) throw IllegalStateException("没有这个席位")
        write("POST", "rpc/transfer_monitor", "", JSONObject().put("seat_id", seatId))
        val refreshed = rows("profiles", "select=*&owner_id=eq.${enc(CloudSession.uid)}").firstOrNull()
            ?: throw IllegalStateException("转让后读不到档案，请重进")
        return JSONObject().put("user", toUser(refreshed))
    }

    private fun assignments(): JSONObject {
        val me = requireProfile()
        val items = rows("assignments", "select=*&order=id.desc")
        val profiles = rows("profiles", "select=owner_id,name")
        val names = profiles.associate { it.optString("owner_id") to it.optString("name") }
        // 发布人按席位署名：人退班了名字也还在
        val seats = rows("class_roster", "select=id,name").associate { num(it, "id") to it.optString("name") }
        val subs = rows("submissions", "select=assignment_id,status,student_ref")
        val array = JSONArray()
        for (row in items) {
            val id = num(row, "id")
            val related = subs.filter { num(it, "assignment_id") == id }
            val mine = related.find { it.optString("student_ref") == me.optString("owner_id") }
            val mode = row.optString("mode").ifBlank { "collect" }
            val mineStatus = mine?.optString("status").orEmpty()
            val publisher = if (row.isNull("publisher_seat_id")) "" else seats[num(row, "publisher_seat_id")].orEmpty()
            array.put(
                JSONObject()
                    .put("id", id)
                    .put("title", row.optString("title"))
                    .put("description", row.optString("description"))
                    .put("week", row.optString("week"))
                    .put("deadline", row.optString("deadline"))
                    .put("mode", mode)
                    .put("createdAt", row.optString("created_at"))
                    .put("publisherName", publisher.ifBlank { names[row.optString("owner_id")] ?: "" })
                    .put("progress", progress(related, mode, mineStatus)),
            )
        }
        return JSONObject().put("assignments", array)
    }

    private fun progress(rows: List<JSONObject>, mode: String, mineStatus: String): JSONObject {
        val counts = rows.groupingBy { it.optString("status") }.eachCount()
        val collected = counts["collected_by_monitor"] ?: 0
        val ready = counts["ready_to_submit"] ?: 0
        val notSubmitted = counts["not_submitted"] ?: 0
        return JSONObject()
            .put("total", rows.size)
            .put("handedIn", if (mode == "collect") ready + collected else rows.size - notSubmitted)
            .put("distributed", counts["distributed"] ?: 0)
            .put("collected", collected)
            .put("pending", (counts["submitted_to_monitor"] ?: 0) + (counts["received_by_ta"] ?: 0) + (counts["graded"] ?: 0))
            .put("mineStatus", mineStatus)
            .put("mineStatusLabel", if (mineStatus.isBlank()) JSONObject.NULL else Domain.label(mineStatus, mode))
    }

    private fun createAssignment(body: JSONObject?): JSONObject {
        val me = requireProfile()
        if (me.optString("role") != "monitor") throw IllegalStateException("只有学习委员可以发布作业")
        val title = body?.optString("title").orEmpty().trim().ifBlank { throw IllegalStateException("请选择科目") }
        val week = body?.optString("week").orEmpty().trim().ifBlank { throw IllegalStateException("请填写周次") }
        val description = body?.optString("description").orEmpty().trim()
        val created = write(
            "POST",
            "assignments",
            "",
            JSONObject()
                .put("title", title)
                .put("description", description)
                .put("week", week)
                .put("deadline", deadline(body?.optString("deadline")))
                .put("mode", "collect"),
        )
        if (created.isEmpty()) throw IllegalStateException("没有权限发布作业")
        val id = num(created[0], "id")
        val people = rows("profiles", "select=owner_id,role")
        for (person in people) {
            val role = person.optString("role")
            if (role != "student" && role != "monitor") continue
            val submission = write(
                "POST",
                "submissions",
                "",
                JSONObject().put("assignment_id", id).put("student_ref", person.optString("owner_id")).put("status", "not_submitted"),
            )
            if (submission.isEmpty()) throw IllegalStateException("没有权限建立进度")
            val event = write("POST", "events", "", JSONObject().put("submission_id", num(submission[0], "id")).put("status", "not_submitted").put("note", ""))
            if (event.isEmpty()) throw IllegalStateException("没有权限记录进度")
        }
        // 名单里还没加入 App 的人也要有进度，学委才能替他们标状态
        for (person in rows("class_roster", "select=id&claimed_by=is.null")) {
            val submission = write(
                "POST",
                "submissions",
                "",
                JSONObject()
                    .put("assignment_id", id)
                    .put("student_ref", "roster:${num(person, "id")}")
                    .put("status", "not_submitted"),
            )
            if (submission.isEmpty()) throw IllegalStateException("没有权限建立进度")
            write("POST", "events", "", JSONObject().put("submission_id", num(submission[0], "id")).put("status", "not_submitted").put("note", ""))
        }
        val message = write(
            "POST",
            "messages",
            "",
            JSONObject().put("kind", "assignment").put("title", title).put("body", "${me.optString("name")}发布了${weekText(week)}的作业").put("assignment_id", id),
        )
        if (message.isEmpty()) throw IllegalStateException("没有权限发布消息")
        runCatching {
            val system = rows("chat_threads", "select=id&kind=eq.system&limit=1").firstOrNull()
            if (system != null) {
                write(
                    "POST",
                    "chat_messages",
                    "",
                    JSONObject()
                        .put("thread_id", num(system, "id"))
                        .put("body", "${me.optString("name")} 发布了${weekText(week)}的作业：$title"),
                )
            }
        }
        return JSONObject().put("id", id).put("messageId", num(message[0], "id"))
    }

    private fun messages(): JSONObject {
        requireProfile()
        val rows = rows("messages", "select=*&order=id.desc&limit=50")
        val array = JSONArray()
        for (row in rows) {
            array.put(
                JSONObject()
                    .put("id", num(row, "id"))
                    .put("kind", row.optString("kind"))
                    .put("title", row.optString("title"))
                    .put("body", row.optString("body"))
                    .put("assignmentId", if (row.isNull("assignment_id")) JSONObject.NULL else num(row, "assignment_id"))
                    .put("createdAt", row.optString("created_at")),
            )
        }
        return JSONObject().put("messages", array)
    }

    // ---------- 班级空间 / 会话 ----------

    /**
     * 班级空间的名单：按**席位**列，空席位也显示出来，这样能看出学委职位挂在哪个席位上。
     * members 只放已认领的（老版本 App 读这个字段，行为不变），seats 放全部。
     */
    private fun chatMembers(): JSONObject {
        val me = requireProfile()
        val people = rows("profiles", "select=*").associateBy { it.optString("owner_id") }
        val members = JSONArray()
        val seats = JSONArray()
        val claimed = mutableListOf<JSONObject>()
        for (seat in rows("class_roster", "select=*&order=id.asc")) {
            val who = if (seat.isNull("claimed_by")) "" else seat.optString("claimed_by")
            val person = if (who.isBlank()) null else people[who]
            if (person != null && person.optString("role") == "ta") continue
            val name = seat.optString("name").ifBlank { person?.optString("name").orEmpty() }
            val role = seat.optString("role").ifBlank { "student" }
            val base = JSONObject()
                .put("seatId", num(seat, "id"))
                .put("id", who)
                .put("name", name)
                .put("studentNo", seat.optString("student_no").ifBlank { person?.optString("student_no").orEmpty() })
                .put("role", role)
                .put("claimed", who.isNotBlank() && person != null)
                .put("self", who.isNotBlank() && who == CloudSession.uid)
                .put("avatar", if (person == null || person.isNull("avatar_path")) "" else person.optString("avatar_path"))
            seats.put(base)
            if (who.isNotBlank() && person != null) {
                claimed.add(
                    JSONObject()
                        .put("id", who)
                        .put("seatId", num(seat, "id"))
                        .put("name", name)
                        .put("studentNo", base.optString("studentNo"))
                        .put("role", role)
                        .put("avatar", base.optString("avatar"))
                        .put("self", who == CloudSession.uid),
                )
            }
        }
        claimed.sortWith(compareBy({ if (it.optString("role") == "monitor") 0 else 1 }, { it.optLong("seatId") }))
        for (row in claimed) members.put(row)
        return JSONObject().put("members", members).put("seats", seats)
    }

    private fun chatPeerId(thread: JSONObject, myId: String): String {
        val a = thread.optString("member_a")
        return if (a == myId) thread.optString("member_b") else a
    }

    private fun chatThreads(): JSONObject {
        val me = requireProfile()
        val myId = me.optString("owner_id")
        val threads = rows("chat_threads", "select=*&order=id.asc")
        val people = rows("profiles", "select=owner_id,name,avatar_path")
            .associateBy { it.optString("owner_id") }
        // 一次拉最近消息再按会话分组，避免每个会话单独请求（原来 N+1 次）
        val latest = rows("chat_messages", "select=id,thread_id,body,created_at,revoked_at&order=id.desc&limit=300")
            .groupBy { num(it, "thread_id") }
            .mapValues { it.value.first() }
        val array = JSONArray()
        for (thread in threads) {
            val id = num(thread, "id")
            val last = latest[id]
            val lastBody = when {
                last == null -> ""
                !last.isNull("revoked_at") -> "撤回了一条消息"
                else -> last.optString("body")
            }
            val isSystem = thread.optString("kind") == "system"
            val peer = if (isSystem) "" else chatPeerId(thread, myId)
            val person = people[peer]
            array.put(
                JSONObject()
                    .put("id", id)
                    .put("kind", thread.optString("kind"))
                    .put("title", if (isSystem) thread.optString("title").ifBlank { "系统通知" } else person?.optString("name") ?: "已注销的同学")
                    .put("peerId", peer)
                    .put("peerAvatar", person?.optString("avatar_path") ?: "")
                    .put("lastBody", lastBody)
                    .put("lastAt", last?.optString("created_at") ?: thread.optString("created_at")),
            )
        }
        return JSONObject().put("threads", array)
    }

    private fun openChatThread(body: JSONObject?): JSONObject {
        val me = requireProfile()
        val myId = me.optString("owner_id")
        val peerId = body?.optString("peerId").orEmpty().trim()
        if (peerId.isBlank()) throw IllegalStateException("缺少对方")
        if (peerId == myId) throw IllegalStateException("不能和自己建会话")
        val peer = rows("profiles", "select=*&owner_id=eq.${enc(peerId)}").firstOrNull()
            ?: throw IllegalStateException("没有这个成员")
        val a = if (myId < peerId) myId else peerId
        val b = if (myId < peerId) peerId else myId
        val existing = rows("chat_threads", "select=*&member_a=eq.${enc(a)}&member_b=eq.${enc(b)}").firstOrNull()
        val created = existing ?: write(
            "POST",
            "chat_threads",
            "",
            JSONObject().put("kind", "direct").put("member_a", a).put("member_b", b).put("title", peer.optString("name")),
        ).firstOrNull() ?: throw IllegalStateException("没有权限创建会话")
        return JSONObject().put(
            "thread",
            JSONObject()
                .put("id", num(created, "id"))
                .put("title", peer.optString("name"))
                .put("peerId", peerId),
        )
    }

    private fun listChatMessages(id: String): JSONObject {
        val me = requireProfile()
        val myId = me.optString("owner_id")
        val thread = rows("chat_threads", "select=*&id=eq.$id").firstOrNull()
            ?: throw IllegalStateException("没有这个会话")
        val list = rows("chat_messages", "select=*&thread_id=eq.$id&order=id.asc&limit=200")
        val names = rows("profiles", "select=owner_id,name")
            .associate { it.optString("owner_id") to it.optString("name") }
        val images = if (list.isEmpty()) emptyList() else rows(
            "chat_message_images",
            "select=*&message_id=in.(${list.joinToString(",") { num(it, "id").toString() }})&order=id.asc",
        )
        val byMessage = images.groupBy { num(it, "message_id") }
        val array = JSONArray()
        for (row in list) {
            val mid = num(row, "id")
            val shots = JSONArray()
            byMessage[mid]?.forEach { shots.put(JSONObject().put("url", it.optString("storage_path"))) }
            array.put(
                JSONObject()
                    .put("id", mid)
                    .put("body", if (row.isNull("revoked_at")) row.optString("body") else "")
                    .put("revoked", !row.isNull("revoked_at"))
                    .put("mine", row.optString("owner_id") == myId)
                    .put("authorName", names[row.optString("owner_id")] ?: "")
                    .put("createdAt", row.optString("created_at"))
                    .put("images", shots),
            )
        }
        val isSystem = thread.optString("kind") == "system"
        val title = if (isSystem) thread.optString("title").ifBlank { "系统通知" }
            else names[chatPeerId(thread, myId)] ?: "会话"
        return JSONObject().put("title", title).put("kind", thread.optString("kind")).put("messages", array)
    }

    private fun sendChatMessage(id: String, body: JSONObject?): JSONObject {
        requireProfile()
        if (rows("chat_threads", "select=id&id=eq.$id").isEmpty()) throw IllegalStateException("没有这个会话")
        val text = body?.optString("body").orEmpty().trim().take(1000)
        val inserted = write(
            "POST",
            "chat_messages",
            "",
            JSONObject().put("thread_id", id.toLong()).put("body", text),
        )
        if (inserted.isEmpty()) throw IllegalStateException("没有权限发消息")
        return JSONObject().put("message", JSONObject().put("id", num(inserted[0], "id")))
    }

    /** 学委向系统会话广播一条通知 */
    private fun postSystemMessage(body: JSONObject?): JSONObject {
        val me = requireProfile()
        if (me.optString("role") != "monitor") throw IllegalStateException("只有学习委员可以发布系统消息")
        val text = body?.optString("body").orEmpty().trim()
        if (text.isBlank()) throw IllegalStateException("请填写消息内容")
        val system = rows("chat_threads", "select=id&kind=eq.system&limit=1").firstOrNull()
            ?: throw IllegalStateException("没有系统会话")
        val inserted = write(
            "POST",
            "chat_messages",
            "",
            JSONObject().put("thread_id", num(system, "id")).put("body", text.take(500)),
        )
        if (inserted.isEmpty()) throw IllegalStateException("没有权限发布系统消息")
        // 同时写一条班级消息：这张表才是触发通知栏提醒的那条链路（和发布作业一致）。
        // 只写会话的话，同学不主动点「消息」就永远看不到公告。
        runCatching {
            write(
                "POST",
                "messages",
                "",
                JSONObject().put("kind", "notice").put("title", "班级公告").put("body", text.take(500)),
            )
        }
        return JSONObject().put("ok", true)
    }

    private fun uploadChatImage(id: String, body: JSONObject?): JSONObject {
        val me = requireProfile()
        val message = rows("chat_messages", "select=id,owner_id&id=eq.$id").firstOrNull()
            ?: throw IllegalStateException("没有这条消息")
        if (message.optString("owner_id") != me.optString("owner_id")) throw IllegalStateException("只能给自己的消息加图片")
        val storagePath = upload(body)
        val inserted = write(
            "POST",
            "chat_message_images",
            "",
            JSONObject().put("message_id", id.toLong()).put("storage_path", storagePath),
        )
        if (inserted.isEmpty()) {
            runCatching { removeObjects(listOf(storagePath)) }
            throw IllegalStateException("没有权限上传图片")
        }
        return JSONObject().put("id", num(inserted[0], "id")).put("url", storagePath)
    }

    private fun revokeChatMessage(id: String): JSONObject {
        val me = requireProfile()
        val message = rows("chat_messages", "select=id,owner_id&id=eq.$id").firstOrNull()
            ?: throw IllegalStateException("没有这条消息")
        if (message.optString("owner_id") != me.optString("owner_id")) throw IllegalStateException("只能撤回自己的消息")
        val updated = write(
            "PATCH",
            "chat_messages",
            "id=eq.$id",
            JSONObject().put("revoked_at", java.time.Instant.now().toString()),
        )
        if (updated.isEmpty()) throw IllegalStateException("没有权限撤回")
        return JSONObject().put("ok", true)
    }

    private fun deleteChatMessage(id: String): JSONObject {
        val me = requireProfile()
        val message = rows("chat_messages", "select=id,owner_id&id=eq.$id").firstOrNull()
            ?: throw IllegalStateException("没有这条消息")
        if (message.optString("owner_id") != me.optString("owner_id")) throw IllegalStateException("只能删除自己的消息")
        val removed = write("DELETE", "chat_messages", "id=eq.$id&owner_id=eq.${enc(me.optString("owner_id"))}", null)
        if (removed.isEmpty()) throw IllegalStateException("没有权限删除")
        return JSONObject().put("ok", true)
    }

    private fun deleteChatThread(id: String): JSONObject {
        val me = requireProfile()
        val thread = rows("chat_threads", "select=*&id=eq.$id").firstOrNull()
            ?: throw IllegalStateException("没有这个会话")
        if (thread.optString("kind") == "system") throw IllegalStateException("系统会话不能删除")
        val myId = me.optString("owner_id")
        if (thread.optString("member_a") != myId && thread.optString("member_b") != myId) {
            throw IllegalStateException("只能删除自己的会话")
        }
        write("DELETE", "chat_threads", "id=eq.$id", null)
        return JSONObject().put("ok", true)
    }

    private fun assignmentDetail(id: String): JSONObject {
        requireProfile()
        val row = rows("assignments", "select=*&id=eq.$id").firstOrNull() ?: throw IllegalStateException("没有这次作业")
        val publisher = rows("profiles", "select=name&owner_id=eq.${enc(row.optString("owner_id"))}").firstOrNull()
        val seatName = if (row.isNull("publisher_seat_id")) "" else rows(
            "class_roster",
            "select=name&id=eq.${num(row, "publisher_seat_id")}",
        ).firstOrNull()?.optString("name").orEmpty()
        val images = rows("assignment_images", "select=id,storage_path&assignment_id=eq.$id&order=id.asc")
        val array = JSONArray()
        for (image in images) array.put(JSONObject().put("id", num(image, "id")).put("url", image.optString("storage_path")))
        return JSONObject()
            .put("id", num(row, "id"))
            .put("title", row.optString("title"))
            .put("description", row.optString("description"))
            .put("week", row.optString("week"))
            .put("deadline", row.optString("deadline"))
            .put("mode", row.optString("mode").ifBlank { "collect" })
            .put("createdAt", row.optString("created_at"))
            .put("publisherName", seatName.ifBlank { publisher?.optString("name") ?: "" })
            .put("images", array)
    }

    private fun updateAssignment(id: String, body: JSONObject?): JSONObject {
        val me = requireProfile()
        if (me.optString("role") != "monitor") throw IllegalStateException("只有学习委员可以修改作业")
        val title = body?.optString("title").orEmpty().trim().ifBlank { throw IllegalStateException("请选择科目") }
        val week = body?.optString("week").orEmpty().trim().ifBlank { throw IllegalStateException("请填写周次") }
        val updated = write(
            "PATCH",
            "assignments",
            "id=eq.$id",
            JSONObject()
                .put("title", title)
                .put("description", body?.optString("description").orEmpty().trim())
                .put("week", week)
                .put("deadline", deadline(body?.optString("deadline"))),
        )
        if (updated.isEmpty()) throw IllegalStateException("没有权限或没有这次作业")
        return JSONObject().put("ok", true)
    }

    private fun deleteAssignment(id: String): JSONObject {
        val me = requireProfile()
        if (me.optString("role") != "monitor") throw IllegalStateException("只有学习委员可以删除作业")
        val images = rows("assignment_images", "select=storage_path&assignment_id=eq.$id")
        val commentPaths = runCatching {
            val ids = rows("comments", "select=id&assignment_id=eq.$id").map { num(it, "id").toString() }
            if (ids.isEmpty()) emptyList()
            else rows("comment_images", "select=storage_path&comment_id=in.(${ids.joinToString(",")})")
                .map { it.optString("storage_path") }
                .filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
        write("DELETE", "messages", "assignment_id=eq.$id", null)
        val removed = write("DELETE", "assignments", "id=eq.$id", null)
        if (removed.isEmpty()) throw IllegalStateException("没有权限或没有这次作业")
        val paths = images.map { it.optString("storage_path") }.filter { it.isNotBlank() } + commentPaths
        if (paths.isNotEmpty()) runCatching { removeObjects(paths) }
        return JSONObject().put("ok", true)
    }

    private fun uploadAssignmentImage(id: String, body: JSONObject?): JSONObject {
        val me = requireProfile()
        if (me.optString("role") != "monitor") throw IllegalStateException("只有学习委员可以上传图片")
        if (rows("assignments", "select=id&id=eq.$id").isEmpty()) throw IllegalStateException("没有这次作业")
        if (rows("assignment_images", "select=id&assignment_id=eq.$id").size >= 6) throw IllegalStateException("一次作业最多 6 张图片")
        val storagePath = upload(body)
        val inserted = write("POST", "assignment_images", "", JSONObject().put("assignment_id", id.toLong()).put("storage_path", storagePath))
        if (inserted.isEmpty()) throw IllegalStateException("没有权限上传图片")
        val images = rows("assignment_images", "select=id,storage_path&assignment_id=eq.$id&order=id.asc")
        val array = JSONArray()
        for (image in images) array.put(JSONObject().put("id", num(image, "id")).put("url", image.optString("storage_path")))
        return JSONObject().put("images", array)
    }

    private fun listComments(id: String): JSONObject {
        requireProfile()
        if (rows("assignments", "select=id&id=eq.$id").isEmpty()) throw IllegalStateException("没有这次作业")
        val list = rows("comments", "select=*&assignment_id=eq.$id&order=created_at.asc,id.asc")
        return JSONObject().put("comments", presentComments(list))
    }

    private fun createComment(id: String, body: JSONObject?): JSONObject {
        requireProfile()
        if (rows("assignments", "select=id&id=eq.$id").isEmpty()) throw IllegalStateException("没有这次作业")
        val text = body?.optString("body").orEmpty().trim().take(500)
        val inserted = write(
            "POST",
            "comments",
            "",
            JSONObject().put("assignment_id", id.toLong()).put("body", text),
        )
        if (inserted.isEmpty()) throw IllegalStateException("没有权限发表评论")
        return JSONObject().put("comment", presentComments(inserted).getJSONObject(0))
    }

    private fun uploadCommentImage(id: String, body: JSONObject?): JSONObject {
        val me = requireProfile()
        val comment = rows("comments", "select=id,owner_id&id=eq.$id").firstOrNull()
            ?: throw IllegalStateException("没有这条评论")
        if (comment.optString("owner_id") != me.optString("owner_id")) throw IllegalStateException("只能给自己的评论加图片")
        if (rows("comment_images", "select=id&comment_id=eq.$id").size >= 4) throw IllegalStateException("一条评论最多 4 张图片")
        val storagePath = upload(body)
        val inserted = write(
            "POST",
            "comment_images",
            "",
            JSONObject().put("comment_id", id.toLong()).put("storage_path", storagePath),
        )
        if (inserted.isEmpty()) {
            runCatching { removeObjects(listOf(storagePath)) }
            throw IllegalStateException("没有权限上传图片")
        }
        return JSONObject().put("id", num(inserted[0], "id")).put("url", storagePath)
    }

    private fun deleteComment(id: String): JSONObject {
        val me = requireProfile()
        val comment = rows("comments", "select=id,owner_id&id=eq.$id").firstOrNull()
            ?: throw IllegalStateException("没有这条评论")
        val mine = comment.optString("owner_id") == me.optString("owner_id")
        if (!mine && me.optString("role") != "monitor") throw IllegalStateException("只能删除自己的评论")
        val images = rows("comment_images", "select=storage_path&comment_id=eq.$id")
        val removed = write("DELETE", "comments", "id=eq.$id", null)
        if (removed.isEmpty()) throw IllegalStateException("没有权限删除评论")
        val paths = images.map { it.optString("storage_path") }.filter { it.isNotBlank() }
        if (paths.isNotEmpty()) runCatching { removeObjects(paths) }
        return JSONObject().put("ok", true)
    }

    private fun presentComments(list: List<JSONObject>): JSONArray {
        val array = JSONArray()
        if (list.isEmpty()) return array
        val ids = list.joinToString(",") { num(it, "id").toString() }
        val owners = list.map { it.optString("owner_id") }.filter { it.isNotBlank() }.distinct()
        val profiles = if (owners.isEmpty()) emptyList() else rows(
            "profiles",
            "select=owner_id,name,role,avatar_path&owner_id=in.(${owners.joinToString(",")})",
        )
        // 署名按席位：作者退班之后，评论仍然显示真名
        val seatIds = list.filter { !it.isNull("seat_id") }.map { num(it, "seat_id") }.distinct()
        val seats = if (seatIds.isEmpty()) emptyMap() else rows(
            "class_roster",
            "select=id,name,role&id=in.(${seatIds.joinToString(",")})",
        ).associateBy { num(it, "id") }
        val images = rows("comment_images", "select=id,comment_id,storage_path&comment_id=in.($ids)&order=id.asc")
        val people = profiles.associateBy { it.optString("owner_id") }
        for (row in list) {
            val person = people[row.optString("owner_id")]
            val seat = if (row.isNull("seat_id")) null else seats[num(row, "seat_id")]
            val shots = JSONArray()
            for (image in images) {
                if (num(image, "comment_id") != num(row, "id")) continue
                shots.put(JSONObject().put("id", num(image, "id")).put("url", image.optString("storage_path")))
            }
            val name = seat?.optString("name").orEmpty().ifBlank { person?.optString("name").orEmpty() }
            val author = if (name.isBlank()) {
                JSONObject().put("id", row.optString("owner_id")).put("name", "已注销的同学").put("role", "").put("avatar", "")
            } else {
                JSONObject()
                    .put("id", person?.optString("owner_id") ?: row.optString("owner_id"))
                    .put("name", name)
                    .put("role", seat?.optString("role").orEmpty().ifBlank { person?.optString("role").orEmpty() })
                    .put("avatar", if (person == null || person.isNull("avatar_path")) "" else person.optString("avatar_path"))
            }
            array.put(
                JSONObject()
                    .put("id", num(row, "id"))
                    .put("body", row.optString("body"))
                    .put("createdAt", row.optString("created_at"))
                    .put("author", author)
                    .put("images", shots),
            )
        }
        return array
    }

    private fun mine(id: String): JSONObject {
        val me = requireProfile()
        val row = rows("submissions", "select=*&assignment_id=eq.$id&student_ref=eq.${enc(me.optString("owner_id"))}").firstOrNull()
            ?: throw IllegalStateException("这次作业没有你的进度")
        return JSONObject().put("submission", present(row, me, modeOf(id)))
    }

    private fun board(id: String): JSONObject {
        val me = requireProfile()
        if (me.optString("role") == "student") throw IllegalStateException("只能看自己的进度")
        val mode = modeOf(id)
        fillMissingSeatSubmissions(id)
        val rows = rows("submissions", "select=*&assignment_id=eq.$id").sortedBy { it.optString("student_ref") }
        val array = JSONArray()
        for (row in rows) array.put(present(row, me, mode))
        return JSONObject().put("submissions", array)
    }

    /**
     * 作业管理按**席位**列人：每个席位都该有一条进度行。
     * 席位可能是批量导入或系统补建的，那时没人给它建进度，这里补上，保证管理页不会漏人。
     */
    private fun fillMissingSeatSubmissions(assignmentId: String) {
        val assignment = rows("assignments", "select=id,class_id,owner_id&id=eq.$assignmentId").firstOrNull() ?: return
        val classId = num(assignment, "class_id")
        if (classId <= 0) return
        val have = rows("submissions", "select=student_ref&assignment_id=eq.$assignmentId")
            .map { it.optString("student_ref") }
            .toHashSet()
        for (seat in rows("class_roster", "select=id,claimed_by&class_id=eq.$classId")) {
            val ref = if (seat.isNull("claimed_by")) "roster:" + num(seat, "id") else seat.optString("claimed_by")
            if (ref.isBlank() || have.contains(ref)) continue
            write(
                "POST",
                "submissions",
                "",
                JSONObject()
                    .put("assignment_id", num(assignment, "id"))
                    .put("student_ref", ref)
                    .put("status", "not_submitted")
                    .put("class_id", classId)
                    .put("owner_id", assignment.optString("owner_id")),
            )
            have.add(ref)
        }
    }

    private fun avatar(body: JSONObject?): JSONObject {
        val me = requireProfile()
        val storagePath = upload(body)
        val old = me.optString("avatar_path")
        if (old.isNotBlank()) runCatching { removeObjects(listOf(old)) }
        val rows = write("PATCH", "profiles", "owner_id=eq.${enc(CloudSession.uid)}", JSONObject().put("avatar_path", storagePath))
        if (rows.isEmpty()) throw IllegalStateException("没有权限修改头像")
        return JSONObject().put("user", toUser(rows[0]))
    }

    private fun move(id: String, direction: String, body: JSONObject?): JSONObject {
        val me = requireProfile()
        val row = rows("submissions", "select=*&id=eq.$id").firstOrNull() ?: throw IllegalStateException("没有这条进度")
        val mode = modeOf(num(row, "assignment_id").toString())
        val status = row.optString("status")
        val own = row.optString("student_ref") == me.optString("owner_id")
        val role = me.optString("role")
        val next = when (direction) {
            "collect" -> {
                if (!(mode == "collect" && role == "monitor" && (status == "not_submitted" || status == "ready_to_submit"))) throw IllegalStateException("现在不能揽收")
                "collected_by_monitor"
            }
            "advance" -> when {
                status == "not_submitted" && own && (role == "student" || role == "monitor") -> "ready_to_submit"
                else -> throw IllegalStateException("现在不能改这一步")
            }
            else -> when {
                status == "ready_to_submit" && own && (role == "student" || role == "monitor") -> "not_submitted"
                status == "collected_by_monitor" && role == "monitor" -> {
                    val events = rows("events", "select=status&submission_id=eq.$id")
                    if (events.any { it.optString("status") == "ready_to_submit" }) "ready_to_submit" else "not_submitted"
                }
                else -> throw IllegalStateException("现在不能改这一步")
            }
        }
        val updated = write("PATCH", "submissions", "id=eq.$id", JSONObject().put("status", next).put("updated_at", java.time.Instant.now().toString()))
        if (updated.isEmpty()) throw IllegalStateException("没有权限改这一步")
        val note = body?.optString("note").orEmpty().trim().take(200)
        val event = write("POST", "events", "", JSONObject().put("submission_id", id.toLong()).put("status", next).put("note", note))
        if (event.isEmpty()) throw IllegalStateException("没有权限记录进度")
        return JSONObject().put("submission", present(updated[0], me, mode))
    }

    private fun shiftClass(assignmentId: String, from: String, to: String): JSONObject {
        val me = requireProfile()
        if (me.optString("role") != "monitor") throw IllegalStateException("只有学习委员可以操作")
        if (rows("assignments", "select=id&id=eq.$assignmentId").isEmpty()) throw IllegalStateException("没有这次作业")
        val targets = rows("submissions", "select=id&assignment_id=eq.$assignmentId&status=eq.$from")
        if (targets.isEmpty()) {
            throw IllegalStateException(if (from == "collected_by_monitor") "还没有已揽收的同学" else "还没有可以分发的进度")
        }
        val now = java.time.Instant.now().toString()
        var count = 0
        for (target in targets) {
            val id = num(target, "id")
            val updated = write("PATCH", "submissions", "id=eq.$id", JSONObject().put("status", to).put("updated_at", now))
            if (updated.isEmpty()) continue
            val event = write("POST", "events", "", JSONObject().put("submission_id", id).put("status", to).put("note", ""))
            if (event.isEmpty()) throw IllegalStateException("没有权限记录进度")
            count++
        }
        if (count == 0) throw IllegalStateException("没有权限改这一步")
        return JSONObject().put("ok", true).put("count", count)
    }

    private fun present(row: JSONObject, viewer: JSONObject, mode: String): JSONObject {
        val studentRef = row.optString("student_ref")
        val pendingRef = studentRef.startsWith("roster:")
        val student = if (pendingRef) {
            val rid = studentRef.removePrefix("roster:")
            rows("class_roster", "select=name,student_no&id=eq.$rid").firstOrNull()?.let {
                JSONObject()
                    .put("id", studentRef)
                    .put("studentNo", it.optString("student_no"))
                    .put("name", it.optString("name"))
                    .put("role", "student")
                    .put("roleLabel", "未加入")
                    .put("avatar", "")
                    .put("pending", true)
            }
        } else {
            rows("profiles", "select=*&owner_id=eq.${enc(studentRef)}").firstOrNull()?.let { toUser(it) }
        }
        val status = row.optString("status")
        val own = studentRef == viewer.optString("owner_id")
        val role = viewer.optString("role")
        val events = rows("events", "select=*&submission_id=eq.${num(row, "id")}&order=id.asc")
        val names = if (events.isEmpty()) emptyMap() else rows("profiles", "select=owner_id,name").associate { it.optString("owner_id") to it.optString("name") }
        val eventArray = JSONArray()
        for (event in events) {
            eventArray.put(
                JSONObject()
                    .put("id", num(event, "id"))
                    .put("status", event.optString("status"))
                    .put("statusLabel", Domain.label(event.optString("status"), mode))
                    .put("note", event.optString("note"))
                    .put("actorName", names[event.optString("owner_id")] ?: "")
                    .put("createdAt", event.optString("created_at")),
            )
        }
        val canAdvance = status == "not_submitted" && own && (role == "student" || role == "monitor")
        val canRevert = (status == "ready_to_submit" && own && (role == "student" || role == "monitor")) ||
            (status == "collected_by_monitor" && role == "monitor")
        val canCollect = mode == "collect" && role == "monitor" && (status == "not_submitted" || status == "ready_to_submit")
        val advanceLabel = if (canAdvance) "我已写完，随时可以提交" else null
        return JSONObject()
            .put("id", num(row, "id"))
            .put("assignmentId", num(row, "assignment_id"))
            .put(
                "student",
                when {
                    student == null -> JSONObject().put("id", studentRef).put("studentNo", "").put("name", "")
                        .put("role", "student").put("roleLabel", "同学").put("avatar", "")
                    // 占位记录已经构造好并带了 pending，不能再过 toUser，否则标记会被丢掉
                    pendingRef -> student
                    else -> toUser(student)
                },
            )
            .put("status", status)
            .put("statusLabel", Domain.label(status, mode))
            .put("updatedAt", row.optString("updated_at"))
            .put("events", eventArray)
            .put(
                "actions",
                JSONObject()
                    .put("advance", canAdvance)
                    .put("revert", canRevert)
                    .put("collect", canCollect)
                    .put("advanceLabel", advanceLabel ?: JSONObject.NULL),
            )
    }

    private fun modeOf(id: String): String =
        rows("assignments", "select=mode&id=eq.$id").firstOrNull()?.optString("mode").orEmpty().ifBlank { "collect" }

    private fun toUser(row: JSONObject): JSONObject {
        val role = row.optString("role")
        return JSONObject()
            .put("id", row.optString("owner_id"))
            .put("studentNo", row.optString("student_no"))
            .put("name", row.optString("name"))
            .put("role", role)
            .put("roleLabel", Domain.ROLE_LABELS[role] ?: role)
            .put("avatar", row.optString("avatar_path"))
            .put("classId", if (row.isNull("class_id")) 0L else row.optLong("class_id"))
    }

    private fun deadline(value: String?): Any {
        val text = value.orEmpty().trim()
        if (text.isBlank() || text == "null") return JSONObject.NULL
        val instant = runCatching { java.time.Instant.parse(text) }.getOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(text).atZone(java.time.ZoneId.systemDefault()).toInstant() }.getOrNull()
            ?: throw IllegalStateException("截止时间无法识别")
        return instant.toString()
    }

    private fun upload(body: JSONObject?): String {
        ensureToken()
        val mime = body?.optString("mime").orEmpty().ifBlank { "image/jpeg" }
        if (mime !in setOf("image/jpeg", "image/png", "image/gif", "image/webp")) throw IllegalStateException("请上传 jpg、png、gif 或 webp 图片")
        val raw = body?.optString("data").orEmpty().let { if (it.startsWith("data:")) it.substringAfter(",") else it }
        val bytes = Base64.decode(raw, Base64.DEFAULT)
        if (bytes.size > 4 * 1024 * 1024) throw IllegalStateException("图片太大")
        val path = "shared/${CloudSession.uid}/images/${UUID.randomUUID()}.jpg"
        val connection = open("$host/.cloud/storage/object/runtime/$path", "POST")
        connection.setRequestProperty("Content-Type", "image/jpeg")
        connection.doOutput = true
        connection.outputStream.use { it.write(bytes) }
        val code = connection.responseCode
        val text = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) throw IllegalStateException(explain(code, text))
        return path
    }

    private fun signedUrl(path: String): String {
        ensureToken()
        val connection = open("$host/.cloud/storage/object/sign/runtime/$path", "POST")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.outputStream.use { it.write("""{"expiresIn":600}""".toByteArray()) }
        val code = connection.responseCode
        val text = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) throw IllegalStateException(explain(code, text))
        val signed = JSONObject(text).optString("signedURL").ifBlank { JSONObject(text).optString("signedUrl") }
        if (signed.startsWith("http")) return signed
        return host + "/.cloud/storage" + signed
    }

    private fun removeObjects(paths: List<String>) {
        val connection = open("$host/.cloud/storage/object/runtime", "DELETE")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.outputStream.use { it.write(JSONArray(paths).toString().toByteArray()) }
        connection.responseCode
        connection.disconnect()
    }

    private fun rows(table: String, query: String): List<JSONObject> {
        val array = request("GET", table, query, null)
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    private fun write(method: String, table: String, query: String, body: JSONObject?): List<JSONObject> {
        val array = request(method, table, query, body)
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    private fun request(method: String, table: String, query: String, body: JSONObject?): JSONArray {
        ensureToken()
        val first = call(method, table, query, body)
        if (first.code in 200..299) return first.rows
        if (first.text.contains("invalid_grant") && refreshNow()) {
            val second = call(method, table, query, body)
            if (second.code in 200..299) return second.rows
            android.util.Log.e("Homework", "db $method $table ${second.code} ${second.text.take(300)}")
            throw IllegalStateException(explain(second.code, second.text))
        }
        android.util.Log.e("Homework", "db $method $table ${first.code} ${first.text.take(300)} uid=${CloudSession.uid.take(12)}")
        throw IllegalStateException(explain(first.code, first.text))
    }

    private fun call(method: String, table: String, query: String, body: JSONObject?): HttpResult {
        val url = "$host/.cloud/database/rest/$table" + if (query.isBlank()) "" else "?$query"
        val connection = open(url, method)
        if (method != "GET") {
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Prefer", "return=representation")
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
        }
        val code = connection.responseCode
        val text = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (text.isBlank() || !text.trim().startsWith("[")) return HttpResult(code, text, JSONArray())
        return HttpResult(code, text, JSONArray(text))
    }

    private fun ensureToken() {
        if (CloudSession.accessToken.isBlank()) throw IllegalStateException("请先登录")
        if (CloudSession.expiresAt > System.currentTimeMillis() + 60_000) return
        if (!refreshNow()) throw IllegalStateException("请先登录")
    }

    private fun refreshNow(): Boolean {
        if (CloudSession.refreshToken.isBlank()) return false
        val connection = open("$host/.cloud/auth/v1/token", "POST", auth = false)
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.outputStream.use { it.write(JSONObject().put("grant_type", "refresh_token").put("refresh_token", CloudSession.refreshToken).toString().toByteArray()) }
        val code = connection.responseCode
        val text = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) {
            android.util.Log.e("Homework", "refresh $code ${text.take(200)}")
            return false
        }
        val json = JSONObject(text)
        val expiresIn = json.optLong("expires_in")
        CloudSession.apply(
            json.optString("access_token"),
            json.optString("refresh_token"),
            System.currentTimeMillis() + expiresIn * 1000,
            json.optString("sub").ifBlank { CloudSession.uid },
        )
        CloudSession.save(prefs)
        return CloudSession.accessToken.isNotBlank()
    }

    private fun open(url: String, method: String, auth: Boolean = true): HttpURLConnection {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            connectTimeout = 8000
            readTimeout = 30000
            setRequestProperty("x-wb-webapp-access-key", key)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Origin", host)
            setRequestProperty("Referer", "$host/")
            val cookie = CookieManager.getInstance().getCookie(host)
            if (!cookie.isNullOrBlank()) setRequestProperty("Cookie", cookie)
            if (auth && CloudSession.accessToken.isNotBlank()) setRequestProperty("Authorization", "Bearer ${CloudSession.accessToken}")
        }
        return connection
    }

    private data class HttpResult(val code: Int, val text: String, val rows: JSONArray)

    private fun readBytes(connection: HttpURLConnection): ByteArray {
        val code = connection.responseCode
        val bytes = (if (code in 200..299) connection.inputStream else connection.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        connection.disconnect()
        if (code !in 200..299) throw IllegalStateException("图片加载失败")
        return bytes
    }

    private fun explain(code: Int, text: String): String {
        val json = runCatching { JSONObject(text) }.getOrNull()
        val db = json?.optString("code").orEmpty()
        val message = json?.optString("message").orEmpty()
        if (db == "23505" || message.contains("duplicate", true)) return "这个学号已经在班里了"
        if (db == "42501" || code == 401 || code == 403) return if (message.any { it.code > 127 }) message else "没有权限做这件事"
        if ((db == "42P01" || message.contains("does not exist", true) || message.contains("schema cache", true) || message.contains("Could not find", true)) && message.contains("comment", true)) {
            return "评论还没写进云数据库"
        }
        if (db == "42P01" || message.contains("does not exist", true)) return "云数据库还没建表"
        if (message.contains("schema cache", true) || message.contains("Could not find", true)) return "科目和周次还没写进云数据库"
        if (message.contains("jwt", true) || code == 401) return "请先登录"
        return message.ifBlank { "请求失败（$code）" }
    }

    private fun num(row: JSONObject, key: String): Long = when (val value = row.opt(key)) {
        is Number -> value.toLong()
        else -> value?.toString()?.toLongOrNull() ?: 0L
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun decode(value: String) = java.net.URLDecoder.decode(value, "UTF-8")
}
