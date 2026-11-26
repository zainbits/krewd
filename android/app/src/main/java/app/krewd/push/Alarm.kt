package app.krewd.push

import android.app.KeyguardManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.krewd.KrewdTheme
import app.krewd.LocalKrewdColors
import app.krewd.MainActivity
import app.krewd.R
import app.krewd.data.AlertInfo
import app.krewd.data.AlertLevel

/**
 * A ringing Critical alarm: a foreground service (only while it rings) that plays the phone's alarm sound in a loop on
 * the alarm stream and vibrates, under a full-screen notification ([AlarmActivity] over the lock screen). It rings
 * until Got it, at most [MAX_RING_MS]; after that the notification stays, silent. Started only when Do Not Disturb is
 * off ([AlertCenter.ring]).
 */
class AlarmService : Service() {
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { silence() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val alert = intent?.let(::alertOf) ?: run { stopSelf(); return START_NOT_STICKY }
        _ringing.value = alert
        startForeground(AlertCenter.ALARM_ID, AlertCenter.alarmNotification(this, alert), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        startRinging()
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, MAX_RING_MS)
        return START_NOT_STICKY
    }

    private fun startRinging() {
        if (player != null) return
        val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(this@AlarmService, uri)
                isLooping = true
                prepare()
                start()
            }
        }.onFailure { Log.w("Krewd", "alarm sound did not play", it) }.getOrNull()
        val vibrator = getSystemService(VibratorManager::class.java)?.defaultVibrator
        vibrator?.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0),
            android.os.VibrationAttributes.Builder().setUsage(android.os.VibrationAttributes.USAGE_ALARM).build(),
        )
    }

    private fun silence() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        getSystemService(VibratorManager::class.java)?.defaultVibrator?.cancel()
    }

    override fun onDestroy() {
        _ringing.value = null
        handler.removeCallbacks(timeout)
        silence()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "app.krewd.ALARM_STOP"
        const val MAX_RING_MS = 30 * 60_000L

        fun start(context: Context, alert: AlertInfo) {
            try {
                context.startForegroundService(withAlert(Intent(context, AlarmService::class.java), alert))
            } catch (e: Exception) {
                // Android refused a foreground service from the background: the alarm's notification still shows,
                // full screen where allowed, so it is not lost.
                Log.w("Krewd", "alarm service did not start", e)
                context.getSystemService(android.app.NotificationManager::class.java)
                    .notify(AlertCenter.ALARM_ID, AlertCenter.alarmNotification(context, alert))
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, AlarmService::class.java)) }
        }

        private val _ringing = kotlinx.coroutines.flow.MutableStateFlow<AlertInfo?>(null)

        /** The alarm ringing now, if any: the Krewd tab shows it on top with its own Got it (opening the app alone
         *  never stops it, so it cannot be silenced by accident). */
        val ringing: kotlinx.coroutines.flow.StateFlow<AlertInfo?> = _ringing

        fun withAlert(intent: Intent, alert: AlertInfo): Intent = intent
            .putExtra("id", alert.id).putExtra("kind", alert.kind).putExtra("level", alert.level)
            .putExtra("title", alert.title).putExtra("body", alert.body)
            .putExtra("session", alert.sessionId).putExtra("needs", alert.needsId)

        fun alertOf(intent: Intent): AlertInfo? {
            val id = intent.getStringExtra("id") ?: return null
            return AlertInfo(
                id, intent.getStringExtra("kind").orEmpty(), intent.getStringExtra("level") ?: AlertLevel.CRITICAL,
                intent.getStringExtra("title") ?: "Krewd alarm", intent.getStringExtra("body").orEmpty(),
                intent.getStringExtra("session"), intent.getStringExtra("needs"), true,
            )
        }
    }
}

/** The alarm over the lock screen: what it is, a big Got it (stops it), and Open (stops it and opens the card). */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val alert = AlarmService.alertOf(intent) ?: run { finish(); return }
        setContent {
            KrewdTheme {
                AlarmScreen(
                    alert,
                    onGotIt = {
                        AlertCenter.gotIt(this, alert.id)
                        finish()
                    },
                    onOpen = {
                        AlertCenter.gotIt(this, alert.id)
                        getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this, null)
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .putExtra(AlertCenter.EXTRA_ALERT_ID, alert.id)
                                .apply {
                                    alert.needsId?.let { putExtra(AlertCenter.EXTRA_NEEDS_ID, it) }
                                    if (alert.needsId == null) alert.sessionId?.let { putExtra(MainActivity.EXTRA_SESSION_ID, it) }
                                }
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        )
                        finish()
                    },
                )
            }
        }
    }

    companion object {
        /** A local test (no server alert to acknowledge). */
        const val TEST_LOCAL = "local-test"

        fun intent(context: Context, alert: AlertInfo): Intent =
            AlarmService.withAlert(Intent(context, AlarmActivity::class.java), alert)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }
}

@androidx.compose.runtime.Composable
private fun AlarmScreen(alert: AlertInfo, onGotIt: () -> Unit, onOpen: () -> Unit) {
    val krewd = LocalKrewdColors.current
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(painterResource(R.drawable.ic_notification), contentDescription = null, tint = krewd.needs, modifier = Modifier.size(72.dp))
            Spacer(Modifier.height(24.dp))
            Text(alert.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            if (alert.body.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(alert.body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onGotIt,
                colors = ButtonDefaults.buttonColors(containerColor = krewd.needs, contentColor = krewd.onNeeds),
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) { Text("Got it", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Open in Krewd") }
        }
    }
}
