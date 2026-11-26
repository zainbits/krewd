package app.krewd.push

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import app.krewd.KrewdApplication
import app.krewd.KrewdViewModel
import app.krewd.MainActivity
import app.krewd.R
import app.krewd.data.AlertInfo
import app.krewd.data.AlertLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Krewd's notifications, by level (server alerts.py decides the level):
 *
 * - info: no notification (a card in the app only).
 * - action: a normal notification.
 * - urgent: a heads-up notification whose sound repeats until it is opened (FLAG_INSISTENT).
 * - critical: a full-screen alarm that rings until "Got it" ([AlarmService], [AlarmActivity]). It follows Do Not
 *   Disturb (the owner's choice): with it on, the alarm waits silently as a notification that cannot be swiped away.
 *
 * Alerts come by push (FCM, [onPush]: a wake-up with the alert id; the details are fetched from Krewd) or, without
 * push, from the app's live connection while it is open ([fromEvent]). Every push ends in a notification, as Android
 * asks of high-priority pushes: when Krewd cannot be reached (Tailscale off) it says "Krewd: something needs you".
 */
object AlertCenter {
    const val CH_ACTION = "alert_action"
    const val CH_URGENT = "alert_urgent"
    const val CH_CRITICAL = "alert_critical" // the alarm's own notification; the service plays the sound
    const val CH_CRITICAL_QUIET = "alert_critical_quiet" // a critical alarm during Do Not Disturb
    const val ALARM_ID = 0x4b52_0001
    const val EXTRA_ALERT_ID = "alert_id"
    const val EXTRA_NEEDS_ID = "needs_id"
    private const val FETCH_MS = 8_000L
    private const val TAG = "Krewd"

    private val handled = LinkedHashSet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun vm(context: Context): KrewdViewModel = (context.applicationContext as KrewdApplication).krewd

    fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        // The 0.36 channels (live activity, per-kind alerts) are replaced by one channel per level.
        listOf("live", "approvals", "finished", "errors", "done").forEach(manager::deleteNotificationChannel)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CH_ACTION, "Needs you", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Approvals, finished tasks and routine runs, things to do"
                },
                NotificationChannel(CH_URGENT, "Urgent", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Shows on top and repeats its sound until you open it"
                },
                NotificationChannel(CH_CRITICAL, "Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "A time-bound job failed (such as a missed check-in): full screen, rings until Got it"
                    setSound(null, null) // the alarm service rings, on the alarm stream
                    enableVibration(false)
                },
                NotificationChannel(CH_CRITICAL_QUIET, "Alarms during Do Not Disturb", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "An alarm that waits silently while Do Not Disturb is on"
                },
            ),
        )
    }

    private fun allowed(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Whether Do Not Disturb (any filter but "all") is on now. */
    fun quietNow(context: Context): Boolean {
        val filter = context.getSystemService(NotificationManager::class.java).currentInterruptionFilter
        return filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    private fun claim(id: String): Boolean = synchronized(handled) {
        if (id.isBlank() || !handled.add(id)) return false
        while (handled.size > 200) handled.remove(handled.first())
        true
    }

    fun newToken(context: Context, token: String) = vm(context).pushToken(token)

    /** A push (on FCM's thread, which may block for a few seconds): fetch the alert from Krewd and show it. */
    fun onPush(context: Context, id: String, level: String?) {
        if (!claim(id)) return
        channels(context)
        val vm = vm(context)
        val alert = runBlocking {
            withTimeoutOrNull(FETCH_MS) {
                runCatching { vm.fetchAlert(id) }.onFailure { Log.w(TAG, "alert $id: ${it.message}") }.getOrNull()
            }
        }
        when {
            alert == null -> generic(context, id, level ?: AlertLevel.ACTION)
            alert.open -> show(context, alert)
            else -> {} // already decided or done (on another phone, or in the app)
        }
    }

    /** An alert from the live connection (no push on this phone): the event carries it whole. */
    fun fromEvent(context: Context, alert: AlertInfo) {
        if (!alert.open || !claim(alert.id)) return
        show(context, alert)
    }

    private fun show(context: Context, alert: AlertInfo) {
        if (alert.level == AlertLevel.INFO || !allowed(context)) return
        val vm = vm(context)
        // The owner is looking at that chat right now: a card is enough (alarms always ring).
        if (alert.level != AlertLevel.CRITICAL && vm.isLookingAt(alert.sessionId)) return
        when (alert.level) {
            AlertLevel.CRITICAL -> ring(context, alert)
            AlertLevel.URGENT -> post(context, alert, CH_URGENT, insistent = true)
            else -> post(context, alert, CH_ACTION, insistent = false)
        }
    }

    /** Krewd could not be reached to say what it is (Tailscale off, server down): still a notification. */
    private fun generic(context: Context, id: String, level: String) {
        val alert = AlertInfo(id, "unknown", level, "Krewd: something needs you",
            "Connect to Bottle (home Wi-Fi or Tailscale) and open Krewd to see what.", null, null, true)
        if (!allowed(context)) return
        when (level) {
            AlertLevel.INFO, AlertLevel.ACTION -> post(context, alert, CH_ACTION, insistent = false)
            AlertLevel.URGENT -> post(context, alert, CH_URGENT, insistent = true)
            else -> ring(context, alert)
        }
    }

    private fun post(context: Context, alert: AlertInfo, channel: String, insistent: Boolean) {
        val notification = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(alert.title)
            .setContentText(alert.body)
            .setStyle(Notification.BigTextStyle().bigText(alert.body))
            .setContentIntent(openIntent(context, alert))
            .setAutoCancel(true)
            .setCategory(if (insistent) Notification.CATEGORY_REMINDER else Notification.CATEGORY_MESSAGE)
            .build()
        if (insistent) notification.flags = notification.flags or Notification.FLAG_INSISTENT
        context.getSystemService(NotificationManager::class.java).notify(notificationId(alert), notification)
    }

    private fun ring(context: Context, alert: AlertInfo) {
        if (quietNow(context)) {
            // Do Not Disturb: no sound, no full screen; it waits, and cannot be swiped away until Got it.
            val n = Notification.Builder(context, CH_CRITICAL_QUIET)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(alert.title)
                .setContentText(alert.body.ifBlank { "Alarm held back while Do Not Disturb is on" })
                .setStyle(Notification.BigTextStyle().bigText(alert.body))
                .setContentIntent(openIntent(context, alert))
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_ALARM)
                .addAction(Notification.Action.Builder(null, "Got it", gotItIntent(context, alert.id)).build())
                .build()
            context.getSystemService(NotificationManager::class.java).notify(ALARM_ID, n)
            return
        }
        AlarmService.start(context, alert)
    }

    /** The notification of a ringing alarm: full screen over the lock screen, Got it and Open. */
    fun alarmNotification(context: Context, alert: AlertInfo): Notification {
        val full = PendingIntent.getActivity(
            context, ALARM_ID,
            AlarmActivity.intent(context, alert),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(context, CH_CRITICAL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(alert.title)
            .setContentText(alert.body)
            .setStyle(Notification.BigTextStyle().bigText(alert.body))
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .addAction(Notification.Action.Builder(null, "Got it", gotItIntent(context, alert.id)).build())
            .build()
    }

    /** Got it: the alarm stops, its notification goes, and Krewd hears it was seen. */
    fun gotIt(context: Context, alertId: String?) {
        AlarmService.stop(context)
        context.getSystemService(NotificationManager::class.java).cancel(ALARM_ID)
        if (!alertId.isNullOrBlank() && alertId != AlarmActivity.TEST_LOCAL) scope.launch { runCatching { vm(context).ackAlertNow(alertId) } }
    }

    fun clearChat(context: Context, sessionId: String) =
        context.getSystemService(NotificationManager::class.java).cancel("chat:$sessionId".hashCode())

    fun clearNeeds(context: Context, needsId: String) =
        context.getSystemService(NotificationManager::class.java).cancel("needs:$needsId".hashCode())

    private fun notificationId(alert: AlertInfo): Int = when {
        alert.needsId != null -> "needs:${alert.needsId}".hashCode()
        alert.sessionId != null -> "chat:${alert.sessionId}".hashCode()
        else -> "alert:${alert.id}".hashCode()
    }

    fun openIntent(context: Context, alert: AlertInfo): PendingIntent = PendingIntent.getActivity(
        context,
        notificationId(alert),
        Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_ALERT_ID, alert.id)
            .apply {
                alert.needsId?.let { putExtra(EXTRA_NEEDS_ID, it) }
                if (alert.needsId == null) alert.sessionId?.let { putExtra(MainActivity.EXTRA_SESSION_ID, it) }
            }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun gotItIntent(context: Context, alertId: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        ALARM_ID,
        Intent(context, AlertActions::class.java).setAction(AlertActions.GOT_IT).putExtra(EXTRA_ALERT_ID, alertId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Notification buttons: Got it on an alarm. */
class AlertActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == GOT_IT) AlertCenter.gotIt(context, intent.getStringExtra(AlertCenter.EXTRA_ALERT_ID))
    }

    companion object {
        const val GOT_IT = "app.krewd.GOT_IT"
    }
}
