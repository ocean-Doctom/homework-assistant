package edu.hust.homeworklogistics

import android.app.job.JobParameters
import android.app.job.JobService

class MessageJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try {
                val prefs = getSharedPreferences("session", MODE_PRIVATE)
                CloudSession.load(prefs)
                if (CloudSession.accessToken.isNotBlank()) {
                    val data = CloudApi(prefs).get("/messages", CloudSession.accessToken)
                    MessageAlerts.publish(this, data)
                }
            } catch (_: Exception) {
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}
