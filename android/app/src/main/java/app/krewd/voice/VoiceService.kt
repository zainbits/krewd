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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The microphone's foreground service: it runs the [Listener] of the chat in voice mode, with a "Listening" (Mute)
 * or "Muted" notification; tapping it opens the chat, where the owner unmutes or leaves voice mode. Android lets a
 * microphone service start only while the app is in front, which is where the owner taps the mic.
 */
class VoiceService : Service() {
    private var listener: Listener? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watch: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_LEAVE -> {
                val l = listener
                if (l == null) finish(null) else l.stop(null)
                return START_NOT_STICKY
            }
            ACTION_MUTE -> {
                listener?.mute()
                return START_NOT_STICKY
            }
        }
        val chat = intent?.getStringExtra(EXTRA_CHAT)
        if (chat == null) {
            if (listener == null) stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(chat, muted = false), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        val current = listener
        if (current?.chatId == chat) return START_NOT_STICKY
        current?.stop(null)
        listen(chat)
        return START_NOT_STICKY
    }

    private fun listen(chat: String) {
        val l = Listener(applicationContext, chat) { reason -> scope.launch { finish(reason, chat) } }
        listener = l
        Voice.listener = l
        l.start()
        watch?.cancel()
        watch = scope.launch {
            Voice.state.map { it.chatId to it.muted }.distinctUntilChanged().collect { (id, muted) ->
                if (id == chat && listener === l) {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(chat, muted))
                }
            }
        }
    }

    private fun finish(reason: String?, chat: String? = null) {
        if (chat != null && listener?.chatId != chat) return // a newer chat took over
        listener = null
        Voice.listener = null
        watch?.cancel()
        Voice.set {
            VoiceUi(enrolled = it.enrolled, available = it.available, myVoice = it.myVoice, live = it.live,
                liveAvailable = it.liveAvailable, chatId = if (reason != null) it.chatId else null, error = reason)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(chat: String, muted: Boolean): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Voice input", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while a chat is in voice mode"
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            this, chat.hashCode() + 7,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SESSION_ID, chat)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(if (muted) R.drawable.ic_mic_off_24 else R.drawable.ic_mic_24)
            .setContentTitle("Krewd")
            .setContentText(if (muted) "Muted" else "Listening")
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
        if (!muted) {
            val mute = PendingIntent.getService(
                this, 1, Intent(this, VoiceService::class.java).setAction(ACTION_MUTE), PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(Notification.Action.Builder(null, "Mute", mute).build())
        }
        return builder.build()
    }

    override fun onDestroy() {
        if (listener != null) { // the system ended the service: voice mode is off
            listener?.stop(null)
            listener = null
            Voice.listener = null
            Voice.set { VoiceUi(enrolled = it.enrolled, available = it.available, myVoice = it.myVoice, live = it.live,
                liveAvailable = it.liveAvailable) }
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_CHAT = "chat"
        const val ACTION_LEAVE = "app.krewd.voice.LEAVE"
        const val ACTION_MUTE = "app.krewd.voice.MUTE"
        const val CHANNEL = "voice"
        const val NOTIFICATION_ID = 0x766f6963
    }
}
