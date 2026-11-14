package app.krewd.data

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class SecurityKeyException(message: String) : IOException(message)

/**
 * The owner's security key with FIDO U2F (every FIDO key speaks it, no PIN needed), over USB-C (plugged in, then
 * touched) or NFC (held to the back of the phone, which counts as the touch). The app only relays bytes: the
 * server checks every signature.
 */
object U2f {
    private val AID = byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x06, 0x47, 0x2F, 0x00, 0x01)
    private const val PERMISSION = "app.krewd.USB_PERMISSION"

    class Result(val registration: ByteArray?, val assertion: ByteArray)

    /** What the owner should do now, for the on-screen step. */
    fun interface Step {
        fun show(text: String)
    }

    /** One way to talk to the key: send an APDU, get the answer (data and status word). */
    private interface Transport : Closeable {
        val usb: Boolean
        fun apdu(cla: Int, ins: Int, p1: Int, p2: Int, data: ByteArray): ByteArray
    }

    fun hasNfc(context: Context): Boolean = NfcAdapter.getDefaultAdapter(context)?.isEnabled == true

    /**
     * Waits for the key: a FIDO key plugged in over USB (now or within a minute) or, where the phone has NFC,
     * one held to it. With [registerChallenge], registers a new credential first and signs with it; otherwise
     * signs with [keyHandle]. [signChallenge] is what the server expects signed; [appParam] comes from it too.
     */
    suspend fun tap(
        activity: Activity,
        appParam: ByteArray,
        signChallenge: ByteArray,
        keyHandle: ByteArray? = null,
        registerChallenge: ByteArray? = null,
        step: Step = Step {},
    ): Result {
        val work = { t: Transport -> talk(t, appParam, signChallenge, keyHandle, registerChallenge, step) }
        step.show(if (hasNfc(activity)) "Plug your security key into the phone (USB-C), or hold it to the back of the phone"
                  else "Plug your security key into the phone's USB-C port")
        return firstOf(activity, work, step)
    }

    /** USB polling and NFC reader mode at the same time; the first key found does the work. */
    private suspend fun firstOf(activity: Activity, work: (Transport) -> Result, step: Step): Result =
        suspendCancellableCoroutine { cont ->
            val taken = AtomicBoolean(false)
            val nfc = NfcAdapter.getDefaultAdapter(activity)?.takeIf { it.isEnabled }
            fun finish(result: kotlin.Result<Result>) {
                if (!taken.compareAndSet(false, true)) return
                runCatching { nfc?.disableReaderMode(activity) }
                if (cont.isActive) result.fold(cont::resume) { cont.resumeWithException(it) }
            }
            nfc?.enableReaderMode(activity, { tag: Tag ->
                if (taken.get()) return@enableReaderMode
                finish(runCatching { NfcTransport(tag).use(work) })
            }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 1000) })
            val usbJob = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launchUsb(activity, step, work, taken, ::finish)
            cont.invokeOnCancellation {
                taken.set(true)
                usbJob.cancel()
                runCatching { nfc?.disableReaderMode(activity) }
            }
        }

    private fun kotlinx.coroutines.CoroutineScope.launchUsb(
        activity: Activity, step: Step, work: (Transport) -> Result, taken: AtomicBoolean, finish: (kotlin.Result<Result>) -> Unit,
    ) = launch {
        val manager = activity.getSystemService(UsbManager::class.java) ?: return@launch
        val deadline = System.currentTimeMillis() + 90_000
        while (isActive && !taken.get() && System.currentTimeMillis() < deadline) {
            val found = manager.deviceList.values.firstNotNullOfOrNull { device -> fidoInterface(manager, device)?.let { device to it } }
            if (found != null) {
                val (device, iface) = found
                val allowed = manager.hasPermission(device) || askPermission(activity, manager, device)
                if (!allowed) {
                    finish(kotlin.Result.failure(SecurityKeyException("Allow Krewd to use the security key to continue")))
                    return@launch
                }
                step.show("Touch the security key (the gold contact) when it blinks")
                finish(runCatching { UsbTransport(manager, device, iface).use(work) })
                return@launch
            }
            delay(500)
        }
        if (!taken.get() && System.currentTimeMillis() >= deadline) finish(kotlin.Result.failure(SecurityKeyException("No security key found")))
    }

    /** A FIDO HID interface: HID class, 64-byte interrupt endpoints, usage page 0xF1D0 in its report descriptor. */
    private fun fidoInterface(manager: UsbManager, device: UsbDevice): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass != UsbConstants.USB_CLASS_HID || iface.endpointCount < 2) continue
            val eps = (0 until iface.endpointCount).map { iface.getEndpoint(it) }
            if (eps.none { it.direction == UsbConstants.USB_DIR_IN && it.maxPacketSize == 64 }) continue
            if (eps.none { it.direction == UsbConstants.USB_DIR_OUT && it.maxPacketSize == 64 }) continue
            // Without permission the descriptor cannot be read yet: a 64-byte HID interface is the candidate.
            if (!manager.hasPermission(device)) return iface
            val conn = manager.openDevice(device) ?: continue
            try {
                val buf = ByteArray(256)
                val n = conn.controlTransfer(0x81, 0x06, 0x2200, iface.id, buf, buf.size, 1000)
                val desc = if (n > 0) buf.copyOf(n) else ByteArray(0)
                val fido = (0 until desc.size - 2).any { desc[it] == 0x06.toByte() && desc[it + 1] == 0xD0.toByte() && desc[it + 2] == 0xF1.toByte() }
                if (fido || n <= 0) return iface
            } finally {
                conn.close()
            }
        }
        return null
    }

    private suspend fun askPermission(activity: Activity, manager: UsbManager, device: UsbDevice): Boolean =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        runCatching { activity.unregisterReceiver(this) }
                        if (cont.isActive) cont.resume(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                    }
                }
                activity.registerReceiver(receiver, IntentFilter(PERMISSION), Context.RECEIVER_NOT_EXPORTED)
                val intent = Intent(PERMISSION).setPackage(activity.packageName)
                manager.requestPermission(device, PendingIntent.getBroadcast(activity, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                cont.invokeOnCancellation { runCatching { activity.unregisterReceiver(receiver) } }
            }
        }

    private fun talk(t: Transport, appParam: ByteArray, signChallenge: ByteArray, keyHandle: ByteArray?, registerChallenge: ByteArray?, step: Step): Result {
        var registration: ByteArray? = null
        var handle = keyHandle
        if (registerChallenge != null) {
            registration = withPresence(t, step) { t.apdu(0x00, 0x01, 0x03, 0x00, registerChallenge + appParam) }
            handle = keyHandleOf(registration)
        }
        val h = handle ?: throw SecurityKeyException("No security key is registered yet")
        val assertion = withPresence(t, step) {
            t.apdu(0x00, 0x02, 0x03, 0x00, signChallenge + appParam + byteArrayOf(h.size.toByte()) + h)
        }
        return Result(registration, assertion)
    }

    private fun keyHandleOf(registration: ByteArray): ByteArray {
        if (registration.size < 67 || registration[0] != 0x05.toByte()) throw SecurityKeyException("The key's answer was not understood")
        val length = registration[66].toInt() and 0xff
        return registration.copyOfRange(67, 67 + length)
    }

    /** Until the key is touched it answers 0x6985: over USB keep asking (up to 30 s); over NFC it passes at once. */
    private fun withPresence(t: Transport, step: Step, block: () -> ByteArray): ByteArray {
        val deadline = System.currentTimeMillis() + if (t.usb) 30_000 else 3_000
        var asked = false
        while (true) {
            val answer = block()
            val sw = status(answer)
            if (sw == 0x9000) return answer.copyOf(answer.size - 2)
            if (sw == 0x6A80) throw SecurityKeyException("This is not the security key registered with Krewd")
            if (sw != 0x6985) throw SecurityKeyException("Security key answered %04X".format(sw))
            if (System.currentTimeMillis() > deadline) throw SecurityKeyException(if (t.usb) "The key was not touched in time" else "Hold the key against the phone a little longer")
            if (t.usb && !asked) { step.show("Touch the security key (the gold contact) when it blinks"); asked = true }
            Thread.sleep(200)
        }
    }

    private fun status(answer: ByteArray): Int {
        if (answer.size < 2) throw SecurityKeyException("The key's answer was cut off")
        return ((answer[answer.size - 2].toInt() and 0xff) shl 8) or (answer[answer.size - 1].toInt() and 0xff)
    }

    // NFC: ISO 7816 short APDUs, long answers fetched with GET RESPONSE

    private class NfcTransport(tag: Tag) : Transport {
        private val iso = IsoDep.get(tag) ?: throw SecurityKeyException("That is not a security key")
        override val usb = false

        init {
            iso.connect()
            iso.timeout = 5_000
            val selected = apdu(0x00, 0xA4, 0x04, 0x00, AID)
            if (status(selected) != 0x9000) throw SecurityKeyException("That is not a FIDO security key")
        }

        override fun apdu(cla: Int, ins: Int, p1: Int, p2: Int, data: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            var response = iso.transceive(byteArrayOf(cla.toByte(), ins.toByte(), p1.toByte(), p2.toByte(), data.size.toByte()) + data + byteArrayOf(0x00))
            while (true) {
                if (response.size < 2) throw SecurityKeyException("The key's answer was cut off")
                val sw1 = response[response.size - 2].toInt() and 0xff
                val sw2 = response[response.size - 1].toInt() and 0xff
                if (sw1 == 0x61) {
                    out.write(response, 0, response.size - 2)
                    response = iso.transceive(byteArrayOf(0x00, 0xC0.toByte(), 0x00, 0x00, sw2.toByte()))
                    continue
                }
                out.write(response, 0, response.size)
                return out.toByteArray()
            }
        }

        override fun close() = runCatching { iso.close() }.let {}
    }

    // USB: FIDO CTAPHID, 64-byte HID reports, U2F messages as extended-length APDUs

    private class UsbTransport(manager: UsbManager, device: UsbDevice, private val iface: UsbInterface) : Transport {
        override val usb = true
        private val conn: UsbDeviceConnection = manager.openDevice(device) ?: throw SecurityKeyException("Could not open the security key")
        private val input: UsbEndpoint
        private val output: UsbEndpoint
        private var cid = byteArrayOf(-1, -1, -1, -1)

        init {
            if (!conn.claimInterface(iface, true)) { conn.close(); throw SecurityKeyException("The security key is busy") }
            val eps = (0 until iface.endpointCount).map { iface.getEndpoint(it) }
            input = eps.first { it.direction == UsbConstants.USB_DIR_IN }
            output = eps.first { it.direction == UsbConstants.USB_DIR_OUT }
            val nonce = ByteArray(8).also { SecureRandom().nextBytes(it) }
            val answer = transact(0x86, nonce)  // CTAPHID_INIT on the broadcast channel
            if (answer.size < 12 || !answer.copyOf(8).contentEquals(nonce)) throw SecurityKeyException("The security key did not answer")
            cid = answer.copyOfRange(8, 12)
        }

        override fun apdu(cla: Int, ins: Int, p1: Int, p2: Int, data: ByteArray): ByteArray {
            val n = data.size
            val message = byteArrayOf(cla.toByte(), ins.toByte(), p1.toByte(), p2.toByte(), 0, (n shr 8).toByte(), n.toByte()) + data + byteArrayOf(0, 0)
            return transact(0x83, message)  // CTAPHID_MSG
        }

        private fun transact(cmd: Int, payload: ByteArray): ByteArray {
            // Send: an init packet (channel, command, length, 57 bytes), then continuation packets (59 bytes each).
            var offset = 0
            var seq = 0
            val first = ByteArray(64)
            cid.copyInto(first)
            first[4] = (cmd or 0x80).toByte()
            first[5] = (payload.size shr 8).toByte()
            first[6] = payload.size.toByte()
            val firstLen = minOf(57, payload.size)
            payload.copyInto(first, 7, 0, firstLen)
            write(first)
            offset += firstLen
            while (offset < payload.size) {
                val packet = ByteArray(64)
                cid.copyInto(packet)
                packet[4] = seq++.toByte()
                val len = minOf(59, payload.size - offset)
                payload.copyInto(packet, 5, offset, offset + len)
                write(packet)
                offset += len
            }
            // Receive: skip keep-alives, then collect the answer.
            while (true) {
                val packet = read()
                if (!packet.copyOf(4).contentEquals(cid)) continue
                val command = packet[4].toInt() and 0xff
                if (command == 0xBB) continue  // keep-alive
                if (command == 0xBF) throw SecurityKeyException("Security key error ${packet[7].toInt() and 0xff}")
                if (command != (cmd or 0x80)) continue
                val total = ((packet[5].toInt() and 0xff) shl 8) or (packet[6].toInt() and 0xff)
                val out = ByteArrayOutputStream()
                out.write(packet, 7, minOf(57, total))
                while (out.size() < total) {
                    val more = read()
                    if (!more.copyOf(4).contentEquals(cid)) continue
                    out.write(more, 5, minOf(59, total - out.size()))
                }
                return out.toByteArray()
            }
        }

        private fun write(packet: ByteArray) {
            if (conn.bulkTransfer(output, packet, packet.size, 2_000) < 0) throw SecurityKeyException("Could not talk to the security key")
        }

        private fun read(): ByteArray {
            val buf = ByteArray(64)
            if (conn.bulkTransfer(input, buf, buf.size, 5_000) < 0) throw SecurityKeyException("The security key stopped answering")
            return buf
        }

        override fun close() {
            runCatching { conn.releaseInterface(iface) }
            conn.close()
        }
    }
}
