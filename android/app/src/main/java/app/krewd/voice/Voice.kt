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
import kotlinx.coroutines.CompletableDeferred
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
 * Voice input on the phone (the server side is voice.py). In voice mode the microphone is on all the time; its audio
 * goes to the server about every 250 ms (kept in memory while offline, the newest 2 minutes), and the words come back
 * while they are said, one segment per speaker turn (Me or Others). They are written straight into the chat's composer
 * as its last lines ([LiveTail]), replaced as they change, until each turn is final and becomes a `Me: ...` /
 * `Others: ...` line ([joinLine]). The owner edits them and presses Send; nothing is sent by itself. Send first makes
 * everything final (about half a second), then sends; voice mode goes on listening.
 *
 * [VoiceService] (a microphone foreground service) owns the running [Listener]; the UI reads [state] and calls the
 * functions here. One chat is in voice mode at a time. Mute releases the microphone (Android's mic indicator goes
 * off); only the app's screen unmutes.
 */
object Voice {
    private val _state = MutableStateFlow(VoiceUi())
    val state: StateFlow<VoiceUi> = _state.asStateFlow()

    @Volatile internal var connection: suspend () -> KrewdApi = { throw IllegalStateException("Krewd is not connected") }
    /** Where heard words go: the ViewModel applies the change to the chat's composer draft (chat id, change). */
    @Volatile internal var edit: (String, (String) -> String) -> Unit = { _, _ -> }

    @Volatile internal var listener: Listener? = null

    /** The ViewModel hands over its connection ([wait] returns once the app is online) and the composer drafts. */
    fun bind(wait: suspend () -> KrewdApi, onEdit: (chatId: String, change: (String) -> String) -> Unit) {
        connection = wait
        edit = onEdit
    }

