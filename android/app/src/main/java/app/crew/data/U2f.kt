package app.crew.data

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class SecurityKeyException(message: String) : IOException(message)

/**
 * The owner's security key over NFC, with FIDO U2F (every FIDO key speaks it, no PIN needed; holding the key to
 * the phone counts as the touch). The app only relays bytes: the server checks every signature.
 */
object U2f {
    private val AID = byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x06, 0x47, 0x2F, 0x00, 0x01)

    class Result(val registration: ByteArray?, val assertion: ByteArray)

    /**
     * Waits for a key tap. With [registerChallenge], registers a new credential first and signs with it; otherwise
     * signs with [keyHandle]. [signChallenge] is what the server expects signed. [appParam]: from the server.
     */
    suspend fun tap(
        activity: Activity,
        appParam: ByteArray,
        signChallenge: ByteArray,
        keyHandle: ByteArray? = null,
        registerChallenge: ByteArray? = null,
    ): Result = suspendCancellableCoroutine { cont ->
        val nfc = NfcAdapter.getDefaultAdapter(activity)
        if (nfc == null || !nfc.isEnabled) {
            cont.resumeWithException(SecurityKeyException(if (nfc == null) "This phone has no NFC" else "Turn on NFC to use the security key"))
            return@suspendCancellableCoroutine
        }
        val callback = NfcAdapter.ReaderCallback { tag: Tag ->
            val result = runCatching { talk(tag, appParam, signChallenge, keyHandle, registerChallenge) }
            runCatching { nfc.disableReaderMode(activity) }
            if (cont.isActive) result.fold(cont::resume) { cont.resumeWithException(it) }
        }
        val options = Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 1000) }
        nfc.enableReaderMode(activity, callback,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, options)
        cont.invokeOnCancellation { runCatching { nfc.disableReaderMode(activity) } }
    }

    private fun talk(tag: Tag, appParam: ByteArray, signChallenge: ByteArray, keyHandle: ByteArray?, registerChallenge: ByteArray?): Result {
        val iso = IsoDep.get(tag) ?: throw SecurityKeyException("That is not a security key")
        iso.use {
            it.connect()
            it.timeout = 5_000
            transmit(it, apdu(0x00, 0xA4, 0x04, 0x00, AID))
            var registration: ByteArray? = null
            var handle = keyHandle
            if (registerChallenge != null) {
                registration = withPresence { transmit(it, apdu(0x00, 0x01, 0x03, 0x00, registerChallenge + appParam)) }
                handle = keyHandleOf(registration)
            }
            val h = handle ?: throw SecurityKeyException("No security key is registered yet")
            val assertion = withPresence {
                transmit(it, apdu(0x00, 0x02, 0x03, 0x00, signChallenge + appParam + byteArrayOf(h.size.toByte()) + h))
            }
            return Result(registration, assertion)
        }
    }

    private fun keyHandleOf(registration: ByteArray): ByteArray {
        if (registration.size < 67 || registration[0] != 0x05.toByte()) throw SecurityKeyException("The key's answer was not understood")
        val length = registration[66].toInt() and 0xff
        return registration.copyOfRange(67, 67 + length)
    }

    /** A key that wants a touch answers 0x6985 for a moment; on NFC that passes by itself. */
    private fun withPresence(block: () -> ByteArray): ByteArray {
        repeat(20) {
            try {
                return block()
            } catch (e: StatusException) {
                if (e.sw != 0x6985) throw SecurityKeyException(e.message ?: "Security key error")
                Thread.sleep(150)
            }
        }
        throw SecurityKeyException("Hold the key against the phone a little longer")
    }

    private class StatusException(val sw: Int) : IOException("Security key answered %04X".format(sw))

    private fun apdu(cla: Int, ins: Int, p1: Int, p2: Int, data: ByteArray): ByteArray =
        byteArrayOf(cla.toByte(), ins.toByte(), p1.toByte(), p2.toByte(), data.size.toByte()) + data + byteArrayOf(0x00)

    /** Sends an APDU and collects a long answer (61XX: GET RESPONSE for the rest). */
    private fun transmit(iso: IsoDep, command: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var response = iso.transceive(command)
        while (true) {
            if (response.size < 2) throw SecurityKeyException("The key's answer was cut off")
            val sw1 = response[response.size - 2].toInt() and 0xff
            val sw2 = response[response.size - 1].toInt() and 0xff
            out.write(response, 0, response.size - 2)
            if (sw1 == 0x61) {
                response = iso.transceive(byteArrayOf(0x00, 0xC0.toByte(), 0x00, 0x00, sw2.toByte()))
                continue
            }
            val sw = (sw1 shl 8) or sw2
            if (sw != 0x9000) throw StatusException(sw)
            return out.toByteArray()
        }
    }
}
