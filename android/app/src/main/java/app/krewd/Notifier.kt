package app.krewd

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock

/**
 * Notifications while the app process is alive (no cloud push):
 *
 * - Live activity: one ongoing, promoted (Android 16 live update) notification per running task, with
 *   the engine, what it is doing now, the elapsed time, and Open and Stop. The first one is also the
 *   foreground notification of [LiveService], which keeps the process (and so these alerts) alive while
 *   tasks run with the phone locked or the app in the background. It goes away when nothing runs.
 * - Alerts: approval needed, finished (first line of the reply), error or stopped unexpectedly.
 *
 * Each kind has its own channel so it can be tuned in Android settings.
 */
class Notifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var pending: List<LiveTask>? = null
    private var lastTasks: List<LiveTask> = emptyList()
    private var lastPost = 0L
    private var shown = emptySet<String>()
    private var foregroundId: Int? = null

    init {
        manager.deleteNotificationChannel("done") // replaced by "finished"
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_LIVE, "Live activity", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "What a running task is doing, with Open and Stop"
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_APPROVALS, "Approvals", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "An agent needs your OK"
                },
                NotificationChannel(CHANNEL_FINISHED, "Finished", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "A task finished, with the first line of its reply"
                },
                NotificationChannel(CHANNEL_ERRORS, "Errors", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "A task failed or stopped unexpectedly"
                },
            ),
        )
    }

    private val allowed: Boolean
        get() = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    // alerts

    fun approval(sessionId: String, taskTitle: String, engine: String, what: String) =
        alert(sessionId, CHANNEL_APPROVALS, "Needs your OK: $taskTitle", "$engine wants to: $what")

    fun finished(sessionId: String, taskTitle: String, firstLine: String) =
        alert(sessionId, CHANNEL_FINISHED, taskTitle, firstLine)

    fun error(sessionId: String, taskTitle: String, reason: String) =
        alert(sessionId, CHANNEL_ERRORS, "Stopped: $taskTitle", reason)

    /** Clears a task's alerts (not its live activity) once the owner opens it. */
    fun clear(sessionId: String) = manager.cancel(alertId(sessionId))

    private fun alert(sessionId: String, channel: String, title: String, text: String) {
        if (!allowed) return
        val notification = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openIntent(sessionId))
            .setAutoCancel(true)
            .setCategory(if (channel == CHANNEL_ERRORS) Notification.CATEGORY_ERROR else Notification.CATEGORY_MESSAGE)
            .build()
        manager.notify(alertId(sessionId), notification)
    }

    // live activity

    /** Shows the running tasks; updates are throttled to one post a second. */
    fun live(tasks: List<LiveTask>) {
        pending = tasks
        val wait = LIVE_THROTTLE_MS - (SystemClock.elapsedRealtime() - lastPost)
        handler.removeCallbacks(flush)
        if (wait <= 0) flush.run() else handler.postDelayed(flush, wait)
    }

    /** Back in the foreground the app may start the service again if it could not before. */
    fun appVisible() {
        if (lastTasks.isEmpty() || foregroundId != null) return
        pending = pending ?: lastTasks
        handler.post(flush)
    }

    private val flush = Runnable {
        val tasks = pending ?: return@Runnable
        pending = null
        lastTasks = tasks
        lastPost = SystemClock.elapsedRealtime()
        val ids = tasks.map { it.sessionId }.toSet()
        (shown - ids).forEach { manager.cancel(liveId(it)) }
        shown = ids
        if (tasks.isEmpty()) {
            stopService()
            return@Runnable
        }
        if (!allowed) return@Runnable
        tasks.forEach { manager.notify(liveId(it.sessionId), liveNotification(it)) }
        val first = tasks.first()
        if (foregroundId != liveId(first.sessionId)) startService(liveId(first.sessionId), liveNotification(first))
    }

    private fun liveNotification(task: LiveTask): Notification {
        val stop = Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_stop_24), "Stop", stopIntent(task.sessionId),
        ).build()
        val open = Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_chat_24), "Open", openIntent(task.sessionId),
        ).build()
        return Notification.Builder(context, CHANNEL_LIVE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${task.engine}: ${task.activity}")
            .setContentText(task.title)
            .setContentIntent(openIntent(task.sessionId))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(task.since)
            .setUsesChronometer(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setShortCriticalText(if (task.waiting) "Needs you" else task.engine)
            .setRequestPromotedOngoing(true)
            .addAction(open)
            .addAction(stop)
            .build()
    }

    private fun startService(id: Int, notification: Notification) {
        try {
            context.startForegroundService(
                Intent(context, LiveService::class.java).putExtra(LiveService.EXTRA_ID, id).putExtra(LiveService.EXTRA_NOTIFICATION, notification),
            )
            foregroundId = id
        } catch (e: Exception) {
            // Android does not let a background app start a foreground service; the notifications still
            // show, and the service starts the next time the app is on screen.
            foregroundId = null
        }
    }

    private fun stopService() {
        if (foregroundId == null) return
        foregroundId = null
        runCatching { context.stopService(Intent(context, LiveService::class.java)) }
    }

    private fun openIntent(sessionId: String): PendingIntent = PendingIntent.getActivity(
        context,
        sessionId.hashCode(),
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_SESSION_ID, sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun stopIntent(sessionId: String): PendingIntent = PendingIntent.getActivity(
        context,
        sessionId.hashCode() + 1,
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_STOP_SESSION_ID, sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun alertId(sessionId: String) = sessionId.hashCode()

    private fun liveId(sessionId: String) = sessionId.hashCode() xor LIVE_SALT

    private companion object {
        const val CHANNEL_LIVE = "live"
        const val CHANNEL_APPROVALS = "approvals"
        const val CHANNEL_FINISHED = "finished"
        const val CHANNEL_ERRORS = "errors"
        const val LIVE_THROTTLE_MS = 1_000L
        const val LIVE_SALT = 0x5f3759df
    }
}

/**
 * Keeps the process alive while tasks run, so the long poll and its alerts continue with the phone
 * locked. Its notification is the first task's live activity; [Notifier] updates it in place.
 */
class LiveService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getIntExtra(EXTRA_ID, 0) ?: 0
        val notification = intent?.getParcelableExtra(EXTRA_NOTIFICATION, Notification::class.java)
        if (id == 0 || notification == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_NOTIFICATION = "notification"
    }
}
