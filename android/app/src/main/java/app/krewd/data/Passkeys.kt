package app.krewd.data

import android.app.Activity
import android.util.Base64
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

class PasskeyException(message: String) : IOException(message)

/**
 * The owner's phone passkey (WebAuthn through Android's Credential Manager; kept and synced by the phone's
 * password manager, used with the fingerprint or screen lock). The app only relays the answers: the server
 * checks every signature, the relying party id and that the passkey was used by this app.
 */
object Passkeys {
    private const val B64URL = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

    fun b64url(data: ByteArray): String = Base64.encodeToString(data, B64URL)
    private fun unb64url(text: String): ByteArray = Base64.decode(text, B64URL)
    private fun b64(data: ByteArray): String = Base64.encodeToString(data, Base64.NO_WRAP)

    /** Makes a new passkey for Krewd on this phone. Returns {credential_id, client_data, attestation_object} for the server. */
    suspend fun create(activity: Activity, rpId: String, challenge: ByteArray, exclude: List<String>): JSONObject {
        val request = JSONObject()
            .put("challenge", b64url(challenge))
            .put("rp", JSONObject().put("id", rpId).put("name", "Krewd"))
            .put("user", JSONObject().put("id", b64url("krewd-owner".toByteArray())).put("name", "Krewd owner").put("displayName", "Krewd owner"))
            .put("pubKeyCredParams", JSONArray().put(JSONObject().put("type", "public-key").put("alg", -7)))
            .put("timeout", 120_000)
            .put("attestation", "none")
            .put("excludeCredentials", JSONArray(exclude.map { JSONObject().put("type", "public-key").put("id", it) }))
            .put("authenticatorSelection", JSONObject()
                .put("authenticatorAttachment", "platform")
                .put("residentKey", "required").put("requireResidentKey", true)
                .put("userVerification", "required"))
        val response = try {
            CredentialManager.create(activity).createCredential(activity, CreatePublicKeyCredentialRequest(request.toString()))
        } catch (e: CreateCredentialCancellationException) {
            throw PasskeyException("Cancelled")
        } catch (e: CreateCredentialException) {
            throw PasskeyException(explain(e.errorMessage?.toString() ?: e.type))
        }
        val json = JSONObject((response as? CreatePublicKeyCredentialResponse)?.registrationResponseJson
            ?: throw PasskeyException("The phone did not make a passkey"))
        val r = json.getJSONObject("response")
        return JSONObject()
            .put("credential_id", json.getString("id"))
            .put("client_data", b64(unb64url(r.getString("clientDataJSON"))))
            .put("attestation_object", b64(unb64url(r.getString("attestationObject"))))
    }

    /** Signs [challenge] with one of the owner's passkeys ([allow]: their credential ids, base64url). */
    suspend fun sign(activity: Activity, rpId: String, challenge: ByteArray, allow: List<String>): JSONObject {
        val request = JSONObject()
            .put("challenge", b64url(challenge))
            .put("rpId", rpId)
            .put("timeout", 120_000)
            .put("userVerification", "required")
            .put("allowCredentials", JSONArray(allow.map { JSONObject().put("type", "public-key").put("id", it) }))
        val result = try {
            CredentialManager.create(activity).getCredential(activity, GetCredentialRequest(listOf(GetPublicKeyCredentialOption(request.toString()))))
        } catch (e: GetCredentialCancellationException) {
            throw PasskeyException("Cancelled")
        } catch (e: NoCredentialException) {
            throw PasskeyException("No Krewd passkey on this phone (is it signed in to the same Google account?)")
        } catch (e: GetCredentialException) {
            throw PasskeyException(explain(e.errorMessage?.toString() ?: e.type))
        }
        val credential = result.credential as? PublicKeyCredential ?: throw PasskeyException("That was not a passkey")
        val json = JSONObject(credential.authenticationResponseJson)
        val r = json.getJSONObject("response")
        return JSONObject()
            .put("credential_id", json.getString("id"))
            .put("client_data", b64(unb64url(r.getString("clientDataJSON"))))
            .put("authenticator_data", b64(unb64url(r.getString("authenticatorData"))))
            .put("signature", b64(unb64url(r.getString("signature"))))
    }

    private fun explain(message: String): String =
        if (message.contains("RP ID", ignoreCase = true) || message.contains("asset", ignoreCase = true) || message.contains("domain", ignoreCase = true))
            "Android could not link Krewd to the passkey domain: check its /.well-known/assetlinks.json ($message)"
        else message
}