    fun hasMic(context: Context) = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Voice mode on in [chatId] (the composer's mic): the microphone listens until [leave]. Needs the app in front. */
    fun start(context: Context, chatId: String) {
        _state.value = VoiceUi(
            chatId = chatId, listening = true, starting = true, since = System.currentTimeMillis(),
            myVoice = prefs(context).getBoolean("my_voice", true), enrolled = _state.value.enrolled,
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

    fun setMyVoice(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("my_voice", on).apply()
        _state.update { it.copy(myVoice = on) }
    }

    /**
     * Before Send: everything heard in [chatId] so far is made final and in the composer first, at most [timeoutMs].
     * False on time out.
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
            problems = o.optJSONArray("problems")?.strings().orEmpty()) }
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
    /** Why the words wait: "offline", "server" (busy, loading or failing); null when they flow. */
    val holdUp: String? = null,
    /** Why voice mode ended or the microphone stopped; shown in the chat. */
    val error: String? = null,
)

/**
 * The words heard at the end of the composer: final turns become `Me: ...` / `Others: ...` lines ([Voice.joinLine]);
 * the turns still being heard follow as the last lines and are replaced whenever they change. While the text still ends
 * with what was last written, that part is swapped; if the owner changed it (a fix) or sent it, their text stays and
 * those turns are theirs: their later words or final text are not added again. Used on the main thread only.
 */
internal class LiveTail {
    data class Turn(val id: Long, val label: String?, val text: String)

    private var tail = ""
    private var tailIds = emptySet<Long>()
    private val theirs = HashSet<Long>()

    fun apply(current: String, finals: List<Turn>, hearing: List<Turn>): String {
        var base = current
        if (tail.isNotEmpty()) {
            if (current.endsWith(tail)) base = current.dropLast(tail.length) else theirs += tailIds
        }
        val add = finals.filter { it.id !in theirs && it.text.isNotEmpty() }
        val now = hearing.filter { it.id !in theirs && it.text.isNotEmpty() }
        finals.forEach { theirs.remove(it.id) } // done: an id is never seen again
        var text = base
        for (t in add) text = Voice.joinLine(text, t.label ?: "Others", t.text)
        if (now.isEmpty()) {
            tail = ""
            tailIds = emptySet()
            return text
        }
        val start = text.trimEnd()
        var rendered = start
        for (t in now) rendered = Voice.joinLine(rendered, t.label ?: "…", t.text)
        tail = rendered.substring(start.length)
        tailIds = now.map { it.id }.toSet()
        return rendered
    }
}

/**
 * Voice mode in one chat: the capture thread while not muted (mic -> gain -> audio in memory) and the sender (audio ->
 * server about every 250 ms, one request at a time, a failed one sent again with its number -> words into the composer).
 */
internal class Listener(private val context: Context, val chatId: String, private val ended: (String?) -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
    private var thread: Thread? = null
    private val audio = java.io.ByteArrayOutputStream() // heard, not sent yet (guarded by itself)
    private var peak = 3000.0
    private var after = 0L // the first segment not final in the composer yet
    private val tail = LiveTail()
    private val flushes = java.util.concurrent.ConcurrentLinkedQueue<CompletableDeferred<Unit>>()
    private var sender: Job? = null

    fun start() {
        File(context.filesDir, "voice").deleteRecursively() // pieces of 0.34 and older, never sent
        unmute()
        sender = scope.launch { send() }
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
        capturing = false // the capture thread lets go of the microphone
        flushes.add(CompletableDeferred()) // the words being heard become final now, not when the server notices the quiet
        wake.trySend(Unit)
        Voice.set { it.copy(muted = true, speaking = false, level = 0f) }
    }

    /** Leave voice mode: stop the mic, send what is left, end the server's voice session, then [ended]. */
    fun stop(reason: String?) {
        if (!active) return
        active = false
        capturing = false
        Voice.set { it.copy(stopping = true) }
        scope.launch {
            withContext(Dispatchers.IO) { thread?.join(2_000) }
            wake.trySend(Unit)
            val drained = withTimeoutOrNull(30_000) { sender?.join(); true }
            if (drained == null) Log.w(Voice.TAG, "voice: the last words were not sent within 30 s of leaving voice mode")
            voiceId?.let { vid -> runCatching { Voice.connection().voiceStop(chatId, vid) } }
            ended(reason ?: if (drained == null) "The last words could not be sent" else null)
            scope.cancel()
        }
    }

    /** Everything heard so far made final and in the composer. */
    suspend fun settle() {
        val done = CompletableDeferred<Unit>()
        flushes.add(done)
        wake.trySend(Unit)
        done.await()
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
                tap?.let { t ->
                    t(frame, n)
                    continue
                }
                feed(frame, n)
                val now = SystemClock.elapsedRealtime()
                if (now - lastLevel > 100) {
                    lastLevel = now
                    val rms = Voice.rms(frame, n)
                    Voice.set { it.copy(level = min(1f, (rms / 5000.0).toFloat()), speaking = rms > 600) }
                }
            }
            wake.trySend(Unit) // the last audio goes with the next request
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }

    /** A frame of the mic into the audio to send, with a slow automatic gain (the loudest of the last seconds at
     *  about -3 dB, at most 20x), so a quiet phone mic still reaches the models. */
    private fun feed(frame: ShortArray, n: Int) {
        var loudest = 0
        for (i in 0 until n) loudest = max(loudest, kotlin.math.abs(frame[i].toInt()))
        peak = max(max(loudest.toDouble(), 1500.0), peak * 0.995)
        val gain = min(20.0, 0.7 * 32767 / peak)
        val bytes = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (frame[i] * gain).toInt().coerceIn(-32768, 32767)
            bytes[2 * i] = (v and 0xff).toByte()
            bytes[2 * i + 1] = (v shr 8).toByte()
        }
        synchronized(audio) {
            audio.write(bytes)
            if (audio.size() > KEEP_BYTES) { // offline for long: keep the newest 2 minutes
                val all = audio.toByteArray()
                audio.reset()
                audio.write(all, all.size - KEEP_BYTES, KEEP_BYTES)
            }
        }
    }

    private fun take(): ByteArray = synchronized(audio) {
        val all = audio.toByteArray()
        val n = min(all.size, MAX_SEND_BYTES)
        audio.reset()
        if (n < all.size) audio.write(all, n, all.size - n)
        all.copyOf(n)
    }

    private fun waiting(): Boolean = synchronized(audio) { audio.size() > 0 }

    // sending

    /** Audio to the server about every 250 ms and the words back into the composer. Ends after the last audio once
     *  voice mode is left. */
    private suspend fun send() {
        var backoff = 1_000L
        var chunk: ByteArray? = null
        var chunkSeq = 0L
        var chunkFlush = 0
        var chunkEnd = false
        while (true) {
            if (voiceId == null) {
                if (!active && !waiting() && chunk == null) break
                val started = try {
                    val api = withTimeoutOrNull(5_000) { Voice.connection() }
                    if (api == null) Voice.set { it.copy(holdUp = "offline") } else begin(api)
                    api != null
                } catch (e: ApiException) {
                    if (e.code == 503 || e.code == 404) { // voice input is off on the server, or the chat is gone
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
                if (active && flushes.isEmpty()) withTimeoutOrNull(SEND_MS) { wake.receive() }
                if (active && flushes.isEmpty() && capturing && !waiting()) continue
                val flushing = flushes.size
                val ending = !active
                val next = take()
                if (next.isEmpty() && flushing == 0 && !ending) {
                    withTimeoutOrNull(1_000) { wake.receive() } // muted: nothing to send
                    continue
                }
                chunk = next
                chunkSeq = seq.incrementAndGet()
                chunkFlush = flushing
                chunkEnd = ending && !waiting()
            }
            val sent = try {
                val api = withTimeoutOrNull(5_000) { Voice.connection() }
                if (api == null) {
                    Voice.set { it.copy(holdUp = "offline") }
                    false
                } else {
                    val vid = voiceId ?: begin(api)
                    answer(api.voiceLive(chatId, vid, chunkSeq, after, chunkFlush > 0, chunkEnd, chunk!!))
                    true
                }
            } catch (e: ApiException) {
                when {
                    e.code == 409 -> { voiceId = null; false } // the server lost the voice session: start again
                    e.code in 400..499 && e.code != 429 -> {
                        Log.w(Voice.TAG, "voice: audio refused (${e.code}): ${e.message}")
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
    }

    private fun answer(r: JSONObject) {
        val segments = r.optJSONArray("segments") ?: JSONArray()
        val finals = mutableListOf<LiveTail.Turn>()
        val hearing = mutableListOf<LiveTail.Turn>()
        val myVoice = Voice.state.value.myVoice
        for (i in 0 until segments.length()) {
            val seg = segments.optJSONObject(i) ?: continue
            val id = seg.optLong("id", -1)
            if (id < after) continue
            val mine = seg.optString("speaker") == "me"
            val label = seg.optString("label").takeIf { it.isNotBlank() && it != "null" }
            val turn = LiveTail.Turn(id, label, if (mine && !myVoice) "" else seg.optString("text").trim())
            if (seg.optBoolean("final")) {
                finals.add(turn)
                after = id + 1
            } else {
                hearing.add(turn)
            }
        }
        Voice.edit(chatId) { tail.apply(it, finals, hearing) }
        val status = r.optString("status")
        Voice.set {
            it.copy(holdUp = null, starting = status == "queued" || status == "starting",
                error = if (it.holdUp == "server") null else it.error)
        }
    }

    private fun begin(api: KrewdApi): String {
        after = 0
        val o = api.voiceStart(chatId)
        Voice.set { it.copy(starting = false, enrolled = o.optBoolean("enrolled"), holdUp = null) }
        return o.getString("voice_id").also { voiceId = it }
    }

    companion object {
        const val SEND_MS = 250L
        const val MAX_SEND_BYTES = 8 * Voice.RATE * 2 // the server takes 10 s per request
        const val KEEP_BYTES = 120 * Voice.RATE * 2
    }
}
