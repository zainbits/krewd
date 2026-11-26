package app.krewd.push

import android.content.Context
import android.util.Log
import app.krewd.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The google flavor's push: Firebase Cloud Messaging, started by the app itself from the build's Firebase config
 * (BuildConfig.FIREBASE_*, read from google-services.json outside the repo; see build.gradle.kts). A build without
 * that config has no push and says so.
 */
object PushProvider {
    const val NAME = "Firebase Cloud Messaging"

    /** Whether this build can push at all. */
    val built: Boolean get() = BuildConfig.FIREBASE_APP_ID.isNotBlank()

    fun init(context: Context): Boolean {
        if (!built) return false
        return runCatching {
            if (FirebaseApp.getApps(context).isEmpty()) {
                val options = FirebaseOptions.Builder()
                    .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                    .setApiKey(BuildConfig.FIREBASE_API_KEY)
                    .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                    .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                    .build()
                FirebaseApp.initializeApp(context, options)
            }
            true
        }.onFailure { Log.w("Krewd", "Firebase did not start", it) }.getOrDefault(false)
    }

    /** This phone's FCM token, or null when push is not available (no Play services, no config, no network). */
    suspend fun token(context: Context): String? {
        if (!init(context)) return null
        return suspendCancellableCoroutine { cont ->
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (!task.isSuccessful) Log.w("Krewd", "No FCM token", task.exception)
                cont.resume(if (task.isSuccessful) task.result else null)
            }
        }
    }
}

/** FCM's entry: a new token goes to Krewd; a push (`k=alert`, `id`, `l` level) becomes a notification. */
class KrewdMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) = AlertCenter.newToken(applicationContext, token)

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data["k"] == "alert") AlertCenter.onPush(applicationContext, data["id"].orEmpty(), data["l"])
    }
}
