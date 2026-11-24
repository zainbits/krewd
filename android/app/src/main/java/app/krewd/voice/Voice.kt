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
 * Voice input on the phone (the server side is voice.py): while it listens in a chat, the microphone is cut into
 * pieces of speech at short pauses, each piece is kept as a file until the server has it (so nothing is lost while
 * offline or while the server's model loads), and the server turns them into labelled lines in the chat. Audio is
 * deleted as soon as it is sent; only text stays.
 *
 * [VoiceService] (a microphone foreground service) owns the running [Listener]; the UI reads [state] and calls the
 * functions here. One chat listens at a time.
 */
object Voice {
    private val _state = MutableStateFlow(VoiceUi())
    val state: StateFlow<VoiceUi> = _state.asStateFlow()

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile internal var connection: suspend () -> KrewdApi = { throw IllegalStateException("Krewd is not connected") }
    @Volatile internal var listener: Listener? = null

    /** The ViewModel hands over its connection: [wait] returns once the app is online. */
    fun bind(wait: suspend () -> KrewdApi) {
        connection = wait
    }

    fun hasMic(context: Context) = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun start(context: Context, chatId: String) {
        val prefs = prefs(context)
        _state.value = VoiceUi(
            chatId = chatId, listening = true, starting = true, since = System.currentTimeMillis(),
            myVoice = prefs.getBoolean("my_voice", true), ask = prefs.getBoolean("ask", true),
            enrolled = _state.value.enrolled,
        )
        context.startForegroundService(Intent(context, VoiceService::class.java).putExtra(VoiceService.EXTRA_CHAT, chatId))
    }

    fun stop(context: Context) {
        context.startService(Intent(context, VoiceService::class.java).setAction(VoiceService.ACTION_STOP))
    }

    fun setMyVoice(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("my_voice", on).apply()
        _state.update { it.copy(myVoice = on) }
        listener?.change(myVoice = on)
    }

    fun setAsk(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("ask", on).apply()
        _state.update { it.copy(ask = on) }
        listener?.change(ask = on)
    }

    /** Hold to instruct: while held, everything the mic hears is the owner talking to the agent. */
    fun instruct(down: Boolean) {
        _state.update { it.copy(instructing = down) }
        listener?.instructing = down
    }

    fun choose(lineId: String, choice: String) {
        val l = listener ?: return
        _state.update { it.copy(choice = null) }
        l.call { api, chat, vid -> api.voiceChoice(chat, vid, lineId, choice) }
    }

    fun rename(speaker: String, name: String?) {
        listener?.call { api, chat, vid -> update(api.voiceSpeaker(chat, vid, speaker, name ?: "", null)) }
    }

    fun merge(speaker: String, into: String) {
        listener?.call { api, chat, vid -> update(api.voiceSpeaker(chat, vid, speaker, null, into)) }
    }

    /** What the voiceprint and the server's voice support look like; null on failure. */
    suspend fun refreshStatus(): JSONObject? = runCatching {
        val o = withContext(Dispatchers.IO) { connection().voiceStatus() }
        _state.update { it.copy(enrolled = o.optBoolean("enrolled"), available = o.optBoolean("available"),
            problems = o.optJSONArray("problems")?.strings().orEmpty()) }
        o
    }.getOrNull()

    /** Server events of the listening chat: its speakers, a timed choice, and the end of that choice. */
    fun onEvent(chatId: String?, kind: String, payload: JSONObject) {
        val s = _state.value
        if (chatId == null || chatId != s.chatId) return
        when (kind) {
            "voice_state" -> if (payload.optString("voice_id") == listener?.voiceId) update(payload)
            "voice_choice" -> {
                val options = payload.optJSONArray("options")?.let { a ->
                    (0 until a.length()).map { a.getJSONObject(it).let { o -> VoiceOption(o.optString("id"), o.optString("label")) } }
                }.orEmpty()
                val seconds = payload.optDouble("seconds", 3.0)
                _state.update {
                    it.copy(choice = VoiceChoice(
                        lineId = payload.optString("line_id"), speaker = payload.optString("speaker"),
                        text = payload.optString("text"), options = options, defaultId = payload.optString("default"),
                        until = SystemClock.elapsedRealtime() + (seconds * 1000).toLong(), seconds = seconds.toFloat(),
                    ))
                }
            }
            "voice_choice_done" -> _state.update { if (it.choice?.lineId == payload.optString("line_id")) it.copy(choice = null) else it }
        }
    }

    internal fun update(o: JSONObject) {
        val speakers = o.optJSONArray("speakers")?.let(::speakersFrom)
        _state.update { s ->
            s.copy(
                speakers = speakers ?: s.speakers,
                enrolled = if (o.has("enrolled")) o.optBoolean("enrolled") else s.enrolled,
            )
        }
    }

    internal fun set(block: (VoiceUi) -> VoiceUi) = _state.update(block)

    internal fun prefs(context: Context) = context.getSharedPreferences("krewd_voice", Context.MODE_PRIVATE)

    private fun JSONArray.strings() = (0 until length()).map { optString(it) }

    internal fun speakersFrom(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it) }.map {
        VoiceSpeaker(it.optString("id"), it.optString("label"), it.optString("name").takeIf { n -> n.isNotBlank() && n != "null" })
    }

    // Recording the owner's voiceprint: about 30 s of them reading aloud, sent once, never kept on the phone.

    @SuppressLint("MissingPermission") // the sheet asks for the microphone first
    suspend fun enroll(seconds: Int, progress: (Float, Float) -> Unit): Result<JSONObject> = withContext(Dispatchers.IO) {
        runCatching {
            // Listening has to let go of the mic first: it sends its last words, then ends (seconds, at most a minute).
            withTimeoutOrNull(70_000) { while (listener != null) delay(200) }
                ?: throw IllegalStateException("Listening did not stop; stop it and try again")
            delay(300)
            val rec = recorder()
            val out = java.io.ByteArrayOutputStream(seconds * RATE * 2)
            val frame = ShortArray(FRAME)
            val bytes = java.nio.ByteBuffer.allocate(FRAME * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            try {
                rec.startRecording()
                val total = seconds * RATE
                var got = 0
                while (got < total && kotlinx.coroutines.currentCoroutineContext()[Job]?.isActive != false) {
                    val n = rec.read(frame, 0, FRAME)
                    if (n <= 0) continue
                    bytes.clear()
                    for (i in 0 until n) bytes.putShort(frame[i])
                    out.write(bytes.array(), 0, n * 2)
                    got += n
                    progress(got / total.toFloat(), rms(frame, n).toFloat() / 6000f)
                }
            } finally {
                rec.stop()
                rec.release()
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

data class VoiceSpeaker(val id: String, val label: String, val name: String?)

data class VoiceOption(val id: String, val label: String)

data class VoiceChoice(
    val lineId: String,
    val speaker: String,
    val text: String,
    val options: List<VoiceOption>,
    val defaultId: String,
    /** elapsedRealtime when the server takes the likelier choice by itself. */
    val until: Long,
    val seconds: Float,
)

data class VoiceUi(
    /** The chat listening now (null: none). */
    val chatId: String? = null,
    val listening: Boolean = false,
    /** Asking the server to start, or the transcription model is loading. */
    val starting: Boolean = false,
    /** Sending the last words after Stop. */
    val stopping: Boolean = false,
    val since: Long = 0,
    val myVoice: Boolean = true,
    val ask: Boolean = true,
    /** The owner's voiceprint is on the server; null while unknown. */
    val enrolled: Boolean? = null,
    val available: Boolean? = null,
    val problems: List<String> = emptyList(),
    val speakers: List<VoiceSpeaker> = emptyList(),
    /** The thought being heard: who and the words so far. */
    val liveLabel: String? = null,
    val liveText: String = "",
    /** The last finished thought, shown faintly until the next one. */
    val lastLine: String? = null,
    val speaking: Boolean = false,
    val level: Float = 0f,
    /** Pieces recorded but not yet transcribed by the server. */
    val waiting: Int = 0,
    /** Why pieces wait: "offline", "server" (busy, loading or failing); null when they flow. */
    val holdUp: String? = null,
    val instructing: Boolean = false,
    val choice: VoiceChoice? = null,
    /** Why listening stopped or cannot start; shown once in the chat. */
    val error: String? = null,
)

/**
 * One listening session in one chat: the capture thread (mic -> [Cutter] -> piece files) and the sender (piece files ->
 * server, in order, with retries). Files live in the app's own storage until the server answers for them.
 */
internal class Listener(private val context: Context, val chatId: String, private val ended: (String?) -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dir = File(context.filesDir, "voice/$chatId").apply { mkdirs() }
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val seq = AtomicLong(System.currentTimeMillis()) // unique and rising across restarts of the app
    @Volatile var voiceId: String? = null
    @Volatile var instructing = false
    @Volatile private var running = true
    @Volatile private var lastSpeech = SystemClock.elapsedRealtime()
    private var thread: Thread? = null

    fun start() {
        // Pieces left by a chat whose listening ended with the app (killed before they were sent) are not kept.
        File(context.filesDir, "voice").listFiles()?.filter { it.name != chatId && it.lastModified() < System.currentTimeMillis() - 3_600_000 }
            ?.forEach { it.deleteRecursively() }
        thread = Thread(::capture, "krewd-voice").also { it.start() }
        scope.launch { send() }
    }

    /** Stop the mic, send what is left (up to a minute), end the server's voice session, then call [ended]. */
    fun stop(reason: String?) {
        if (!running) return
        running = false
        Voice.set { it.copy(stopping = true, instructing = false, choice = null) }
        scope.launch {
            withContext(Dispatchers.IO) { thread?.join(2_000) }
            val drained = withTimeoutOrNull(60_000) {
                while (pending().isNotEmpty()) delay(200)
                true
            }
            if (drained == null) {
                Log.w(Voice.TAG, "voice: ${pending().size} pieces not sent within a minute after Stop; deleted")
            }
            dir.listFiles()?.forEach { it.delete() }
            voiceId?.let { vid -> runCatching { Voice.connection().voiceStop(chatId, vid) } }
            ended(reason ?: if (drained == null) "Stopped; the last words could not be sent" else null)
            scope.cancel()
        }
    }

    fun change(myVoice: Boolean? = null, ask: Boolean? = null) = call { api, chat, vid ->
        Voice.update(api.voiceChange(chat, vid, myVoice, ask))
    }

    fun call(block: suspend (KrewdApi, String, String) -> Unit) {
        scope.launch {
            val vid = voiceId ?: return@launch
            runCatching { block(Voice.connection(), chatId, vid) }.onFailure { Log.w(Voice.TAG, "voice call failed: $it") }
        }
    }

    // capture

    private fun capture() {
        val rec = try {
            Voice.recorder()
        } catch (e: Exception) {
            Log.w(Voice.TAG, "voice: no microphone: $e")
            scope.launch { stop("The microphone could not be opened") }
            return
        }
        val cutter = Cutter(
            piece = { pcm, instruct -> save(pcm, instruct) },
            pause = { savePause() },
        )
        val frame = ShortArray(Voice.FRAME)
        var lastLevel = 0L
        try {
            rec.startRecording()
            while (running) {
                val n = rec.read(frame, 0, Voice.FRAME)
                if (n < 0) {
                    Log.w(Voice.TAG, "voice: microphone read failed ($n)")
                    scope.launch { stop("The microphone stopped") }
                    break
                }
                if (n == 0) continue
                val speech = cutter.feed(frame, n, instructing)
                val now = SystemClock.elapsedRealtime()
                if (speech) lastSpeech = now
                if (now - lastLevel > 100) {
                    lastLevel = now
                    val level = min(1f, (Voice.rms(frame, n) / 5000.0).toFloat())
                    Voice.set { it.copy(level = level, speaking = cutter.inSpeech || instructing) }
                }
                if (now - lastSpeech > AUTO_STOP_MS) {
                    scope.launch { stop("Stopped listening: nobody spoke for ${AUTO_STOP_MS / 60_000} minutes") }
                    break
                }
            }
            cutter.flush()
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }

    private fun save(pcm: ShortArray, instruct: Boolean) {
        // Phone-like gain: the loudest sample at about -3 dB, at most 20x (the same as the hearing test's audio).
        var peak = 1
        for (s in pcm) peak = max(peak, kotlin.math.abs(s.toInt()))
        val gain = min(20.0, 0.7 * 32767 / peak)
        val bytes = java.nio.ByteBuffer.allocate(pcm.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (s in pcm) bytes.putShort((s * gain).toInt().coerceIn(-32768, 32767).toShort())
        val n = seq.incrementAndGet()
        val f = File(dir, "%016d.%s.pcm".format(n, if (instruct) "i" else "n"))
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeBytes(bytes.array())
        tmp.renameTo(f)
        trim()
        wake.trySend(Unit)
        Voice.set { it.copy(waiting = pending().size) }
    }

    private fun savePause() {
        File(dir, "%016d.pause".format(seq.incrementAndGet())).createNewFile()
        wake.trySend(Unit)
    }

    /** Offline for long: keep the newest [MAX_WAITING] pieces (about 20 minutes of talk). */
    private fun trim() {
        val all = pending()
        if (all.size > MAX_WAITING) all.take(all.size - MAX_WAITING).forEach { it.delete() }
    }

    private fun pending(): List<File> = dir.listFiles { f -> !f.name.endsWith(".tmp") }?.sortedBy { it.name }.orEmpty()

    // sending

    private suspend fun send() {
        var backoff = 1_000L
        while (true) {
            if (voiceId == null && running) {
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
                if (!running) return
                withTimeoutOrNull(1_000) { wake.receive() }
                continue
            }
            val ok = try {
                val api = withTimeoutOrNull(5_000) { Voice.connection() }
                if (api == null) {
                    Voice.set { it.copy(holdUp = "offline", waiting = pending().size) }
                    false
                } else {
                    val vid = voiceId ?: begin(api)
                    deliver(api, vid, next)
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

    private fun begin(api: KrewdApi): String {
        val s = Voice.state.value
        val o = api.voiceStart(chatId, s.myVoice, s.ask, null)
        Voice.update(o)
        Voice.set { it.copy(starting = false, error = null) }
        return o.getString("voice_id").also { voiceId = it }
    }

    private fun deliver(api: KrewdApi, vid: String, f: File) {
        val n = f.name.substringBefore('.').toLong()
        if (f.name.endsWith(".pause")) {
            api.voicePause(chatId, vid)
            f.delete()
            Voice.set { s -> s.copy(lastLine = s.liveLabel?.let { "$it: ${s.liveText}" }?.takeIf { s.liveText.isNotBlank() } ?: s.lastLine,
                liveLabel = null, liveText = "") }
            return
        }
        val r = api.voiceAudio(chatId, vid, n, f.readBytes(), f.name.contains(".i."))
        f.delete()
        r.optJSONArray("speakers")?.let { a -> Voice.set { it.copy(speakers = Voice.speakersFrom(a)) } }
        val text = r.optString("text")
        val line = r.optString("line")
        val label = r.optString("label")
        Voice.set { s ->
            when {
                text.isBlank() -> s.copy(waiting = pending().size, holdUp = null)
                r.optBoolean("done") -> s.copy(lastLine = "$label: $line", liveLabel = null, liveText = "", waiting = pending().size, holdUp = null)
                else -> s.copy(liveLabel = label, liveText = line, waiting = pending().size, holdUp = null)
            }
        }
    }

    companion object {
        const val AUTO_STOP_MS = 10 * 60_000L
        const val MAX_WAITING = 400
    }
}

/**
 * Cuts the microphone into pieces of speech by loudness against a moving quiet floor: a piece starts after 60 ms of
 * speech (with 300 ms before it), ends after 600 ms of quiet (keeping 200 ms of it) or at 15 s, and is dropped when it
 * holds under 400 ms of speech (a cough, a click). 1.5 s of quiet after a piece sends a pause, which finishes the
 * thought on the server. While instructing, everything heard is one instruction, cut at 25 s.
 */
internal class Cutter(private val piece: (ShortArray, Boolean) -> Unit, private val pause: () -> Unit) {
    private var floor = 300.0
    private val before = ArrayDeque<ShortArray>()
    private var current: ArrayList<ShortArray>? = null
    private var instruction: ArrayList<ShortArray>? = null
    private var onset = 0
    private var speechFrames = 0
    private var quiet = 0
    private var sincePiece = -1
    val inSpeech: Boolean get() = current != null

    fun feed(frame: ShortArray, n: Int, instruct: Boolean): Boolean {
        val f = frame.copyOf(n)
        val level = Voice.rms(f, n)
        val speech = level > max(floor * 3.0, 350.0)
        floor = when {
            !speech && level < floor -> floor * 0.9 + level * 0.1
            !speech -> floor * 0.998 + level * 0.002
            else -> floor * 0.9995 + level * 0.0005
        }.coerceAtLeast(30.0)
        if (instruct) {
            current?.let { end(it) }
            val list = instruction ?: ArrayList<ShortArray>().also { instruction = it }
            list.add(f)
            if (list.size >= 25 * FPS) {
                piece(join(list), true)
                instruction = ArrayList()
            }
            return true
        }
        instruction?.let { list ->
            instruction = null
            if (list.isNotEmpty()) piece(join(list), true)
            pause()
            sincePiece = -1
        }
        val cur = current
        if (cur == null) {
            before.addLast(f)
            if (before.size > 15) before.removeFirst()
            onset = if (speech) onset + 1 else max(0, onset - 1)
            if (onset >= 3) {
                current = ArrayList(before)
                before.clear()
                speechFrames = onset
                quiet = 0
                onset = 0
            } else if (sincePiece >= 0 && ++sincePiece >= 75) {
                pause()
                sincePiece = -1
            }
        } else {
            cur.add(f)
            if (speech) {
                speechFrames++
                quiet = 0
            } else {
                quiet++
            }
            if (quiet >= 30) {
                end(ArrayList(cur.subList(0, cur.size - (quiet - 10))))
            } else if (cur.size >= 15 * FPS) {
                end(cur)
                current = ArrayList() // still talking: the next piece starts at once
            }
        }
        return speech
    }

    private fun end(list: List<ShortArray>) {
        if (speechFrames >= 20) {
            piece(join(list), false)
            sincePiece = 0
        }
        current = null
        speechFrames = 0
        quiet = 0
    }

    /** The mic stops: what is open goes out, and the thought is finished. */
    fun flush() {
        instruction?.let { if (it.isNotEmpty()) piece(join(it), true) }
        instruction = null
        current?.let { end(it) }
        pause()
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
    }
}
