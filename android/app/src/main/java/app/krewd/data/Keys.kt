package app.krewd.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * This phone's two keys, made inside its secure hardware (StrongBox where the phone has it) and never readable:
 * - the device key proves it is this phone; the app uses it without asking, for sessions;
 * - the presence key works only right after the owner's fingerprint, face or screen lock, once per use, for
 *   sensitive requests (a sign-in, a gateway key).
 */
object Keys {
    private const val DEVICE = "krewd_device_v1"
    private const val PRESENCE = "krewd_presence_v1"
    private const val STORE = "AndroidKeyStore"

    private val store: KeyStore get() = KeyStore.getInstance(STORE).apply { load(null) }

    /** Makes the keys if they are missing; returns their public keys (SubjectPublicKeyInfo, DER). */
    fun ensure(): Pair<ByteArray, ByteArray> {
        val ks = store
        if (!ks.containsAlias(DEVICE)) generate(DEVICE, presence = false)
        if (!ks.containsAlias(PRESENCE)) generate(PRESENCE, presence = true)
        return publicKey(DEVICE) to publicKey(PRESENCE)
    }

    fun exists(): Boolean = store.let { it.containsAlias(DEVICE) && it.containsAlias(PRESENCE) }

    /** Removes both keys (before pairing again, so a removed phone never comes back with its old keys). */
    fun delete() {
        val ks = store
        listOf(DEVICE, PRESENCE).forEach { if (ks.containsAlias(it)) ks.deleteEntry(it) }
    }

    fun signWithDevice(message: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(privateKey(DEVICE))
        update(message)
        sign()
    }

    /** A signature object for the presence key; usable only inside a fingerprint prompt (see [Presence]). */
    fun presenceSignature(): Signature = Signature.getInstance("SHA256withECDSA").apply { initSign(privateKey(PRESENCE)) }

    private fun publicKey(alias: String): ByteArray = store.getCertificate(alias).publicKey.encoded

    private fun privateKey(alias: String): PrivateKey = store.getKey(alias, null) as PrivateKey

    private fun generate(alias: String, presence: Boolean) {
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setIsStrongBoxBacked(strongBox)
            .apply {
                if (presence) {
                    setUserAuthenticationRequired(true)
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                    setInvalidatedByBiometricEnrollment(false)
                }
            }
            .build()
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, STORE)
        try {
            generator.initialize(spec(strongBox = true))
            generator.generateKeyPair()
        } catch (_: StrongBoxUnavailableException) {
            generator.initialize(spec(strongBox = false))
            generator.generateKeyPair()
        }
    }
}
