package edu.hust.homeworklogistics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast

/** 接收 PackageInstaller 的安装结果，安装失败时把系统给的原因弹出来。 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        Log.i("Homework", "install status=$status message=$message")
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // 系统要弹安装确认框，得由我们把那个 Intent 拉起来，否则点了更新什么都不会发生
            @Suppress("DEPRECATION")
            val confirm = if (android.os.Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
            }
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
            }
            return
        }
        if (status != PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(context, "安装没有完成：$message", Toast.LENGTH_LONG).show()
        }
    }
}
