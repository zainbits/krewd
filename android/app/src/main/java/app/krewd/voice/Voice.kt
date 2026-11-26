package app.krewd.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import app.krewd.data.ApiException
import app.krewd.data.KrewdApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Voice input on the phone (the server side is voice.py). In voice mode the microphone is on all the time and cut
 * into pieces of speech ([Cutter]: up to 30 s, ending after 2 s of quiet); each piece is kept as a file in the app's
 * own storage until the server has it (so nothing is lost while offline or while the server's model loads), then
 * deleted. The server answers with the words split by who said them (Me or Others, several lines when both spoke),
 * and they go into the chat's composer as `Me: ...` / `Others: ...` lines ([heard], [joinLine]). The owner edits them
 * and presses Send; nothing is sent by itself.
 *
 * Live mode (an experiment next to the pieces, picked when voice mode starts): no pieces; the audio goes to the server about every
 * 250 ms and the words come back while they are said, one segment per speaker turn: shown grey under the voice bar
 * while it is heard ([VoiceUi.liveLines]), then into the composer once final. Send first makes everything final.
 *
 * [VoiceService] (a microphone foreground service) owns the running [Listener]; the UI reads [state] and calls the
 * functions here. One chat is in voice mode at a time. Mute releases the microphone (Android's mic indicator goes
 * off); only the app's screen unmutes.
 */
object Voice {
    private val _state = MutableStateFlow(VoiceUi())
    val state: StateFlow<VoiceUi> = _state.asStateFlow()

    @Volatile internal var connection: suspend () -> KrewdApi = { throw IllegalStateException("Krewd is not connected") }
    /** Where heard words go: the ViewModel adds them to the chat's composer draft (chat id, label, words). */
    @Volatile internal var heard: (String, String, String) -> Unit = { _, _, _ -> }
    @Volatile internal var listener: Listener? = null

    /** The ViewModel hands over its connection ([wait] returns once the app is online) and the composer drafts. */
    fun bind(wait: suspend () -> KrewdApi, onHeard: (chatId: String, label: String, text: String) -> Unit) {
        connection = wait
        heard = onHeard
    }

    fun hasMic(context: Context) = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Voice mode on in [chatId] (the composer's mic): the microphone listens until [leave]. Needs the app in front. */
    fun start(context: Context, chatId: String) {
        _state.value = VoiceUi(
            chatId = chatId, listening = true, starting = true, since = System.currentTimeMillis(),
            myVoice = prefs(context).getBoolean("my_voice", true), enrolled = _state.value.enrolled,
            live = prefs(context).getBoolean("live_mode", false), liveAvailable = _state.value.liveAvailable,
        )
        context.startForegroundService(Intent(context, VoiceService::class.java).putExtra(VoiceService.EXTRA_CHAT, chatId))
    }

    /** Voice mode off (the mic tapped again): the words still on their way go into the composer, then it ends. */
    fun leave(context: Context) {
        context.startService(Intent(context, VoiceService::class.java).setAction(VoiceService.ACTION_LEAVE))
    }

    /** Mute releases the microphone; unmuting is only offered on the app's screen (Android needs it in front). */
    fun setMuted(muted: Boolean) {
        val l = listener ?: return
        if (muted) l.mute() else l.unmute()
    }

    /** Pieces or Live for the next voice mode (picked before it starts; a running one keeps its mode). */
    fun setLive(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("live_mode", on).apply()
        if (!_state.value.listening) _state.update { it.copy(live = on) }
    }

    fun setMyVoice(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("my_voice", on).apply()
        _state.update { it.copy(myVoice = on) }
    }

    /**
     * Before Send: the words being said or transcribed now go into the composer first. Ends the piece being heard,
     * then waits until the server has answered for every piece of [chatId], at most [timeoutMs]. False on time out.
     */
    suspend fun settle(chatId: String, timeoutMs: Long = 10_000): Boolean {
        val l = listener?.takeIf { it.chatId == chatId } ?: return true
        _state.update { it.copy(settling = true) }
        return try {
            withTimeoutOrNull(timeoutMs) { l.settle(); true } ?: false
        } finally {
            _state.update { it.copy(settling = false) }
        }
    }

    /** Whether My voice is set up and the server's voice support; null on failure. */
    suspend fun refreshStatus(): JSONObject? = runCatching {
        val o = withContext(Dispatchers.IO) { connection().voiceStatus() }
        _state.update { it.copy(enrolled = o.optBoolean("enrolled"), available = o.optBoolean("available"),
            problems = o.optJSONArray("problems")?.strings().orEmpty(), liveAvailable = o.optBoolean("live")) }
        o
    }.getOrNull()

    internal fun set(block: (VoiceUi) -> VoiceUi) = _state.update(block)

    internal fun prefs(context: Context) = context.getSharedPreferences("krewd_voice", Context.MODE_PRIVATE)

    private fun JSONArray.strings() = (0 until length()).map { optString(it) }

    /**
     * [draft] with heard [text] from [label] added at the end: on the last line when that line is the same
     * speaker's ("Others: one. Two."), else on a new line ("Me: ...").
     */
    fun joinLine(draft: String, label: String, text: String): String {
        val words = text.trim()
        if (words.isEmpty()) return draft
        val prefix = "$label: "
        val before = draft.trimEnd()
        if (before.isEmpty()) return prefix + words
        return if (before.substringAfterLast('\n').startsWith(prefix)) "$before $words" else "$before\n$prefix$words"
    }

    // Setting up My voice: about 30 s of the owner reading aloud, sent once, never kept on the phone (the server keeps 15 s).

    /**
     * Records [seconds] of the owner reading and sends it. While a chat listens, the frames come from its mic (the
     * reading does not go into the composer); otherwise, or while muted, from a recorder of its own. Fails instead
     * of hanging when the mic gives nothing.
     */
    @SuppressLint("MissingPermission") // the sheet asks for the microphone first
    suspend fun enroll(seconds: Int, progress: (Float, Float) -> Unit): Result<JSONObject> = withContext(Dispatchers.IO) {
        runCatching {
            val total = seconds * RATE
            val out = java.io.ByteArrayOutputStream(total * 2)
            val bytes = java.nio.ByteBuffer.allocate(FRAME * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            var got = 0
            fun take(frame: ShortArray, n: Int): Boolean {
                bytes.clear()
                for (i in 0 until n) bytes.putShort(frame[i])
                out.write(bytes.array(), 0, n * 2)
                got += n
                progress(min(1f, got / total.toFloat()), rms(frame, n).toFloat() / 6000f)
                return got >= total
            }
            val l = listener?.takeIf { it.capturing }
            if (l != null) {
                val done = kotlinx.coroutines.CompletableDeferred<Unit>()
                l.tap = { frame, n -> if (!done.isCompleted && take(frame, n)) done.complete(Unit) }
                try {
                    withTimeoutOrNull((seconds + 10) * 1000L) { done.await() }
                        ?: throw IllegalStateException("The microphone gave no sound")
                } finally {
                    l.tap = null
                }
            } else {
                val rec = recorder()
                val frame = ShortArray(FRAME)
                val deadline = SystemClock.elapsedRealtime() + (seconds + 10) * 1000L
                try {
                    rec.startRecording()
                    check(rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "The microphone is busy" }
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext()[Job]?.let { if (!it.isActive) throw kotlinx.coroutines.CancellationException() }
                        check(SystemClock.elapsedRealtime() < deadline) { "The microphone gave no sound" }
                        val n = rec.read(frame, 0, FRAME)
                        check(n >= 0) { "The microphone failed ($n)" }
                        if (n > 0 && take(frame, n)) break
                    }
                } finally {
                    runCatching { rec.stop() }
                    rec.release()
                }
            }
            val result = connection().voiceEnroll(out.toByteArray())
            _state.update { it.copy(enrolled = true) }
            result
        }.onFailure { Log.w(TAG, "voice: setting up My voice failed: $it") }
    }

    suspend fun forgetVoiceprint(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { connection().voiceForget(); _state.update { it.copy(enrolled = false) } }
    }

    @SuppressLint("MissingPermission")
    internal fun recorder(): AudioRecord {
        val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(max(minimum, RATE * 2))
            .build()
            .also { check(it.state == AudioRecord.STATE_INITIALIZED) { "The microphone is busy or not allowed" } }
    }

    internal fun rms(frame: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) sum += frame[i].toDouble() * frame[i]
        return if (n > 0) sqrt(sum / n) else 0.0
    }

    const val RATE = 16_000
    const val FRAME = 320 // 20 ms
    const val TAG = "KrewdVoice"
}

data class VoiceUi(
    /** The chat in voice mode now (null: none). */
    val chatId: String? = null,
    /** Voice mode is on. */
    val listening: Boolean = false,
    /** The microphone is released (voice mode stays on). */
    val muted: Boolean = false,
    /** Asking the server to start, or the transcription model is loading. */
    val starting: Boolean = false,
    /** Leaving voice mode: the last words are on their way into the composer. */
    val stopping: Boolean = false,
    /** Send waits for the last words. */
    val settling: Boolean = false,
    val since: Long = 0,
    val myVoice: Boolean = true,
    /** The owner's voice sample is on the server (My voice set up); null while unknown. */
    val enrolled: Boolean? = null,
    val available: Boolean? = null,
    val problems: List<String> = emptyList(),
    val speaking: Boolean = false,
    val level: Float = 0f,
    /** Pieces recorded but not yet transcribed by the server. */
    val waiting: Int = 0,
    /** Why pieces wait: "offline", "server" (busy, loading or failing); null when they flow. */
    val holdUp: String? = null,
    /** Why voice mode ended or the microphone stopped; shown in the chat. */
    val error: String? = null,
    /** Live mode is picked (the owner's switch). */
    val live: Boolean = false,
    /** The server offers Live mode; null while unknown. */
    val liveAvailable: Boolean? = null,
    /** Live mode: the words being heard now, not final yet (one line per speaker turn; label null while unknown). */
    val liveLines: List<LiveLine> = emptyList(),
)

data class LiveLine(val label: String?, val text: String)

/**
 * Voice mode in one chat: the capture thread while not muted (mic -> [Cutter] -> piece files) and the sender (piece
 * files -> server, in order, with retries -> words into the composer). Files live in the app's own storage until the
 * server answers for them.
 */
internal class Listener(private val context: Context, val chatId: String, private val ended: (String?) -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dir = File(context.filesDir, "voice/$chatId").apply { mkdirs() }
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val seq = AtomicLong(System.currentTimeMillis()) // unique and rising across restarts of the app
    private val mic = Mutex() // one capture thread at a time
    @Volatile var voiceId: String? = null
    /** Set while Set up My voice records from this mic: frames go there instead of to the chat. */
    @Volatile var tap: ((ShortArray, Int) -> Unit)? = null
    @Volatile private var active = true
    /** The capture thread runs (not muted). */
    @Volatile var capturing = false
        private set
    @Volatile private var cutAsked = false
    private var thread: Thread? = null
    /** Live mode (fixed for this voice session): audio goes to the server as it comes, no pieces. */
    val live = Voice.prefs(context).getBoolean("live_mode", false)
    private val liveAudio = java.io.ByteArrayOutputStream() // heard, not sent yet (guarded by itself)
    private var livePeak = 3000.0
    private var after = 0L // the first live segment not in the composer yet
    private val flushes = java.util.concurrent.ConcurrentLinkedQueue<kotlinx.coroutines.CompletableDeferred<Unit>>()
    private var sender: Job? = null

    fun start() {
        // Pieces left by a chat whose voice mode ended with the app (killed before they were sent) are not kept.
        File(context.filesDir, "voice").listFiles()?.filter { it.name != chatId && it.lastModified() < System.currentTimeMillis() - 3_600_000 }
            ?.forEach { it.deleteRecursively() }
        unmute()
        sender = scope.launch { if (live) sendLive() else send() }
    }

    fun unmute() {
        if (!active) return
        scope.launch {
            mic.withLock {
                if (!active || capturing) return@withLock
                withContext(Dispatchers.IO) { thread?.join(2_000) }
                capturing = true
                Voice.set { it.copy(muted = false, error = null) }
                thread = Thread(::capture, "krewd-voice").also { it.start() }
            }
        }
    }

    fun mute() {
        capturing = false // the capture thread sends the piece it holds and lets go of the microphone
        if (live) { // the words being heard become final now, not when the server notices the quiet
            flushes.add(kotlinx.coroutines.CompletableDeferred())
            wake.trySend(Unit)
        }
        Voice.set { it.copy(muted = true, speaking = false, level = 0f) }
    }

    /** Leave voice mode: stop the mic, send what is left (up to 30 s), end the server's voice session, then [ended]. */
    fun stop(reason: String?) {
        if (!active) return
        active = false
        capturing = false
        Voice.set { it.copy(stopping = true) }
        scope.launch {
            withContext(Dispatchers.IO) { thread?.join(2_000) }
            val drained = withTimeoutOrNull(30_000) {
                if (live) {
                    wake.trySend(Unit)
                    sender?.join()
                }
                while (pending().isNotEmpty()) delay(200)
                true
            }
            if (drained == null) {
                Log.w(Voice.TAG, "voice: ${pending().size} pieces not sent within 30 s of leaving voice mode; deleted")
            }
            dir.listFiles()?.forEach { it.delete() }
            voiceId?.let { vid -> runCatching { Voice.connection().voiceStop(chatId, vid) } }
            ended(reason ?: if (drained == null) "The last words could not be sent" else null)
            scope.cancel()
        }
    }

    /** Ends the piece being heard now, then waits until the server has answered for every piece. */
    suspend fun settle() {
        if (live) {
            val done = kotlinx.coroutines.CompletableDeferred<Unit>()
            flushes.add(done)
            wake.trySend(Unit)
            done.await()
            return
        }
        if (capturing) {
            cutAsked = true
            while (cutAsked && capturing) delay(20)
        }
        while (pending().isNotEmpty()) delay(100)
    }

    // capture

    private fun capture() {
        val rec = try {
            Voice.recorder()
        } catch (e: Exception) {
            Log.w(Voice.TAG, "voice: no microphone: $e")
            capturing = false
            Voice.set { it.copy(muted = true, error = "The microphone could not be opened") }
            return
        }
        val cutter = Cutter(piece = ::save)
        val frame = ShortArray(Voice.FRAME)
        var lastLevel = 0L
        try {
            rec.startRecording()
            while (capturing) {
                val n = rec.read(frame, 0, Voice.FRAME)
                if (n < 0) {
                    Log.w(Voice.TAG, "voice: microphone read failed ($n)")
                    capturing = false
                    Voice.set { it.copy(muted = true, error = "The microphone stopped") }
                    break
                }
                if (n == 0) continue
                if (cutAsked) {
                    cutter.cut()
                    cutAsked = false
                }
                tap?.let { t ->
                    t(frame, n)
                    continue
                }
                if (live) liveFeed(frame, n) else cutter.feed(frame, n)
                val now = SystemClock.elapsedRealtime()
                if (now - lastLevel > 100) {
                    lastLevel = now
                    val rms = Voice.rms(frame, n)
                    val level = min(1f, (rms / 5000.0).toFloat())
                    Voice.set { it.copy(level = level, speaking = if (live) rms > 600 else cutter.inSpeech) }
                }
            }
            if (live) {
                wake.trySend(Unit) // the last audio goes with the next request
            } else {
                cutter.cut()
            }
        } finally {
            cutAsked = false
            runCatching { rec.stop() }
            rec.release()
        }
    }

    private fun save(pcm: ShortArray) {
        // Phone-like gain: the loudest sample at about -3 dB, at most 20x (the same as the hearing test's audio).
        var peak = 1
        for (s in pcm) peak = max(peak, kotlin.math.abs(s.toInt()))
        val gain = min(20.0, 0.7 * 32767 / peak)
        val bytes = java.nio.ByteBuffer.allocate(pcm.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (s in pcm) bytes.putShort((s * gain).toInt().coerceIn(-32768, 32767).toShort())
        // The name keeps whether "My voice" was on when it was said: 1 keeps the owner's words, 0 leaves them out.
        val myVoice = if (Voice.state.value.myVoice) 1 else 0
        val f = File(dir, "%016d.%d.pcm".format(seq.incrementAndGet(), myVoice))
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeBytes(bytes.array())
        tmp.renameTo(f)
        trim()
        wake.trySend(Unit)
        Voice.set { it.copy(waiting = pending().size) }
    }

    /** Offline for long: keep the newest [MAX_WAITING_BYTES] of pieces (about 20 minutes of talk). */
    private fun trim() {
        var total = 0L
        for (f in pending().asReversed()) {
            total += f.length()
            if (total > MAX_WAITING_BYTES) f.delete()
        }
    }

    private fun pending(): List<File> = dir.listFiles { f -> f.name.endsWith(".pcm") }?.sortedBy { it.name }.orEmpty()

    // sending

    private suspend fun send() {
        // Files of an older app version that are not pieces of speech (pauses) are not sent.
        dir.listFiles { f -> !f.name.endsWith(".pcm") && !f.name.endsWith(".tmp") }?.forEach { it.delete() }
        var backoff = 1_000L
        while (true) {
            if (voiceId == null && active) {
                // Start the server's voice session at once: it also wakes the transcription model.
                val started = try {
                    val api = withTimeoutOrNull(5_000) { Voice.connection() }
                    if (api == null) Voice.set { it.copy(holdUp = "offline") } else begin(api)
                    api != null
                } catch (e: ApiException) {
                    if (e.code == 503 || e.code == 404) { // voice input is off on the server, or the chat is gone
                        stop(e.message ?: "Voice input is not available")
                        return
                    }
                    false
                } catch (e: java.io.IOException) {
                    Voice.set { it.copy(holdUp = "offline") }
                    false
                }
                if (!started) {
                    delay(backoff + (Math.random() * 500).toLong())
                    backoff = min(backoff * 2, 30_000L)
                    continue
                }
                backoff = 1_000L
            }
            val next = pending().firstOrNull()
            if (next == null) {
                Voice.set { it.copy(waiting = 0, holdUp = null) }
                if (!active) return
                withTimeoutOrNull(1_000) { wake.receive() }
                continue
            }
            val ok = try {
                val api = withTimeoutOrNull(5_000) { Voice.connection() }
                if (api == null) {
                    Voice.set { it.copy(holdUp = "offline", waiting = pending().size) }
                    false
                } else {
                    deliver(api, voiceId ?: begin(api), next)
                    true
                }
            } catch (e: ApiException) {
                when {
                    e.code == 409 -> { voiceId = null; false } // the server lost the voice session (restart): start again
                    e.code in 400..499 && e.code != 429 -> {
                        Log.w(Voice.TAG, "voice: piece refused (${e.code}): ${e.message}")
                        next.delete()
                        true
                    }
                    else -> {
                        Voice.set { it.copy(holdUp = "server", waiting = pending().size) }
                        false
                    }
                }
            } catch (e: java.io.IOException) {
                Voice.set { it.copy(holdUp = "offline", waiting = pending().size) }
                false
            }
            if (ok) {
                backoff = 1_000L
            } else {
                delay(backoff + (Math.random() * 500).toLong())
                backoff = min(backoff * 2, 30_000L)
            }
        }
    }


    // Live mode

    /** A frame of the mic into the live audio, with a slow automatic gain (the loudest of the last seconds at about
     *  -3 dB, at most 20x, like the pieces), so a quiet phone mic still reaches the models. */
    private fun liveFeed(frame: ShortArray, n: Int) {
        var peak = 0
        for (i in 0 until n) peak = max(peak, kotlin.math.abs(frame[i].toInt()))
        livePeak = max(max(peak.toDouble(), 1500.0), livePeak * 0.995)
        val gain = min(20.0, 0.7 * 32767 / livePeak)
        val bytes = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (frame[i] * gain).toInt().coerceIn(-32768, 32767)
            bytes[2 * i] = (v and 0xff).toByte()
            bytes[2 * i + 1] = (v shr 8).toByte()
        }
        synchronized(liveAudio) {
            liveAudio.write(bytes)
            if (liveAudio.size() > LIVE_KEEP_BYTES) { // offline for long: keep the newest 2 minutes
                val all = liveAudio.toByteArray()
                liveAudio.reset()
                liveAudio.write(all, all.size - LIVE_KEEP_BYTES, LIVE_KEEP_BYTES)
            }
        }
    }

    private fun takeLive(): ByteArray = synchronized(liveAudio) {
        val all = liveAudio.toByteArray()
        val n = min(all.size, LIVE_MAX_SEND_BYTES)
        liveAudio.reset()
        if (n < all.size) liveAudio.write(all, n, all.size - n)
        all.copyOf(n)
    }

    private fun liveWaiting(): Boolean = synchronized(liveAudio) { liveAudio.size() > 0 }

    /** Audio to the server about every 250 ms (one request at a time, a failed one sent again with its number), and
     *  the words back: final segments into the composer, the others under the voice bar. Ends after the last audio
     *  once voice mode is left. */
    private suspend fun sendLive() {
        var backoff = 1_000L
        var chunk: ByteArray? = null
        var chunkSeq = 0L
        var chunkFlush = 0
        var chunkEnd = false
        while (true) {
            if (voiceId == null) {
                if (!active && !liveWaiting() && chunk == null) break
                val started = try {
                    val api = withTimeoutOrNull(5_000) { Voice.connection() }
                    if (api == null) Voice.set { it.copy(holdUp = "offline") } else begin(api)
                    api != null
                } catch (e: ApiException) {
                    if (e.code == 503 || e.code == 404) {
                        stop(e.message ?: "Voice input is not available")
                        break
                    }
                    false
                } catch (e: java.io.IOException) {
                    Voice.set { it.copy(holdUp = "offline") }
                    false
                }
                if (!started) {
                    delay(backoff + (Math.random() * 500).toLong())
                    backoff = min(backoff * 2, 30_000L)
                    continue
                }
                backoff = 1_000L
            }
            if (chunk == null) {
                if (active && flushes.isEmpty()) withTimeoutOrNull(LIVE_SEND_MS) { wake.receive() }
                if (active && flushes.isEmpty() && capturing && !liveWaiting()) continue
                val flushing = flushes.size
                val ending = !active
                val audio = takeLive()
                if (audio.isEmpty() && flushing == 0 && !ending) {
                    withTimeoutOrNull(1_000) { wake.receive() } // muted: nothing to send
                    continue
                }
                chunk = audio
                chunkSeq = seq.incrementAndGet()
                chunkFlush = flushing
                chunkEnd = ending && !liveWaiting()
            }
            val sent = try {
                val api = withTimeoutOrNull(5_000) { Voice.connection() }
                if (api == null) {
                    Voice.set { it.copy(holdUp = "offline") }
                    false
                } else {
                    val vid = voiceId ?: begin(api)
                    val r = api.voiceLive(chatId, vid, chunkSeq, after, chunkFlush > 0, chunkEnd, chunk!!)
                    liveAnswer(r)
                    true
                }
            } catch (e: ApiException) {
                when {
                    e.code == 409 -> { voiceId = null; false } // the server lost the voice session: start again
                    e.code in 400..499 && e.code != 429 -> {
                        Log.w(Voice.TAG, "voice: live audio refused (${e.code}): ${e.message}")
                        true // that audio is dropped
                    }
                    else -> {
                        Voice.set { it.copy(holdUp = "server", error = e.message) }
                        false
                    }
                }
            } catch (e: java.io.IOException) {
                Voice.set { it.copy(holdUp = "offline") }
                false
            }
            if (sent) {
                backoff = 1_000L
                repeat(chunkFlush) { flushes.poll()?.complete(Unit) }
                val ended = chunkEnd
                chunk = null
                if (ended) break
            } else {
                delay(backoff + (Math.random() * 500).toLong())
                backoff = min(backoff * 2, 30_000L)
            }
        }
        while (true) flushes.poll()?.complete(Unit) ?: break
        Voice.set { it.copy(liveLines = emptyList()) }
    }

    private fun liveAnswer(r: JSONObject) {
        val segments = r.optJSONArray("segments") ?: JSONArray()
        val lines = mutableListOf<LiveLine>()
        val myVoice = Voice.state.value.myVoice
        for (i in 0 until segments.length()) {
            val seg = segments.optJSONObject(i) ?: continue
            val id = seg.optLong("id", -1)
            if (id < after) continue
            val text = seg.optString("text").trim()
            val mine = seg.optString("speaker") == "me"
            val label = seg.optString("label").takeIf { it.isNotBlank() && it != "null" }
            if (seg.optBoolean("final")) {
                if (text.isNotEmpty() && (!mine || myVoice)) Voice.heard(chatId, label ?: "Others", text)
                after = id + 1
            } else if (text.isNotEmpty() && (!mine || myVoice)) {
                lines.add(LiveLine(label, text))
            }
        }
        val status = r.optString("status")
        Voice.set {
            it.copy(liveLines = lines, holdUp = null, starting = status == "queued" || status == "starting",
                error = if (it.holdUp == "server") null else it.error)
        }
    }

    private fun begin(api: KrewdApi): String {
        after = 0
        val o = api.voiceStart(chatId, live)
        Voice.set { it.copy(starting = false, enrolled = o.optBoolean("enrolled"), holdUp = null) }
        return o.getString("voice_id").also { voiceId = it }
    }

    private fun deliver(api: KrewdApi, vid: String, f: File) {
        val n = f.name.substringBefore('.').toLong()
        val r = api.voiceAudio(chatId, vid, n, f.readBytes())
        val lines = r.optJSONArray("lines") ?: JSONArray()
        for (i in 0 until lines.length()) {
            val line = lines.optJSONObject(i) ?: continue
            val text = line.optString("text").trim()
            val mine = line.optString("speaker") == "me"
            if (text.isNotEmpty() && (!mine || !f.name.contains(".0."))) {
                Voice.heard(chatId, line.optString("label").ifBlank { if (mine) "Me" else "Others" }, text)
            }
        }
        f.delete() // the words are in the composer (its draft is saved): the audio goes
        Voice.set { it.copy(waiting = pending().size, holdUp = null) }
    }

    companion object {
        const val MAX_WAITING_BYTES = 20L * 60 * Voice.RATE * 2
        const val LIVE_SEND_MS = 250L
        const val LIVE_MAX_SEND_BYTES = 8 * Voice.RATE * 2 // the server takes 10 s per request
        const val LIVE_KEEP_BYTES = 120 * Voice.RATE * 2
    }
}

/**
 * Cuts the microphone into pieces of speech by loudness against a moving quiet floor. A piece starts after 60 ms of
 * speech (with 300 ms before it) and ends after 2 s of quiet (keeping 200 ms of it), so the short pauses of talking
 * stay inside one piece: the server splits a piece by speaker and needs a few seconds of each voice. A piece that
 * reaches 30 s is cut in the middle of its quietest 260 ms within the last 10 s (the rest starts the next piece), the
 * same rule as ZnKeyboard's voice typing. A piece with under 400 ms of speech is dropped (a cough, a click).
 * [cut] ends the piece at once (Send, Mute, leaving voice mode).
 */
internal class Cutter(private val piece: (ShortArray) -> Unit) {
    private var floor = 300.0
    private val before = ArrayDeque<ShortArray>()
    private var current: ArrayList<ShortArray>? = null
    private val levels = ArrayList<Double>() // loudness of each frame of [current]
    private val loud = ArrayList<Boolean>() // whether each frame of [current] is speech
    private var onset = 0
    private var quiet = 0
    val inSpeech: Boolean get() = current != null

    fun feed(frame: ShortArray, n: Int): Boolean {
        val f = frame.copyOf(n)
        val level = Voice.rms(f, n)
        val speech = level > max(floor * 3.0, 350.0)
        floor = when {
            !speech && level < floor -> floor * 0.9 + level * 0.1
            !speech -> floor * 0.998 + level * 0.002
            else -> floor * 0.9995 + level * 0.0005
        }.coerceAtLeast(30.0)
        val cur = current
        if (cur == null) {
            before.addLast(f)
            if (before.size > 15) before.removeFirst()
            onset = if (speech) onset + 1 else max(0, onset - 1)
            if (onset >= 3) {
                current = ArrayList(before)
                before.forEach { levels.add(Voice.rms(it, it.size)) }
                // Of the frames before the start, only the last [onset] count as speech.
                repeat(before.size) { i -> loud.add(i >= before.size - onset) }
                before.clear()
                quiet = 0
                onset = 0
            }
        } else {
            cur.add(f)
            levels.add(level)
            loud.add(speech)
            quiet = if (speech) 0 else quiet + 1
            if (quiet >= END_QUIET) {
                end(cur.size - (quiet - KEEP_QUIET))
            } else if (cur.size >= MAX_FRAMES) {
                end(quietestCut())
            }
        }
        return speech
    }

    /** The piece being heard ends now and goes out (if it holds enough speech). */
    fun cut() {
        current?.let { end(it.size) }
    }

    /** Where to cut a full piece: the middle of the quietest [SPAN] frames within its last [SEARCH] frames. */
    private fun quietestCut(): Int {
        val from = levels.size - SEARCH
        var best = from
        var bestSum = Double.MAX_VALUE
        var sum = 0.0
        for (i in from until levels.size) {
            sum += levels[i]
            if (i - from >= SPAN) sum -= levels[i - SPAN]
            if (i - from >= SPAN - 1 && sum < bestSum) {
                bestSum = sum
                best = i - SPAN + 1
            }
        }
        return best + SPAN / 2
    }

    /** The first [count] frames go out as a piece; the rest (after a 30 s cut) start the next one. */
    private fun end(count: Int) {
        val cur = current ?: return
        val n = count.coerceIn(0, cur.size)
        if (loud.subList(0, n).count { it } >= MIN_SPEECH) piece(join(cur.subList(0, n)))
        val rest = cur.size - n
        if (quiet < END_QUIET && rest > 0 && n < cur.size) {
            current = ArrayList(cur.subList(n, cur.size))
            val keptLevels = ArrayList(levels.subList(n, levels.size))
            val keptLoud = ArrayList(loud.subList(n, loud.size))
            levels.clear(); levels.addAll(keptLevels)
            loud.clear(); loud.addAll(keptLoud)
        } else {
            current = null
            levels.clear()
            loud.clear()
            quiet = 0
        }
    }

    private fun join(list: List<ShortArray>): ShortArray {
        val out = ShortArray(list.sumOf { it.size })
        var i = 0
        for (f in list) {
            f.copyInto(out, i)
            i += f.size
        }
        return out
    }

    companion object {
        const val FPS = 50
        const val END_QUIET = 2 * FPS // 2 s of quiet ends a piece
        const val KEEP_QUIET = 10 // 200 ms of it stays on the piece
        const val MAX_FRAMES = 30 * FPS // the server takes 32 s at most
        const val SEARCH = 10 * FPS
        const val SPAN = 13 // 260 ms
        const val MIN_SPEECH = 20 // 400 ms
    }
}
