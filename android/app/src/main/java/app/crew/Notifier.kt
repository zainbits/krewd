package app.crew

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import app.crew.data.Approval

/** Local notifications while the app process is alive: approvals and finished tasks. */
class Notifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_APPROVALS, "Needs your OK", NotificationManager.IMPORTANCE_HIGH),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Finished tasks", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun approval(approval: Approval, taskTitle: String) =
        post(approval.sessionId, CHANNEL_APPROVALS, "Needs your OK: $taskTitle", approval.title)

    fun finished(sessionId: String, taskTitle: String) =
        post(sessionId, CHANNEL_DONE, taskTitle, "Finished")

    fun clear(sessionId: String) = manager.cancel(sessionId.hashCode())

    private fun post(sessionId: String, channel: String, title: String, text: String) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_SESSION_ID, sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            context,
            sessionId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = android.app.Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_launcher_symbol)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        manager.notify(sessionId.hashCode(), notification)
    }

    private companion object {
        const val CHANNEL_APPROVALS = "approvals"
        const val CHANNEL_DONE = "done"
    }
}
