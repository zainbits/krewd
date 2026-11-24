package app.krewd.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import app.krewd.MainActivity
import app.krewd.R

/**
 * The microphone's foreground service: it runs the [Listener] of the chat that listens, with a "Listening" notification
 * (tap: open the chat; Stop). Android lets a microphone service start only while the app is in front, which is
 * where the owner taps the mic.
 */
class VoiceService : Service() {
    private var listener: Listener? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val l = listener
            if (l == null) {
                finish(null)
            } else {
                post(l.chatId, "Sending the last words…")
                l.stop(null)
            }
            return START_NOT_STICKY
        }
        val chat = intent?.getStringExtra(EXTRA_CHAT)
        if (chat == null) {
            if (listener == null) stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(chat, "Listening"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        val current = listener
        if (current?.chatId == chat) return START_NOT_STICKY
        current?.stop(null)
        val l = Listener(applicationContext, chat) { reason -> finish(reason, chat) }
        listener = l
        Voice.listener = l
        l.start()
        return START_NOT_STICKY
    }

    private fun finish(reason: String?, chat: String? = null) {
        if (chat != null && listener?.chatId != chat) return // a newer chat took over
        listener = null
        Voice.listener = null
        Voice.set { VoiceUi(enrolled = it.enrolled, available = it.available, chatId = if (reason != null) it.chatId else null, error = reason) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun post(chat: String, text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(chat, text))
    }

    private fun notification(chat: String, text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Voice input", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Krewd listens in a chat"
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            this, chat.hashCode() + 7,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SESSION_ID, chat)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, VoiceService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic_24)
            .setContentTitle("Krewd")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    override fun onDestroy() {
        listener?.stop(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_CHAT = "chat"
        const val ACTION_STOP = "app.krewd.voice.STOP"
        const val CHANNEL = "voice"
        const val NOTIFICATION_ID = 0x766f6963
    }
}
