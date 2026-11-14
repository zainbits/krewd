package app.krewd.data

import android.app.Activity
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class PresenceCancelled(message: String) : IOException(message)

/** The owner's fingerprint, face or screen lock, then one signature with this phone's presence key. */
object Presence {
    /** Call on the main thread, with the activity in front. */
    suspend fun sign(activity: Activity, title: String, subtitle: String, message: ByteArray): ByteArray =
        suspendCancellableCoroutine { cont ->
            val signature = try {
                Keys.presenceSignature()
            } catch (e: Exception) {
                cont.resumeWithException(IOException("This phone's key is missing: set it up again in Settings > Security", e))
                return@suspendCancellableCoroutine
            }
            val prompt = BiometricPrompt.Builder(activity)
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                .build()
            val cancel = CancellationSignal()
            cont.invokeOnCancellation { cancel.cancel() }
            prompt.authenticate(BiometricPrompt.CryptoObject(signature), cancel, activity.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        try {
                            val s = result.cryptoObject?.signature ?: error("no signature")
                            s.update(message)
                            if (cont.isActive) cont.resume(s.sign())
                        } catch (e: Exception) {
                            if (cont.isActive) cont.resumeWithException(IOException("Could not sign: ${e.message}", e))
                        }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (cont.isActive) cont.resumeWithException(PresenceCancelled(errString.toString()))
                    }
                })
        }
}
