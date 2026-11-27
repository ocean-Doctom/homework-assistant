package edu.hust.homeworklogistics

import android.graphics.Color

/** Mirrors desktop/src/domain.js so the offline demo follows the same rules. */
object Domain {
    val STATUSES = listOf(
        "not_submitted",
        "submitted_to_monitor",
        "received_by_ta",
        "graded",
        "returned_to_monitor",
        "distributed",
    )

    val COLLECT_STATUSES = listOf("not_submitted", "ready_to_submit", "collected_by_monitor", "retrieved_by_monitor", "distributed")

    val STATUS_LABELS = mapOf(
        "not_submitted" to "未提交",
        "submitted_to_monitor" to "已提交到学习委员",
        "received_by_ta" to "助教已接收",
        "graded" to "已批改",
        "returned_to_monitor" to "已移交学习委员",
        "distributed" to "已分发",
    )

    private val COLLECT_LABELS = mapOf(
        "not_submitted" to "未写完",
        "ready_to_submit" to "已写完，随时可以提交",
        "collected_by_monitor" to "学委已揽收",
        "retrieved_by_monitor" to "学委已取回",
        "distributed" to "学委已分发",
    )

    private val COLLECT_OWNER = mapOf(
        "not_submitted" to "作业已发布",
        "ready_to_submit" to "同学标记写完之后，学委可以收",
        "collected_by_monitor" to "学委勾选清单后记录",
        "retrieved_by_monitor" to "学委取回后记录",
        "distributed" to "学委分发后记录",
    )

    fun statuses(mode: String) = if (mode == "collect") COLLECT_STATUSES else STATUSES

    fun label(status: String, mode: String) =
        (if (mode == "collect") COLLECT_LABELS[status] else null)
            ?: STATUS_LABELS[status]
            ?: COLLECT_LABELS[status]
            ?: status

    fun owner(status: String, mode: String) =
        if (mode == "collect") COLLECT_OWNER[status] ?: "" else STEP_OWNER[status] ?: ""

    val ROLE_LABELS = mapOf("student" to "同学", "monitor" to "学习委员", "ta" to "助教")

    /** Who is expected to perform each step, shown for upcoming steps. */
    val STEP_OWNER = mapOf(
        "not_submitted" to "作业已发布",
        "submitted_to_monitor" to "同学交给学委，或学委代登记",
        "received_by_ta" to "由助教确认接收",
        "graded" to "由助教批改",
        "returned_to_monitor" to "由助教移交学委",
        "distributed" to "由学委发回同学",
    )

    private val ADVANCE_LABELS = mapOf(
        "not_submitted" to mapOf("student" to "我已交给学委", "monitor" to "登记已提交"),
        "submitted_to_monitor" to mapOf("ta" to "确认接收"),
        "received_by_ta" to mapOf("ta" to "标记已批改"),
        "graded" to mapOf("ta" to "移交学习委员"),
        "returned_to_monitor" to mapOf("monitor" to "标记已分发"),
    )

    fun nextStatus(status: String): String? {
        val index = STATUSES.indexOf(status)
        if (index < 0 || index == STATUSES.size - 1) return null
        return STATUSES[index + 1]
    }

    fun previousStatus(status: String): String? {
        val index = STATUSES.indexOf(status)
        if (index <= 0) return null
        return STATUSES[index - 1]
    }

    private fun ownsAdvance(role: String, status: String, isOwn: Boolean): Boolean = when (status) {
        "not_submitted" -> role == "monitor" || (role == "student" && isOwn)
        "submitted_to_monitor", "received_by_ta", "graded" -> role == "ta"
        "returned_to_monitor" -> role == "monitor"
        else -> false
    }

    fun canAdvance(viewerId: Int, role: String, studentId: Int, status: String, mode: String = "full"): Boolean {
        val isOwn = viewerId == studentId
        if (mode == "collect") {
            if (status == "not_submitted") return isOwn && (role == "student" || role == "monitor")
            return false
        }
        if (role == "monitor" && isOwn && status == "not_submitted") return true
        return ownsAdvance(role, status, isOwn)
    }

    fun canCollect(role: String, status: String, mode: String): Boolean =
        mode == "collect" && role == "monitor" && (status == "not_submitted" || status == "ready_to_submit")

    fun canRevert(viewerId: Int, role: String, studentId: Int, status: String, mode: String = "full"): Boolean {
        val isOwn = viewerId == studentId
        if (mode == "collect") {
            if (status == "ready_to_submit") return isOwn && (role == "student" || role == "monitor")
            if (status == "collected_by_monitor") return role == "monitor"
            return false
        }
        val previous = previousStatus(status) ?: return false
        return ownsAdvance(role, previous, isOwn)
    }

    fun advanceLabel(viewerId: Int, role: String, studentId: Int, status: String, mode: String = "full"): String? {
        if (!canAdvance(viewerId, role, studentId, status, mode)) return null
        if (mode == "collect") return "我已写完，随时可以提交"
        if (role == "monitor" && viewerId == studentId && status == "not_submitted") return "我已交给学委"
        return ADVANCE_LABELS[status]?.get(role)
    }

    /** Foreground and background colour of the status chip. */
    fun statusColors(status: String): Pair<Int, Int> = when (status) {
        "submitted_to_monitor" -> Color.parseColor("#1D4ED8") to Color.parseColor("#DBEAFE")
        "received_by_ta" -> Color.parseColor("#6D28D9") to Color.parseColor("#EDE9FE")
        "graded" -> Color.parseColor("#B45309") to Color.parseColor("#FEF3C7")
        "returned_to_monitor" -> Color.parseColor("#0F766E") to Color.parseColor("#CCFBF1")
        "distributed", "collected_by_monitor", "retrieved_by_monitor" -> Color.parseColor("#047857") to Color.parseColor("#D1FAE5")
        "ready_to_submit" -> Color.parseColor("#1D4ED8") to Color.parseColor("#DBEAFE")
        else -> Color.parseColor("#4B5563") to Color.parseColor("#F3F4F6")
    }
}
