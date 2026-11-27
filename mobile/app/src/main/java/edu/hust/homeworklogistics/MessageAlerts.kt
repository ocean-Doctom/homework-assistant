package edu.hust.homeworklogistics

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject

object MessageAlerts {
    private const val CHANNEL = "homework"
    private const val JOB_ID = 7101
    private const val ASK = 4103

    fun schedule(context: Context) {
        runCatching {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, MessageJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setPeriodic(15 * 60 * 1000L)
                .build()
            scheduler.schedule(job)
        }
    }

    fun ask(activity: Activity) {
        if (Build.VERSION.SDK_INT < 33) return
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), ASK)
    }

    fun publish(context: Context, data: JSONObject) {
        val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
        val rows = data.optJSONArray("messages") ?: return
        val items = (0 until rows.length()).map { rows.getJSONObject(it) }
        val maxId = items.maxOfOrNull { it.optInt("id") } ?: 0
        if (!prefs.contains("notified_message")) {
            prefs.edit().putInt("notified_message", maxId).apply()
            return
        }
        val floor = maxOf(prefs.getInt("seen_message", 0), prefs.getInt("notified_message", 0))
        val fresh = items.filter { it.optInt("id") > floor }.sortedBy { it.optInt("id") }
        if (fresh.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(manager)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        for (item in fresh.take(5)) {
            val title = item.optString("title").ifBlank { "新消息" }
            val body = item.optString("body").ifBlank { "班级里有一条新消息" }
            val notification = Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(open)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .build()
            manager.notify(item.optInt("id"), notification)
        }
        prefs.edit().putInt("notified_message", fresh.maxOf { it.optInt("id") }).apply()
    }

    fun clear(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancelAll()
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL) != null) return
        val channel = NotificationChannel(CHANNEL, "作业消息", NotificationManager.IMPORTANCE_HIGH)
        channel.description = "新作业和班级消息"
        manager.createNotificationChannel(channel)
    }
}
