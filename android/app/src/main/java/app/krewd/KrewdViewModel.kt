package app.krewd

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.graphics.asImageBitmap
import app.krewd.constants.Timing
import app.krewd.data.AgentLogin
import app.krewd.data.ApiException
import app.krewd.data.Approval
import app.krewd.data.Attachment
import app.krewd.data.BrowserFrame
import app.krewd.data.BrowserTab
import app.krewd.data.BrowserTabs
import app.krewd.data.ChatItem
import app.krewd.data.ConfigOption
import app.krewd.data.ConnectionSettings
import app.krewd.data.KrewdApi
import app.krewd.data.Credentials
import app.krewd.data.Device
import app.krewd.data.OwnerKey
import app.krewd.data.Passkeys
import app.krewd.data.Keys
import app.krewd.data.Presence
import app.krewd.data.PresenceCancelled
import app.krewd.data.SecretAsk
import app.krewd.data.Tls
import app.krewd.data.U2f
import app.krewd.data.objects
import app.krewd.data.optStringOrNull
import app.krewd.data.ChatRef
import app.krewd.data.Discovery
import app.krewd.data.Proposal
import app.krewd.data.EngineDefaults
import app.krewd.data.EngineInfo
import app.krewd.data.Event
import app.krewd.data.FrameStream
import app.krewd.data.PendingFile
import app.krewd.data.ImageRef
import app.krewd.data.ProfilesState
import app.krewd.data.Prefs
import app.krewd.data.Routine
import app.krewd.data.RoutineRun
import app.krewd.data.SchedulePreview
import app.krewd.data.SessionInfo
import app.krewd.data.Timeline
import app.krewd.data.UserItem
import app.krewd.data.Verified
import app.krewd.data.browserInput
import app.krewd.data.browserTabs
import app.krewd.data.closeBrowserTab
import app.krewd.data.newTab
import app.krewd.data.setTabView
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

sealed interface Connection {
    data object NotSetUp : Connection
    data object Connecting : Connection
    /** [route]: which address answered ("Home", "Tailscale", a host); [secure]: checked HTTPS. */
    data class Online(val route: String, val version: String, val secure: Boolean = false) : Connection
    data class Offline(val reason: String) : Connection
}

/** A message on its way to the server: shown in the chat as "Sending" until stored, or "Not sent" after a failure. */
data class OutgoingMessage(
    val clientId: String,
    val sessionId: String,
    val text: String,
    val uploads: List<String>,
    val files: List<PendingFile>,
    val tabs: List<BrowserTab>,
    val delivery: String?,
    val failed: Boolean = false,
    val chats: List<ChatRef> = emptyList(),
)

object NavTab {
    const val TASKS = 0
    const val ROUTINES = 1
    const val BROWSER = 2
    const val SETTINGS = 3
}

data class UiState(
    val settings: ConnectionSettings,
    /** A connect link (krewd://connect) waiting for the owner's yes: it would replace the server settings. */
    val pendingConnect: ConnectionSettings? = null,
    val connection: Connection = Connection.Connecting,
    val engines: List<EngineInfo> = emptyList(),
    val sessions: List<SessionInfo> = emptyList(),
    val approvals: List<Approval> = emptyList(),
    /** Sign-in requests from agents waiting for the owner (the values never pass through the agent). */
    val secrets: List<SecretAsk> = emptyList(),
    /** Settings > Security: the owner's phones; null until loaded. */
    val devices: List<Device>? = null,
    /** Settings > Security: the owner's keys (security key, phone passkeys); null until loaded. */
    val ownerKeys: List<OwnerKey>? = null,
    /** The server's passkey domain; null: passkeys are off on the server. */
    val passkeyRpId: String? = null,
    /** While pairing or removing a phone: what the owner should do now ("Hold your security key..."). */
    val securityStep: String? = null,
    val navTab: Int = NavTab.TASKS,
    val openSessionId: String? = null,
    /** The chat opened last: "Add to chat" goes there first. */
    val lastSessionId: String? = null,
    val timeline: List<ChatItem> = emptyList(),
    val timelineLoading: Boolean = false,
    val message: String? = null,
    val browser: BrowserTabs? = null,
    /** The tab the Browser screen shows. */
    val browserTabId: String? = null,
    val streamError: String? = null,
    /** Files picked for the next message, per draft: "new" for the new-task card, else a session id. */
    val drafts: Map<String, List<PendingFile>> = emptyMap(),
    /** Browser tabs added to the next message ("Add to chat"), per session. */
    val draftTabs: Map<String, List<BrowserTab>> = emptyMap(),
    /** Chats attached with @ to the unsent message, per chat (or new-task sheet): kept with its text on the phone. */
    val draftChats: Map<String, List<ChatRef>> = emptyMap(),
    /** Side sessions, sub-agents and messages to other chats that agents drafted, waiting for the owner. */
    val proposals: List<Proposal> = emptyList(),
    /** Archived chats, loaded when @ needs them (null: not loaded). */
    val archived: List<SessionInfo>? = null,
    val outbox: List<OutgoingMessage> = emptyList(),
    /** Picture-in-picture picked by hand from the Browser screen, per session. */
    val pipTabs: Map<String, String> = emptyMap(),
    /** "session:tab" pairs whose picture-in-picture the owner closed. */
    val pipHidden: Set<String> = emptySet(),
    val highQuality: Boolean = false,
    val restarting: Boolean = false,
    val confirmStop: String? = null,
    /** The new-task card's text, kept while you look at other screens. */
    val newTaskText: String = "",
    /** A task deleted in the last 3 seconds: hidden, and only deleted on the server once Undo has passed. */
    val pendingDelete: SessionInfo? = null,
    val routines: List<Routine> = emptyList(),
    /** The routine whose page is open, with its run history once loaded. */
    val openRoutineId: String? = null,
    val routineDetail: Routine? = null,
    /** A chat row to scroll to once it is loaded (a routine run's message), as its timeline key. */
    val chatFocus: String? = null,
    /** What each running task is doing right now, by session id (the Krewd tab and the chat's activity line). */
    val live: Map<String, LiveInfo> = emptyMap(),
    /** The owner's saved prompts for the new-task sheet, newest first. */
    val savedPrompts: List<String> = emptyList(),
    /** Gateway profiles and the presets the app offers (Settings). */
    val profiles: ProfilesState = ProfilesState(),
    /** Each agent's sign-in account, and the profile it runs on (Settings > Agents). */
    val logins: List<AgentLogin> = emptyList(),
) {
    val openRoutine: Routine? get() = routineDetail?.takeIf { it.id == openRoutineId } ?: routines.firstOrNull { it.id == openRoutineId }
    fun routine(id: String?): Routine? = routines.firstOrNull { it.id == id }
    fun draft(key: String): List<PendingFile> = drafts[key].orEmpty()
    fun chatsOf(key: String): List<ChatRef> = draftChats[key].orEmpty()
    fun session(id: String?): SessionInfo? = sessions.firstOrNull { it.id == id }
    val browserTab: BrowserTab? get() = browser?.tabs?.firstOrNull { it.id == browserTabId }
    val openSession: SessionInfo? get() = sessions.firstOrNull { it.id == openSessionId }
    fun engine(id: String?): EngineInfo? = engines.firstOrNull { it.id == id }

    /** The tab to show as picture-in-picture on a chat: picked by hand, or the one its agent is using right now. */
    fun pipTab(session: SessionInfo?): String? {
        session ?: return null
        val tab = pipTabs[session.id] ?: session.browserTab?.id?.takeIf { session.isBusy } ?: return null
        return tab.takeUnless { "${session.id}:$it" in pipHidden }
    }
}

const val NEW_DRAFT = "new"

/** At most this many chats attached with @ to one message. */
const val MAX_DRAFT_CHATS = 5

/** The draft key of the new-task sheet when it starts a side session of [parentId]. */
fun sideDraft(parentId: String) = "side-$parentId"

/** The draft key of the sheet that sends proposal [id]. */
fun proposalDraft(id: String) = "proposal-$id"
const val MAX_SAVED_PROMPTS = 12
/** Width of the tab stills on working cards, in pixels. */
const val SNAPSHOT_WIDTH = 360
const val UNDO_MS = 3_000L

/**
 * The app's state and its one connection to the server. It lives as long as the process (see
 * [KrewdApplication]), not the screen, so the long poll and its notifications keep going while the app
 * is in the background and the live-activity service keeps the process alive.
 *
 * Every change on the server is a durable event, so reconnecting just continues from the last seq.
 */
class KrewdViewModel(private val app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = Prefs(app)
    private val notifier = Notifier(app)
    private val _state = MutableStateFlow(
        UiState(
            settings = prefs.load(), highQuality = prefs.highQuality(), savedPrompts = prefs.savedPrompts(),
            newTaskText = prefs.textDraft(NEW_DRAFT),
            draftChats = prefs.chatDraftKeys().associateWith { prefs.chatDraft(it) }.filterValues { it.isNotEmpty() },
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    @Volatile private var api: KrewdApi? = null
    @Volatile private var creds: Credentials = Credentials.of(prefs.load())

    /** The activity in front, for the fingerprint prompt and the security key's NFC tap. */
    @Volatile var activity: java.lang.ref.WeakReference<android.app.Activity>? = null

    init {
        // The app's own picks are kept on the server too, so a new phone starts where the old one was.
        prefs.onChange = { key, value -> api?.let { client -> scope.launch(Dispatchers.IO) { runCatching { client.setPref(key, value) } } } }
    }
    private var loop: Job? = null
    private var timeline: Timeline? = null
    private val pendingLive = ArrayList<Event>()
    private val live = LinkedHashMap<String, LiveTask>()
    private val lastError = HashMap<String, String>()

    @Volatile var inForeground = true
        set(value) {
            field = value
            if (value) notifier.appVisible()
        }

    // The agents' browser. Frames live in their own flow so a new picture does not re-render everything.
    private val _frame = MutableStateFlow<BrowserFrame?>(null)
    val frame: StateFlow<BrowserFrame?> = _frame.asStateFlow()
    private var streamJob: Job? = null
    @Volatile private var stream: FrameStream? = null
    @Volatile private var watched: String? = null
    private val tabOrder = ArrayList<String>()

    fun connect() {
        loop?.cancel()
        val settings = _state.value.settings
        if (settings.token != creds.token || settings.deviceId != creds.deviceId) creds = Credentials.of(settings)
        if (!settings.configured) {
            _state.update { it.copy(connection = Connection.NotSetUp) }
            return
        }
        loop = scope.launch(Dispatchers.IO) { runLoop(settings) }
    }

    fun saveSettings(settings: ConnectionSettings) {
        prefs.save(settings)
        _state.update { it.copy(settings = prefs.load()) }
        connect()
    }

    /** A krewd://connect link (from `./master.sh token`): asks before it replaces the server settings. */
    fun openConnectLink(uri: android.net.Uri) {
        if (uri.scheme != "krewd" || uri.host != "connect") return
        val addresses = uri.getQueryParameters("a").flatMap(ConnectionSettings::parseAddresses).distinct()
        // No token once the owner's security key is registered: this phone then pairs with the key.
        val token = uri.getQueryParameter("t").orEmpty()
        if (addresses.isEmpty()) {
            _state.update { it.copy(message = "That connect link is incomplete") }
            return
        }
        val pin = uri.getQueryParameter("p").orEmpty()
        val keep = prefs.load().takeIf { it.paired && token.isBlank() }  // same server, already paired: keep this phone's id
        _state.update { it.copy(pendingConnect = ConnectionSettings(addresses, token, pin, keep?.deviceId.orEmpty()), navTab = NavTab.SETTINGS) }
    }

    /**
     * Pasted text that holds a krewd://connect link, for the owner on the same phone as the code: a phone camera or
     * QR app often only copies the link, and a terminal copy can wrap it over several lines. Returns false when none.
     */
    fun pasteConnectCode(text: String?): Boolean {
        val link = text?.let(::findConnectLink)
        if (link == null) {
            _state.update { it.copy(message = "No Krewd connect code to paste. On the server, run ./master.sh token and copy its krewd://connect link") }
            return false
        }
        openConnectLink(android.net.Uri.parse(link))
        return true
    }

    fun answerConnectLink(accept: Boolean) {
        val pending = _state.value.pendingConnect ?: return
        _state.update { it.copy(pendingConnect = null) }
        if (accept) saveSettings(pending)
    }

    /**
     * The first address that answers; when none does, the local network is searched for the server (a router
     * change may have moved it) and a found address is saved first in the list.
     */
    private fun reach(settings: ConnectionSettings): KrewdApi = try {
        KrewdApi.resolve(settings, creds)
    } catch (e: Exception) {
        if (settings.pin.isBlank()) throw e
        val found = Discovery.find(app, settings.pin).filterNot { it in settings.addresses }
        if (found.isEmpty()) throw e
        val client = runCatching { KrewdApi.resolve(settings, creds, found) }.getOrNull()?.takeIf { it.verified == Verified.Pin } ?: throw e
        prefs.save(settings.copy(addresses = listOf(client.baseUrl) + settings.addresses))
        _state.update { it.copy(settings = prefs.load()) }
        client
    }

    /**
     * Over an address an authority vouches for (Tailscale, a domain), learn the server's own certificate pin,
     * so the home network address can be checked too. Never learned over a pinned or plain connection.
     */
    private fun learnPin(client: KrewdApi) {
        if (client.verified != Verified.Authority) return
        val pin = runCatching { client.tlsPin() }.getOrNull() ?: return
        val current = prefs.load()
        if (pin != current.pin) {
            prefs.save(current.copy(pin = pin))
            _state.update { it.copy(settings = prefs.load()) }
        }
    }

    private var sawDown = false

    private suspend fun runLoop(settings: ConnectionSettings) {
        var attempt = 0
        _state.update { it.copy(connection = Connection.Connecting) }
        while (true) {
            try {
                // Settings learned while connecting (a pin, a found address) count; a new token restarts the loop.
                val client = reach(prefs.load().takeIf { it.token == settings.token } ?: settings)
                val version = client.health().optString("version")
                learnPin(client)
                val server = client.state()
                api = client
                val sessions = client.sessions().filterNot { it.id == _state.value.pendingDelete?.id }
                val approvals = client.approvals()
                val secrets = runCatching { client.secrets() }.getOrDefault(emptyList())
                // Older servers have no proposals.
                val proposals = runCatching { client.proposals() }.getOrDefault(emptyList())
                syncPrefs(client)
                val routines = runCatching { client.routines() }.getOrDefault(_state.value.routines)
                val wasRestarting = _state.value.restarting && sawDown
                _state.update {
                    it.copy(
                        connection = Connection.Online(client.label, version, client.secure),
                        engines = server.engines,
                        sessions = sessions,
                        approvals = approvals,
                        secrets = secrets,
                        proposals = proposals,
                        archived = null,
                        routines = routines,
                        restarting = if (wasRestarting) false else it.restarting,
                        message = if (wasRestarting) "Krewd is back" else it.message,
                    )
                }
                if (wasRestarting) sawDown = false
                syncLive(sessions)
                _state.value.openSessionId?.let { loadTimeline(client, it) }
                _state.value.openRoutineId?.let { loadRoutine(client, it) }
                retryOutbox()
                attempt = 0
                var after = server.lastSeq
                while (true) {
                    val (events, last) = client.events(after)
                    // The blocking poll cannot be interrupted, so a replaced loop stops here.
                    currentCoroutineContext().ensureActive()
                    events.forEach(::handle)
                    after = last
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                api = null
                val reason = when {
                    e is ApiException && e.code == 401 -> "The server did not accept this phone's token. $RECONNECT_HINT"
                    e is javax.net.ssl.SSLException || e.cause is java.security.cert.CertificateException ->
                        "The server's certificate is not the one this app trusts. $RECONNECT_HINT"
                    else -> e.message ?: e.javaClass.simpleName
                }
                if (_state.value.restarting) sawDown = true
                _state.update { it.copy(connection = Connection.Offline(reason)) }
                if (e is ApiException && e.code == 401) {
                    if (!creds.paired) return
                    creds.session = null
                    if (e.message.orEmpty().contains("no longer allowed")) {
                        _state.update { it.copy(connection = Connection.Offline("This phone was removed from Krewd. Set it up again in Settings > Security")) }
                        return
                    }
                }
                // While the server restarts, try every second so the app is back as soon as it is.
                val wait = if (_state.value.restarting) 1_000L else Timing.RETRY_DELAYS_MS[minOf(attempt, Timing.RETRY_DELAYS_MS.lastIndex)]
                delay(wait)
                attempt++
            }
        }
    }

    private fun handle(event: Event) {
        val sid = event.sessionId
        when (event.kind) {
            "session" -> {
                val session = SessionInfo.from(event.payload)
                _state.update { s -> s.copy(sessions = upsert(s.sessions, session)) }
                if (session.isBusy) liveTask(session).also { it.title = session.title } else live.remove(sid)
                pushLive()
            }
            "turn_start" -> {
                if (_state.value.navTab == NavTab.BROWSER) refreshTabs()
                live[sid]?.let { it.activity = "Starting"; it.kind = "think"; it.since = System.currentTimeMillis(); it.toolIds.clear() }
                lastError.remove(sid)
                pushLive()
            }
            "tool" -> {
                val status = event.payload.optString("status")
                live[sid]?.toolIds?.add(event.payload.optString("tool_call_id", event.seq.toString()))
                if (status != "completed" && status != "failed") {
                    live[sid]?.let {
                        it.activity = event.payload.optString("title", "Working")
                        it.kind = faceKind(event.payload.optString("kind"))
                    }
                    pushLive()
                }
            }
            "text" -> live[sid]?.let {
                val thought = event.payload.optString("role") == "thought"
                val next = if (thought) "Thinking" else "Writing the reply"
                if (it.activity != next && !it.waiting) { it.activity = next; it.kind = if (thought) "think" else "write"; pushLive() }
            }
            "browser" -> live[sid].also { if (_state.value.navTab == NavTab.BROWSER) refreshTabs() }?.let {
                val host = event.payload.optString("url").substringAfter("://").substringBefore('/').removePrefix("www.")
                it.activity = "Browsing $host"
                it.kind = "read"
                pushLive()
            }
            "error" -> lastError[sid] = event.payload.optString("message", "Something went wrong")
            "routine" -> routineEvent(event.payload)
            "proposal" -> {
                val proposal = Proposal.from(event.payload)
                val known = _state.value.proposals.any { it.id == proposal.id }
                _state.update { s ->
                    val rest = s.proposals.filterNot { it.id == proposal.id }
                    s.copy(proposals = if (proposal.pending) rest + proposal else rest)
                }
                if (proposal.pending && !known && !viewing(sid)) {
                    notifier.approval(sid, titleOf(sid), engineName(sid), "proposes ${proposal.what}: ${proposal.title}")
                }
            }
            "secret" -> {
                val ask = SecretAsk.from(event.payload)
                _state.update { s ->
                    val rest = s.secrets.filterNot { it.id == ask.id }
                    s.copy(secrets = if (ask.pending) rest + ask else rest)
                }
                live[sid]?.let {
                    it.waiting = ask.pending
                    it.activity = if (ask.pending) "Needs you: sign in on ${ask.site}" else "Continuing"
                    pushLive()
                }
                if (ask.pending && !viewing(sid)) notifier.approval(sid, titleOf(sid), engineName(sid), "sign in on ${ask.site}")
            }
            "approval" -> {
                val approval = Approval.from(event.payload)
                _state.update { s ->
                    val rest = s.approvals.filterNot { it.id == approval.id }
                    s.copy(approvals = if (approval.pending) rest + approval else rest)
                }
                live[sid]?.let {
                    it.waiting = approval.pending
                    it.activity = if (approval.pending) "Needs your OK: ${approval.title}" else "Continuing"
                    pushLive()
                }
                if (approval.pending && !viewing(sid)) notifier.approval(sid, titleOf(sid), engineName(sid), approval.title)
            }
            "turn_end" -> {
                if (_state.value.navTab == NavTab.BROWSER) refreshTabs()
                if (!viewing(sid)) when (event.payload.optString("stop_reason")) {
                    "cancelled" -> {}
                    "error" -> notifier.error(sid, titleOf(sid), lastError[sid] ?: "The task stopped unexpectedly")
                    else -> notifier.finished(sid, titleOf(sid), event.payload.optString("preview").ifBlank { "Finished" })
                }
            }
        }
        if (sid == _state.value.openSessionId) {
            val snapshot = synchronized(pendingLive) {
                val current = timeline
                if (current == null) {
                    pendingLive.add(event)
                    null
                } else if (current.apply(event)) {
                    current.snapshot()
                } else {
                    null
                }
            }
            if (snapshot != null) {
                _state.update { it.copy(timeline = snapshot) }
                dropStored()
            }
        }
    }

    private fun routineEvent(payload: JSONObject) {
        val id = payload.optString("id")
        if (payload.optBoolean("deleted")) {
            _state.update { s ->
                s.copy(
                    routines = s.routines.filterNot { it.id == id },
                    openRoutineId = s.openRoutineId.takeUnless { it == id },
                    routineDetail = s.routineDetail?.takeUnless { it.id == id },
                )
            }
            return
        }
        val routine = runCatching { Routine.from(payload) }.getOrNull() ?: return
        _state.update { s -> s.copy(routines = upsertRoutine(s.routines, routine)) }
        // The open page shows the history too: fetch it again (runs change on every run).
        if (_state.value.openRoutineId == id) api?.let { client -> scope.launch(Dispatchers.IO) { loadRoutine(client, id) } }
    }

    private fun upsertRoutine(list: List<Routine>, routine: Routine): List<Routine> =
        (list.filterNot { it.id == routine.id } + routine)
            .sortedWith(compareByDescending<Routine> { it.enabled }.thenBy { it.nextRunAt ?: "9" }.thenBy { it.title })

    // live activity notifications

    private fun liveTask(session: SessionInfo): LiveTask = live.getOrPut(session.id) {
        LiveTask(session.id, session.title, engineName(session.id, session.engine), if (session.status == "waiting") "Needs your OK" else "Working", System.currentTimeMillis(), session.status == "waiting")
    }

    private fun syncLive(sessions: List<SessionInfo>) {
        val busy = sessions.filter { it.isBusy }
        live.keys.retainAll(busy.map { it.id }.toSet())
        busy.forEach { liveTask(it) }
        pushLive()
    }

    private fun pushLive() {
        notifier.live(live.values.map { it.copy() })
        val now = live.mapValues { (_, t) -> LiveInfo(t.activity, t.since, if (t.waiting) "needs" else t.kind, t.toolIds.size) }
        _state.update { it.copy(live = now) }
    }

    /** The face a tool's ACP kind gets: reading for looking things up, writing for changing things. */
    private fun faceKind(kind: String): String = when (kind) {
        "read", "search", "fetch" -> "read"
        "think" -> "think"
        else -> "write"
    }

    private fun engineName(sessionId: String, engineId: String? = null): String {
        val s = _state.value
        val id = engineId ?: s.sessions.firstOrNull { it.id == sessionId }?.engine
        return s.engine(id)?.name ?: id ?: "Krewd"
    }

    private fun viewing(sessionId: String) = inForeground && _state.value.openSessionId == sessionId

    private fun titleOf(sessionId: String) = _state.value.sessions.firstOrNull { it.id == sessionId }?.title ?: "Krewd"

    private fun upsert(list: List<SessionInfo>, session: SessionInfo): List<SessionInfo> {
        val rest = list.filterNot { it.id == session.id }
        if (session.id == _state.value.pendingDelete?.id) return rest
        val merged = if (session.archived) rest else rest + session
        return merged.sortedWith(compareByDescending<SessionInfo> { it.pinned }.thenByDescending { it.updatedAt })
    }

    // navigation

    fun navigate(tab: Int) = _state.update { it.copy(navTab = tab) }

    // routines

    fun openRoutine(id: String) {
        _state.update { it.copy(openRoutineId = id, routineDetail = it.routineDetail?.takeIf { d -> d.id == id }) }
        val client = api ?: return
        scope.launch(Dispatchers.IO) { loadRoutine(client, id) }
    }

    fun closeRoutine() = _state.update { it.copy(openRoutineId = null, routineDetail = null) }

    private fun loadRoutine(client: KrewdApi, id: String) {
        try {
            val routine = client.routine(id)
            _state.update { s ->
                if (s.openRoutineId != id) s
                else s.copy(routineDetail = routine, routines = upsertRoutine(s.routines, routine.copy(runs = null)))
            }
        } catch (e: ApiException) {
            if (e.code == 404) _state.update { it.copy(openRoutineId = null, routineDetail = null, message = "That routine was deleted") }
        } catch (e: Exception) {
            // Offline: the page keeps what it has; the loop reloads it on reconnect.
        }
    }

    /** Creates (no [id]) or changes a routine. [done] runs on the main thread with the saved routine. */
    fun saveRoutine(id: String?, fields: JSONObject, failed: () -> Unit = {}, done: (Routine) -> Unit = {}) = act { client ->
        val routine = try {
            if (id == null) client.createRoutine(fields) else client.updateRoutine(id, fields)
        } catch (e: Exception) {
            launchMain(failed)
            throw e
        }
        _state.update { s -> s.copy(routines = upsertRoutine(s.routines, routine)) }
        if (_state.value.openRoutineId == routine.id) loadRoutine(client, routine.id)
        launchMain { done(routine) }
    }

    fun setRoutineEnabled(id: String, on: Boolean) = saveRoutine(id, JSONObject().put("enabled", on))

    fun runRoutine(id: String, trigger: String? = null) = act { client ->
        client.runRoutine(id, trigger)
        loadRoutine(client, id)
        _state.update { it.copy(message = "Started: it runs in the routine's chat") }
    }

    fun saveRoutineNotes(id: String, notes: String) = act { client ->
        val routine = client.setRoutineNotes(id, notes)
        _state.update { s -> s.copy(routines = upsertRoutine(s.routines, routine)) }
        loadRoutine(client, id)
    }

    /** Drops a follow-up a run asked for ("try again at 19:05"); the trigger's next scheduled time stays. */
    fun cancelFollowUp(routineId: String, triggerId: String) = act { client ->
        client.cancelFollowUp(routineId, triggerId)
        loadRoutine(client, routineId)
    }

    fun deleteRoutine(id: String) = act { client ->
        client.deleteRoutine(id)
        _state.update { s ->
            s.copy(
                routines = s.routines.filterNot { it.id == id },
                openRoutineId = s.openRoutineId.takeUnless { it == id },
                routineDetail = s.routineDetail?.takeUnless { it.id == id },
            )
        }
    }

    /** Checks a schedule on the server (blocking; call off the main thread). Null when offline. */
    fun previewSchedule(schedule: String, timezone: String?): SchedulePreview? = api?.previewSchedule(schedule, timezone)

    /** Opens a routine's chat; with a run, scrolls to the message that started that run. */
    fun openRoutineChat(routine: Routine, run: RoutineRun? = null) {
        val sid = run?.sessionId ?: routine.sessionId ?: run { toast("This routine has no chat yet: it gets one on its next run"); return }
        openSession(sid)
        run?.seq?.let { seq -> _state.update { it.copy(chatFocus = "user-$seq") } }
    }

    fun focusDone() = _state.update { it.copy(chatFocus = null) }

    // chats: pin and compact

    fun setPinned(id: String, on: Boolean) = act { client ->
        val session = client.updateSession(id, JSONObject().put("pinned", on))
        _state.update { it.copy(sessions = upsert(it.sessions, session)) }
    }

    fun switchEngine(id: String, engine: String) = act { client ->
        client.switchEngine(id, engine)
        val name = _state.value.engine(engine)?.name ?: engine
        _state.update { it.copy(message = "Moving to $name after the current turn") }
    }

    fun compact(id: String) = act { client ->
        client.compact(id)
        _state.update { it.copy(message = "Compacting after the current turn") }
    }

    fun openSession(id: String) {
        synchronized(pendingLive) {
            timeline = null
            pendingLive.clear()
        }
        _state.update { it.copy(openSessionId = id, lastSessionId = id, timeline = emptyList(), timelineLoading = true) }
        notifier.clear(id)
        val client = api ?: return
        scope.launch(Dispatchers.IO) { loadTimeline(client, id) }
    }

    fun closeSession() {
        synchronized(pendingLive) { timeline = null }
        _state.update { it.copy(openSessionId = null, timeline = emptyList()) }
    }

    /** From a notification's Stop button: open the task and ask before stopping it. */
    fun askStop(id: String) {
        openSession(id)
        _state.update { it.copy(confirmStop = id) }
    }

    fun answerStop(stop: Boolean) {
        val id = _state.value.confirmStop ?: return
        _state.update { it.copy(confirmStop = null) }
        if (stop) act { it.cancel(id) }
    }

    private fun loadTimeline(client: KrewdApi, id: String) {
        try {
            val fresh = Timeline()
            client.sessionEvents(id, 0).forEach { fresh.apply(it) }
            if (_state.value.openSessionId != id) return
            synchronized(pendingLive) {
                pendingLive.forEach { fresh.apply(it) }
                pendingLive.clear()
                timeline = fresh
            }
            _state.update { it.copy(timeline = fresh.snapshot(), timelineLoading = false) }
            dropStored()
        } catch (e: Exception) {
            _state.update { it.copy(timelineLoading = false, message = "Could not load the task: ${e.message}") }
        }
    }

    // actions

    /**
     * Starts a task from the new-task sheet ([draft] holds its files and @ chats). With [parentId] it is a side
     * session of that chat, a sub-agent when [returns]; then the parent stays open (its strip shows the new chat).
     */
    fun newTask(
        text: String, autoApprove: Boolean, engine: String?, config: Map<String, String>,
        draft: String = NEW_DRAFT, parentId: String? = null, returns: Boolean = false,
    ) = act { client ->
        val files = readyUploads(draft)
        val chats = _state.value.chatsOf(draft)
        val engineId = engine ?: _state.value.engines.firstOrNull { it.isDefault }?.id
        val session = client.createSession(text, autoApprove, engine, config, files, chats = chats, parentId = parentId, returns = returns)
        engineId?.let { id -> prefs.updateEngine(id) { it.copy(autoApprove = autoApprove, config = it.config + config) } }
        clearDraft(draft)
        launchMain { setDraftChats(draft, emptyList()) }
        _state.update { it.copy(sessions = upsert(it.sessions, session)) }
        if (parentId == null) launchMain { openSession(session.id) }
        else _state.update { it.copy(message = "Started ${if (returns) "sub-agent" else "side session"} “${session.title}”") }
    }

    // chats attached with @, side sessions, sub-agents and proposals

    fun addDraftChat(key: String, chat: ChatRef) {
        val now = _state.value.chatsOf(key)
        if (now.any { it.id == chat.id }) return
        if (now.size >= MAX_DRAFT_CHATS) {
            toast("Up to $MAX_DRAFT_CHATS chats per message")
            return
        }
        setDraftChats(key, now + chat)
    }

    fun removeDraftChat(key: String, id: String) = setDraftChats(key, _state.value.chatsOf(key).filterNot { it.id == id })

    fun setDraftChats(key: String, chats: List<ChatRef>) {
        prefs.setChatDraft(key, chats)
        _state.update { s -> s.copy(draftChats = if (chats.isEmpty()) s.draftChats - key else s.draftChats + (key to chats)) }
    }

    /** Archived chats for @, once per connection; older servers or errors leave it empty. */
    fun loadArchived() {
        if (_state.value.archived != null) return
        _state.update { it.copy(archived = emptyList()) }
        val client = api ?: return
        scope.launch(Dispatchers.IO) {
            val list = runCatching { client.archivedSessions() }.getOrDefault(emptyList())
            _state.update { it.copy(archived = list) }
        }
    }

    /** Sends an agent's proposal as the owner edited it; [done] gets the chat it started or messaged, or an error. */
    fun sendProposal(
        proposal: Proposal, text: String, title: String?, engine: String?, config: Map<String, String>, returns: Boolean,
        draft: String, done: (String?, String?) -> Unit,
    ) {
        val chats = _state.value.chatsOf(draft)
        request({ client ->
            val body = JSONObject().put("text", text).put("returns", returns)
            if (!title.isNullOrBlank()) body.put("title", title)
            if (!proposal.isPrompt) {
                engine?.let { body.put("engine", it) }
                if (config.isNotEmpty()) body.put("config", JSONObject(config))
            }
            if (chats.isNotEmpty()) body.put("chats", org.json.JSONArray(chats.map { JSONObject().put("id", it.id) }))
            client.sendProposal(proposal.id, body)
        }) { sent, error ->
            if (sent != null) {
                setDraftChats(draft, emptyList())
                _state.update { s -> s.copy(proposals = s.proposals.filterNot { it.id == proposal.id }) }
                val what = when {
                    proposal.isPrompt -> "Sent to ${proposal.targetTitle ?: "the chat"}"
                    returns -> "Started the sub-agent"
                    else -> "Started the side session"
                }
                _state.update { it.copy(message = what) }
            }
            done(sent?.resultId, error)
        }
    }

    fun dismissProposal(proposal: Proposal) = act { client ->
        client.dismissProposal(proposal.id)
        _state.update { s -> s.copy(proposals = s.proposals.filterNot { it.id == proposal.id }) }
    }

    /** A side session: send its finished replies back to the parent (a sub-agent) or not. */
    fun setReturns(id: String, on: Boolean) = act { client ->
        val session = client.updateSession(id, JSONObject().put("returns", on))
        _state.update { it.copy(sessions = upsert(it.sessions, session)) }
    }

    /** What a new task on [engineId] starts with: the owner's last picks for that agent. */
    fun engineDefaults(engineId: String?): EngineDefaults = engineId?.let(prefs::engine) ?: EngineDefaults()

    // The new-task card saves each pick at once, so leaving the screen or switching agents loses nothing.

    fun lastEngine(): String? = prefs.lastEngine()

    fun setNewTaskEngine(id: String) = prefs.setLastEngine(id)

    fun rememberPick(engineId: String, configId: String, value: String) =
        prefs.updateEngine(engineId) { it.copy(config = it.config + (configId to value)) }

    fun rememberAutoApprove(engineId: String, on: Boolean) = prefs.updateEngine(engineId) { it.copy(autoApprove = on) }

    fun setNewTaskText(text: String) {
        prefs.setTextDraft(NEW_DRAFT, text)
        _state.update { it.copy(newTaskText = text) }
    }

    /** What was typed in a chat's composer and not sent yet, kept on the phone across leaving the chat and restarts. */
    fun messageDraft(sessionId: String): String = prefs.textDraft(sessionId)

    fun setMessageDraft(sessionId: String, text: String) = prefs.setTextDraft(sessionId, text)

    fun pipSize(wide: Boolean): Float = prefs.pipSize(wide)

    fun setPipSize(wide: Boolean, dp: Float) = prefs.setPipSize(wide, dp)

    fun savePrompt(text: String) {
        val clean = text.trim().takeIf { it.isNotEmpty() } ?: return
        val list = (listOf(clean) + _state.value.savedPrompts.filterNot { it == clean }).take(MAX_SAVED_PROMPTS)
        prefs.setSavedPrompts(list)
        _state.update { it.copy(savedPrompts = list) }
    }

    fun forgetPrompt(text: String) {
        val list = _state.value.savedPrompts.filterNot { it == text }
        prefs.setSavedPrompts(list)
        _state.update { it.copy(savedPrompts = list) }
    }

    /** A small still of a browser tab for the Krewd tab's working cards; null when the server has none. */
    suspend fun tabSnapshot(tabId: String): androidx.compose.ui.graphics.ImageBitmap? {
        val client = api ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val bytes = client.tabSnapshot(tabId, SNAPSHOT_WIDTH)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }

    /**
     * Sends a message to the open task. [delivery]: steer, queue or interrupt while a turn runs, or null
     * for the server's default. The message shows at once; if it does not reach the server it is marked
     * "Not sent" and [resend] tries again with the same id, so it is never stored twice.
     */
    fun send(text: String, delivery: String?) {
        val id = _state.value.openSessionId ?: return
        val message = OutgoingMessage(
            clientId = UUID.randomUUID().toString(),
            sessionId = id,
            text = text,
            uploads = readyUploads(id),
            files = _state.value.draft(id).filter { it.uploadId != null },
            tabs = _state.value.draftTabs[id].orEmpty(),
            delivery = delivery,
            chats = _state.value.chatsOf(id),
        )
        clearDraft(id)
        prefs.setTextDraft(id, "")
        setDraftChats(id, emptyList())
        _state.update { it.copy(outbox = it.outbox + message, draftTabs = it.draftTabs - id) }
        deliver(message)
    }

    fun resend(clientId: String) {
        val message = _state.value.outbox.firstOrNull { it.clientId == clientId } ?: return
        _state.update { s -> s.copy(outbox = s.outbox.map { if (it.clientId == clientId) it.copy(failed = false) else it }) }
        deliver(message.copy(failed = false))
    }

    private fun deliver(message: OutgoingMessage) {
        val client = api
        if (client == null) {
            markFailed(message.clientId)
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                client.send(message.sessionId, message.text, message.uploads, message.tabs, message.delivery, message.clientId, message.chats)
                dropStored(message.clientId)
            } catch (e: ApiException) {
                // The server answered but refused: show why; resending would fail the same way.
                markFailed(message.clientId)
                _state.update { it.copy(message = e.message ?: "Not sent") }
            } catch (e: Exception) {
                markFailed(message.clientId)
            }
        }
    }

    private fun markFailed(clientId: String) =
        _state.update { s -> s.copy(outbox = s.outbox.map { if (it.clientId == clientId) it.copy(failed = true) else it }) }

    /** Drops local rows the server has stored (its own row with the same client id is in the chat now). */
    private fun dropStored(clientId: String? = null) {
        val stored = synchronized(pendingLive) { timeline?.clientIds?.toSet() }.orEmpty()
        _state.update { s ->
            val keep = s.outbox.filterNot { it.clientId in stored }
            if (keep.size == s.outbox.size && clientId == null) s else s.copy(outbox = keep)
        }
        if (clientId != null && clientId !in stored) {
            // Stored, but its event has not arrived yet (or this chat is closed): it is safe to drop once seen.
            scope.launch {
                delay(4_000)
                _state.update { s -> s.copy(outbox = s.outbox.filterNot { it.clientId == clientId && !it.failed }) }
            }
        }
    }

    /** After a reconnect, messages that failed while offline go again (same client ids: no duplicates). */
    private fun retryOutbox() = _state.value.outbox.filter { it.failed }.forEach { resend(it.clientId) }

    /** Rows for the open chat that are not stored on the server yet. */
    fun localRows(sessionId: String): List<UserItem> = _state.value.outbox.filter { it.sessionId == sessionId }.map {
        UserItem(
            key = "local-${it.clientId}",
            text = it.text,
            tabs = it.tabs,
            clientId = it.clientId,
            delivery = it.delivery,
            state = if (it.failed) "failed" else "sending",
            chats = it.chats,
        )
    }

    // attachments: each picked file uploads at once; the message carries the upload ids

    private var nextLocalId = 1L

    fun attach(draft: String, uris: List<android.net.Uri>) {
        val client = api ?: run { _state.update { it.copy(message = "Not connected to Krewd") }; return }
        val resolver = app.contentResolver
        uris.forEach { uri ->
            val localId = synchronized(this) { nextLocalId++ }
            var name = "file"
            var size: Long? = null
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { c.getString(it) }?.let { name = it }
                    c.getColumnIndex(android.provider.OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                }
            }
            updateDraft(draft) { it + PendingFile(localId, name, size, uri = uri.toString(), mime = resolver.getType(uri)) }
            scope.launch(Dispatchers.IO) {
                val result = runCatching {
                    val input = resolver.openInputStream(uri) ?: error("Cannot read $name")
                    client.upload(name, resolver.getType(uri), input)
                }
                updateDraft(draft) { list ->
                    list.map { f ->
                        if (f.localId != localId) f
                        else result.fold({ id -> f.copy(uploadId = id) }, { e -> f.copy(error = e.message ?: "Upload failed") })
                    }
                }
            }
        }
    }

    fun removeAttachment(draft: String, localId: Long) = updateDraft(draft) { list -> list.filterNot { it.localId == localId } }

    private fun readyUploads(draft: String) = _state.value.draft(draft).mapNotNull { it.uploadId }

    private fun clearDraft(draft: String) = updateDraft(draft) { emptyList() }

    private fun updateDraft(draft: String, change: (List<PendingFile>) -> List<PendingFile>) =
        _state.update { s -> s.copy(drafts = s.drafts + (draft to change(s.draft(draft)))) }

    fun stop() {
        val id = _state.value.openSessionId ?: return
        act { it.cancel(id) }
    }

    fun decide(approval: Approval, optionId: String) = act { it.decide(approval.id, optionId) }

    fun setAutoApprove(id: String, on: Boolean) = act { client ->
        val session = client.updateSession(id, JSONObject().put("auto_approve", on))
        prefs.updateEngine(session.engine) { it.copy(autoApprove = on) }
    }

    fun setMode(id: String, mode: String) = act { client ->
        val session = client.updateSession(id, JSONObject().put("mode", mode))
        prefs.updateEngine(session.engine) { it.copy(mode = mode) }
    }

    fun setConfig(id: String, configId: String, value: String) = act { client ->
        val session = client.updateSession(id, JSONObject().put("config", JSONObject().put(configId, value)))
        prefs.updateEngine(session.engine) { it.copy(config = it.config + (configId to value)) }
        _state.update { it.copy(sessions = upsert(it.sessions, session)) }
    }

    /** What a new task on [engineId] can choose: the engine's advertised options, else its latest task's. */
    /**
     * What the agent lists now (models, effort, permission mode). Empty while the server is listing them again, for
     * a new agent or after a switch of profile or sign-in: never an older chat's options, which can be another
     * gateway's models.
     */
    fun engineConfig(engineId: String?): List<ConfigOption> {
        val s = _state.value
        val engine = s.engines.firstOrNull { it.id == engineId } ?: s.engines.firstOrNull { it.isDefault }
        return engine?.config.orEmpty()
    }

    /** Re-read engines (their options appear once the server has probed a new engine). */
    fun refreshEngines() = act { client ->
        val engines = client.state().engines
        _state.update { it.copy(engines = engines) }
    }

    private var relistJob: Job? = null

    /**
     * After a switch of profile, a sign-in or a sign-out the server restarts the agent and lists its models again
     * (about 8 s). Read the engines again until [engine] has its new list, so every picker shows it without a restart.
     */
    private fun followRelist(engine: String) {
        relistJob?.cancel()
        relistJob = scope.launch(Dispatchers.IO) {
            repeat(15) {
                delay(3_000)
                val client = api ?: return@launch
                val engines = runCatching { client.state().engines }.getOrNull() ?: return@repeat
                _state.update { it.copy(engines = engines) }
                val info = engines.firstOrNull { it.id == engine } ?: return@launch
                if (info.config.isNotEmpty() || info.problem != null) return@launch
            }
        }
    }

    // profiles and sign-ins (Settings)

    /** Profiles, presets, sign-ins and engines, read again together (they change one another). */
    fun loadProfiles() = act { client -> reloadProfiles(client) }

    private fun reloadProfiles(client: KrewdApi) {
        val profiles = client.profiles()
        val logins = client.logins()
        val engines = client.state().engines
        _state.update { it.copy(profiles = profiles, logins = logins, engines = engines) }
    }

    /** Create (id null) or change a profile; [done] gets null on success, else what went wrong. */
    fun saveProfile(id: String?, fields: JSONObject, key: String?, done: (String?) -> Unit) {
        if (!key.isNullOrBlank() && _state.value.settings.paired) return saveProfileSigned(id, fields, key, done)
        saveProfilePlain(id, fields, key, done)
    }

    /** A gateway key on a paired phone: the owner's fingerprint signs this exact request. */
    private fun saveProfileSigned(id: String?, fields: JSONObject, key: String, done: (String?) -> Unit) {
        val client = api ?: return done("Not connected to Krewd")
        scope.launch {
            val error = try {
                val target = withContext(Dispatchers.IO) { KrewdApi.forKeys(client, _state.value.settings, creds) }
                fields.put("key", key.trim())
                val method = if (id == null) "POST" else "PATCH"
                val path = if (id == null) "/api/profiles" else "/api/profiles/$id"
                val text = fields.toString()
                val header = presence(target, method, path, text, "Save the gateway key", "Krewd stores it on the server")
                withContext(Dispatchers.IO) {
                    target.send(method, path, text, mapOf("X-Krewd-Presence" to header))
                    reloadProfiles(client)
                }
                null
            } catch (e: Exception) {
                e.message ?: "Request failed"
            }
            done(error)
        }
    }

    private fun saveProfilePlain(id: String?, fields: JSONObject, key: String?, done: (String?) -> Unit) = withResult(done) { client ->
        val target = if (key.isNullOrBlank()) client else KrewdApi.forKeys(client, _state.value.settings, creds)
        if (!key.isNullOrBlank()) fields.put("key", key.trim())
        target.saveProfile(id, fields)
        reloadProfiles(client)
    }

    fun deleteProfile(id: String, done: (String?) -> Unit) = withResult(done) { client ->
        client.deleteProfile(id)
        reloadProfiles(client)
    }

    fun refreshLogins() = act { client ->
        val logins = client.logins()
        val before = _state.value.logins.associateBy { it.engine }
        _state.update { it.copy(logins = logins) }
        // A sign-in that finished by itself (Codex) restarts an agent on its sign-in account: follow its new list.
        logins.firstOrNull { it.loggedIn == true && before[it.engine]?.loggedIn != true }?.let { followRelist(it.engine) }
    }

    fun startLogin(engine: String, done: (String?) -> Unit) = withResult(done) { client ->
        client.startLogin(engine)
        _state.update { it.copy(logins = client.logins()) }
    }

    fun submitLoginCode(engine: String, code: String, done: (String?) -> Unit) = withResult(done) { client ->
        client.submitLoginCode(engine, code.trim())
        reloadProfiles(client)
        followRelist(engine)
    }

    fun cancelLogin(engine: String) = act { client ->
        client.cancelLogin(engine)
        _state.update { it.copy(logins = client.logins()) }
    }

    fun logout(engine: String) = act { client ->
        client.logout(engine)
        reloadProfiles(client)
        followRelist(engine)
    }

    fun setAgentProfile(engine: String, profile: String?) = act { client ->
        client.setAgentProfile(engine, profile)
        reloadProfiles(client)
        followRelist(engine)
    }

    /** Runs [block] off the main thread and reports null (it worked) or the error message to [done], on the main thread. */
    private fun withResult(done: (String?) -> Unit, block: (KrewdApi) -> Unit) {
        val client = api ?: return done("Not connected to Krewd")
        scope.launch(Dispatchers.IO) {
            val error = try {
                block(client)
                null
            } catch (e: Exception) {
                e.message ?: "Request failed"
            }
            withContext(Dispatchers.Main) { done(error) }
        }
    }

    /** One server call for a screen that keeps its own state: [done] gets the result or what went wrong, on the main thread. */
    fun <T : Any> request(block: (KrewdApi) -> T, done: (T?, String?) -> Unit) {
        val client = api ?: return done(null, "Not connected to Krewd")
        scope.launch(Dispatchers.IO) {
            val result = runCatching { block(client) }
            withContext(Dispatchers.Main) { done(result.getOrNull(), result.exceptionOrNull()?.let { it.message ?: "Request failed" }) }
        }
    }

    fun archive(id: String) = act { client ->
        client.updateSession(id, JSONObject().put("archived", true))
        _state.update { s -> s.copy(sessions = s.sessions.filterNot { it.id == id }) }
        launchMain { if (_state.value.openSessionId == id) closeSession() }
    }

    // delete, with 3 seconds to undo

    private var deleteJob: Job? = null

    /** Hides the task now and deletes it on the server after [UNDO_MS], unless [undoDelete] comes first. */
    fun deleteSession(id: String) {
        commitDelete() // a previous delete goes through at once
        val session = _state.value.sessions.firstOrNull { it.id == id } ?: return
        _state.update { s -> s.copy(sessions = s.sessions.filterNot { it.id == id }, pendingDelete = session) }
        deleteJob = scope.launch {
            delay(UNDO_MS)
            commitDelete()
        }
    }

    fun undoDelete() {
        deleteJob?.cancel()
        val session = _state.value.pendingDelete ?: return
        _state.update { s -> s.copy(pendingDelete = null, sessions = upsert(s.sessions, session)) }
    }

    private fun commitDelete() {
        deleteJob?.cancel()
        val session = _state.value.pendingDelete ?: return
        _state.update { it.copy(pendingDelete = null) }
        live.remove(session.id)
        pushLive()
        notifier.clear(session.id)
        prefs.setTextDraft(session.id, "")
        act { it.deleteSession(session.id) }
    }

    /** Deletes a chat on the server at once (Settings > Storage confirms first, so there is no Undo). */
    fun deleteSessionNow(id: String, done: (String?) -> Unit) {
        request({ it.deleteSession(id) }) { _, problem ->
            if (problem == null) {
                _state.update { s -> s.copy(sessions = s.sessions.filterNot { it.id == id }) }
                live.remove(id)
                pushLive()
                notifier.clear(id)
                prefs.setTextDraft(id, "")
                if (_state.value.openSessionId == id) closeSession()
            }
            done(problem)
        }
    }

    // Pictures decoded for the screen: thumbnails and full-screen views stay in memory up to about 48 MB.
    private val images = object : android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: androidx.compose.ui.graphics.ImageBitmap) = value.width * value.height * 4
    }

    /** An image from a tool result, or null when not connected. Throws if the server cannot give it. */
    suspend fun toolImage(name: String): androidx.compose.ui.graphics.ImageBitmap? = image(ImageRef.Tool(name), 1600)

    /**
     * A picture decoded so its longer side is at most about [maxSide] pixels (camera rotation applied), or null
     * when it needs the server and the app is not connected. Throws if it cannot be read or is not a picture.
     */
    suspend fun image(ref: ImageRef, maxSide: Int): androidx.compose.ui.graphics.ImageBitmap? {
        val key = "${ref.key}@$maxSide"
        images.get(key)?.let { return it }
        if (ref !is ImageRef.Local && api == null) return null
        val bitmap = withContext(Dispatchers.IO) {
            val source = when (ref) {
                is ImageRef.Local -> android.graphics.ImageDecoder.createSource(app.contentResolver, Uri.parse(ref.uri))
                else -> android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(imageBytes(ref)))
            }
            try {
                android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val side = maxOf(info.size.width, info.size.height)
                    var sample = 1
                    while (side / (sample * 2) >= maxSide) sample *= 2
                    decoder.setTargetSampleSize(sample)
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                }.asImageBitmap()
            } catch (e: java.io.IOException) {
                throw IllegalStateException(if (ref is ImageRef.Local) "Could not read the picture" else "Not a picture", e)
            }
        }
        images.put(key, bitmap)
        return bitmap
    }

    // Downloaded originals, so a thumbnail, the full-screen copy and Save or Share fetch a picture once.
    private val imageFiles = object : android.util.LruCache<String, ByteArray>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }

    private fun imageBytes(ref: ImageRef): ByteArray {
        if (ref is ImageRef.Local) {
            return app.contentResolver.openInputStream(Uri.parse(ref.uri))?.use { it.readBytes() } ?: error("Could not read the picture")
        }
        imageFiles.get(ref.key)?.let { return it }
        val client = api ?: error("Not connected to Krewd")
        val bytes = when (ref) {
            is ImageRef.Tool -> client.toolImage(ref.file)
            is ImageRef.Sent -> client.attachment(ref.file.path.substringAfterLast('/'))
            is ImageRef.Local -> error("unreachable")
        }
        imageFiles.put(ref.key, bytes)
        return bytes
    }

    /** Saves the original picture (not the decoded copy) to Pictures/Krewd, where the gallery finds it. */
    fun saveImage(ref: ImageRef) {
        scope.launch {
            try {
                val name = withContext(Dispatchers.IO) {
                    val bytes = imageBytes(ref)
                    val mime = imageMime(ref, bytes)
                    val resolver = app.contentResolver
                    val name = ref.name.substringAfterLast('/').ifBlank { "image" }.let { n ->
                        val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                        if (n.contains('.') || ext == null) n else "$n.$ext"
                    }
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, mime)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Krewd")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Could not save to Pictures")
                    try {
                        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
                        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                    } catch (e: Exception) {
                        resolver.delete(uri, null, null)
                        throw e
                    }
                    name
                }
                toast("Saved $name to Pictures/Krewd")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(e.message ?: "Could not save the picture")
            }
        }
    }

    /** Shares the original picture with another app (the system share sheet). */
    fun shareImage(ref: ImageRef) {
        scope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    if (ref is ImageRef.Local) return@withContext Uri.parse(ref.uri) to (ref.mime ?: "image/*")
                    val bytes = imageBytes(ref)
                    val dir = java.io.File(app.cacheDir, "shared-images").apply { mkdirs() }
                    dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 3_600_000) it.delete() }
                    val file = java.io.File(dir, ref.name.substringAfterLast('/').ifBlank { "image" })
                    file.writeBytes(bytes)
                    androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.images", file) to imageMime(ref, bytes)
                }
                val send = Intent(Intent.ACTION_SEND).setType(uri.second).putExtra(Intent.EXTRA_STREAM, uri.first)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                app.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(e.message ?: "Could not share the picture")
            }
        }
    }

    private fun imageMime(ref: ImageRef, bytes: ByteArray): String {
        val known = when (ref) {
            is ImageRef.Local -> ref.mime
            is ImageRef.Sent -> ref.file.mime
            is ImageRef.Tool -> null
        }?.takeIf { it.startsWith("image/") }
        if (known != null) return known
        val ext = ref.name.substringAfterLast('.', "").lowercase()
        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outMimeType ?: "image/png"
    }

    private val _downloading = MutableStateFlow<Set<String>>(emptySet())
    /** Paths of handed-back files being downloaded right now. */
    val downloading: StateFlow<Set<String>> = _downloading.asStateFlow()
    private val saved = HashMap<String, Uri>() // "path|size" -> its copy in Downloads

    /** Saves a file the agent handed back to Downloads/Krewd (once), then opens it. */
    fun openSharedFile(file: Attachment) {
        val client = api ?: run { toast("Not connected to Krewd"); return }
        if (file.path in _downloading.value) return
        val key = "${file.path}|${file.size}"
        saved[key]?.takeIf { exists(it) }?.let { if (view(it, file.mime)) return }
        _downloading.update { it + file.path }
        scope.launch {
            try {
                val uri = withContext(Dispatchers.IO) { saveToDownloads(client, file) }
                saved[key] = uri
                if (!view(uri, file.mime)) toast("Saved ${file.name} to Downloads/Krewd")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(e.message ?: "Download failed")
            } finally {
                _downloading.update { it - file.path }
            }
        }
    }

    private fun saveToDownloads(client: KrewdApi, file: Attachment): Uri {
        val resolver = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, file.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Krewd")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Could not save to Downloads")
        try {
            resolver.openOutputStream(uri)!!.use { client.downloadFile(file.path, it) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /** Opens a saved file in an app that can show it; false when no app can. */
    private fun view(uri: Uri, mime: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { app.startActivity(intent) }.isSuccess
    }

    private fun exists(uri: Uri): Boolean = runCatching { app.contentResolver.openFileDescriptor(uri, "r")?.close() }.isSuccess

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun toast(text: String) = _state.update { it.copy(message = text) }

    /** Emergency: the server stops its agents and exits; systemd starts it again. The app reconnects by itself. */
    fun restartBackend() = act { client ->
        sawDown = false
        _state.update { it.copy(restarting = true) }
        try {
            client.restartServer()
        } catch (e: Exception) {
            // The server may already be gone; the loop notices either way.
        }
        scope.launch {
            delay(90_000)
            if (_state.value.restarting) _state.update { it.copy(restarting = false, message = "Krewd did not come back yet; still retrying") }
        }
    }

    // the agents' browser

    fun refreshTabs() = act { client -> setTabs(client.browserTabs()) }

    /** Keeps the tab pills in a stable order: tabs stay where they are, new ones go to the end. */
    private fun setTabs(tabs: BrowserTabs) {
        val ids = tabs.tabs.map { it.id }
        synchronized(tabOrder) {
            tabOrder.retainAll(ids.toSet())
            ids.filterNot { it in tabOrder }.forEach { tabOrder.add(it) }
        }
        val byId = tabs.tabs.associateBy { it.id }
        val ordered = synchronized(tabOrder) { tabOrder.mapNotNull { byId[it] } }
        _state.update { s ->
            val selected = s.browserTabId?.takeIf { it in byId } ?: ordered.firstOrNull()?.id
            s.copy(browser = tabs.copy(tabs = ordered), browserTabId = selected)
        }
    }

    fun selectTab(id: String) = _state.update { it.copy(browserTabId = id) }

    /** Opens the Browser screen on a tab (from a chat's picture-in-picture or menu). */
    fun openBrowserAt(tabId: String?) {
        _state.update { it.copy(navTab = NavTab.BROWSER, browserTabId = tabId ?: it.browserTabId) }
        closeSession()
    }

    fun newTab(url: String? = null, done: (BrowserTab) -> Unit = {}) = act { client ->
        val tab = client.newTab(url)
        setTabs(client.browserTabs())
        _state.update { it.copy(browserTabId = tab.id) }
        launchMain { done(tab) }
    }

    fun closeTab(id: String) = act { client ->
        client.closeBrowserTab(id)
        if (_state.value.browserTabId == id) _state.update { it.copy(browserTabId = null) }
        setTabs(client.browserTabs())
    }

    /** The size of the live view on the phone (CSS pixels and scale), for laying a tab out vertically. */
    @Volatile var viewBox: Triple<Int, Int, Float>? = null

    /**
     * Vertical (laid out for the phone) or horizontal (the normal 1080p window), per tab. The server keeps
     * it, and drops vertical when an agent uses the tab, so coming back never undoes the agent's layout.
     */
    fun setTabVertical(tabId: String, on: Boolean) {
        val box = viewBox
        if (on && box == null) return
        markTab(tabId) { it.copy(vertical = on) }
        act { client ->
            try {
                if (on) client.setTabView(tabId, true, box!!.first, box.second, box.third) else client.setTabView(tabId, false, 0, 0, 0f)
            } catch (e: ApiException) {
                markTab(tabId) { it.copy(vertical = false) }
                throw e
            }
        }
    }

    private fun markTab(tabId: String, change: (BrowserTab) -> BrowserTab) = _state.update { s ->
        val b = s.browser ?: return@update s
        s.copy(browser = b.copy(tabs = b.tabs.map { if (it.id == tabId) change(it) else it }))
    }

    /** Opens the chat of the agent using a tab (from the view-only bar). */
    fun openChatOf(sessionId: String) {
        navigate(NavTab.TASKS)
        openSession(sessionId)
    }

    /** Standard (30 fps) or high quality live view; the stream restarts with the new setting. */
    fun setHighQuality(on: Boolean) {
        prefs.setHighQuality(on)
        _state.update { it.copy(highQuality = on) }
        val tabId = watched ?: return
        api?.let { startStream(it, tabId, keepFrame = true) }
    }

    /** Lays the tab out for the phone (vertical) or the normal 1080p window (horizontal). */
    fun applyView(tabId: String, vertical: Boolean, width: Int, height: Int, scale: Float) = act { client ->
        client.setTabView(tabId, vertical, width, height, scale)
    }

    fun inputCursor(engineId: String?): Boolean = prefs.cursorMode(engineId)

    fun setInputCursor(engineId: String?, cursor: Boolean) = prefs.setCursorMode(engineId, cursor)

    fun addTabToChat(sessionId: String, tab: BrowserTab) {
        _state.update { s ->
            val list = s.draftTabs[sessionId].orEmpty().filterNot { it.id == tab.id } + tab
            s.copy(draftTabs = s.draftTabs + (sessionId to list))
        }
        openSession(sessionId)
    }

    fun removeDraftTab(sessionId: String, tabId: String) = _state.update { s ->
        s.copy(draftTabs = s.draftTabs + (sessionId to s.draftTabs[sessionId].orEmpty().filterNot { it.id == tabId }))
    }

    fun showPip(sessionId: String, tabId: String) {
        _state.update { s -> s.copy(pipTabs = s.pipTabs + (sessionId to tabId), pipHidden = s.pipHidden - "$sessionId:$tabId") }
        openSession(sessionId)
    }

    /** Hides a chat's picture-in-picture. The task keeps running. */
    fun hidePip(sessionId: String, tabId: String) =
        _state.update { s -> s.copy(pipHidden = s.pipHidden + "$sessionId:$tabId", pipTabs = s.pipTabs - sessionId) }

    /** Streams [tabId] live into [frame], for whichever screen shows it. One stream at a time. */
    fun watchTab(tabId: String) {
        if (watched == tabId && streamJob?.isActive == true) return
        val client = api ?: return
        watched = tabId
        startStream(client, tabId)
    }

    fun unwatch(tabId: String) {
        if (watched != tabId) return
        watched = null
        stopStream()
        _frame.value = null
    }

    // Input goes out one request at a time, in order; pointer moves are merged so only the latest is sent.
    private val inputQueue = ArrayDeque<Triple<String, JSONObject, ((JSONObject) -> Unit)?>>()
    private val inputSignal = Channel<Unit>(Channel.CONFLATED)

    fun tabInput(tabId: String, event: JSONObject, onResult: ((JSONObject) -> Unit)? = null) {
        synchronized(inputQueue) {
            val last = inputQueue.lastOrNull()
            if (event.isMove() && last != null && last.first == tabId && last.second.isMove() && last.third == null) {
                inputQueue.removeLast()
            }
            inputQueue.addLast(Triple(tabId, event, onResult))
        }
        inputSignal.trySend(Unit)
    }

    private fun JSONObject.isMove() = optString("type") == "mouse" && optString("action") == "move"

    private fun startInputWorker() = scope.launch(Dispatchers.IO) {
        for (signal in inputSignal) {
            while (true) {
                val next = synchronized(inputQueue) { inputQueue.removeFirstOrNull() } ?: break
                val client = api ?: continue
                try {
                    val result = client.browserInput(next.first, next.second)
                    next.third?.let { callback -> launchMain { callback(result) } }
                } catch (e: ApiException) {
                    if (e.code == 404) refreshTabs()
                } catch (e: Exception) {
                    // A dropped pointer move is harmless; the next one carries the position.
                }
            }
        }
    }

    private fun startStream(client: KrewdApi, tabId: String, keepFrame: Boolean = false) {
        stopStream()
        if (!keepFrame) _frame.value = null
        streamJob = scope.launch(Dispatchers.IO) {
            var failures = 0
            while (watched == tabId) {
                try {
                    val s = client.openBrowserStream(tabId, _state.value.highQuality)
                    stream = s
                    s.read { frame ->
                        failures = 0
                        if (watched == tabId) _frame.value = frame
                        if (_state.value.streamError != null) _state.update { it.copy(streamError = null) }
                    }
                } catch (e: Exception) {
                    if (watched != tabId) break
                    failures++
                    _state.update { it.copy(streamError = e.message ?: "The live view stopped") }
                    if (e is ApiException && e.code == 404) {
                        refreshTabs()
                        break
                    }
                }
                delay(if (failures > 3) 5_000 else 1_000)
            }
        }
    }

    private fun stopStream() {
        streamJob?.cancel()
        streamJob = null
        val s = stream
        stream = null
        // disconnect() unblocks the reading thread; do it off the main thread.
        if (s != null) scope.launch(Dispatchers.IO) { runCatching { s.close() } }
    }

    // security: this phone's keys, the owner's security key, fingerprint-signed requests

    /** Signs one request with the presence key after the owner's fingerprint; returns the X-Krewd-Presence header. */
    private suspend fun presence(client: KrewdApi, method: String, path: String, text: String, title: String, subtitle: String): String {
        val nonce = withContext(Dispatchers.IO) { client.nonce("presence").getString("nonce") }
        val front = activity?.get() ?: throw java.io.IOException("Open Krewd to confirm")
        val message = "krewd-presence-v1\n$nonce\n$method\n$path\n${b64(sha256(text.toByteArray()))}"
        val sig = withContext(Dispatchers.Main) { Presence.sign(front, title, subtitle, message.toByteArray()) }
        return "$nonce.${b64(sig)}"
    }

    /** What the security key screen says: plug in or hold the key, then touch it. */
    private val keyStep = U2f.Step { text -> _state.update { it.copy(securityStep = text) } }

    private var keyJob: Job? = null

    /** Stops waiting for the security key (the owner tapped Cancel). */
    fun cancelKeyStep() {
        keyJob?.cancel()
        _state.update { it.copy(securityStep = null) }
    }

    fun loadSecurity() = act { client ->
        val o = client.securityState()
        val devices = o.optJSONArray("devices").objects().map(Device::from)
        val keys = o.optJSONArray("keys").objects().map(OwnerKey::from)
        _state.update { it.copy(devices = devices, ownerKeys = keys, passkeyRpId = o.optStringOrNull("passkey_rp_id")) }
    }

    /**
     * An owner key's signature over what the server expects for [purpose] (the same 32 bytes for both kinds of key):
     * the request fields, `assertion` (security key) or `passkey`. [n] is the nonce answer, which lists the keys.
     */
    private suspend fun ownerSign(front: android.app.Activity, n: JSONObject, purpose: String, payload: String, method: OwnerMethod,
                                  allowPasskeys: List<String>? = null): JSONObject {
        val nonce = n.getString("nonce")
        val challenge = sha256("krewd-u2f-v1\n$purpose\n$nonce\n$payload".toByteArray())
        val keys = n.optJSONArray("keys").objects().map(OwnerKey::from)
        return when (method) {
            OwnerMethod.PASSKEY -> {
                val rp = n.optStringOrNull("passkey_rp_id") ?: throw java.io.IOException("Passkeys are not set up on the Krewd server")
                val allow = allowPasskeys ?: keys.filter { it.passkey }.mapNotNull { it.credentialId }
                if (allow.isEmpty()) throw java.io.IOException("No phone passkey is registered with Krewd yet")
                _state.update { it.copy(securityStep = "Confirm with your passkey") }
                JSONObject().put("passkey", Passkeys.sign(front, rp, challenge, allow))
            }
            OwnerMethod.SECURITY_KEY -> {
                val handle = keys.firstOrNull { !it.passkey }?.keyHandle ?: n.optStringOrNull("key_handle")
                    ?: throw java.io.IOException("No security key is registered with Krewd")
                val tap = U2f.tap(front, unb64(n.getString("app_param")), challenge, keyHandle = unb64(handle), step = keyStep)
                JSONObject().put("assertion", b64(tap.assertion))
            }
        }
    }

    private fun JSONObject.merge(other: JSONObject): JSONObject = apply { other.keys().forEach { put(it, other.get(it)) } }

    /** A client for pairing: the normal check, or, for a phone with no pin yet, the capturing one (see Tls.Capture). */
    private fun pairingClient(settings: ConnectionSettings, setup: Credentials): KrewdApi {
        var last: Exception? = null
        for (address in settings.addresses) {
            val normal = KrewdApi(address, setup, settings.pin)
            try {
                normal.probe(4_000)
                return normal
            } catch (e: Exception) {
                last = e
            }
            if (settings.pin.isBlank()) {
                val capturing = KrewdApi(address, setup, "", Tls.Capture())
                try {
                    capturing.probe(4_000)
                    return capturing
                } catch (e: Exception) {
                    last = e
                }
            }
        }
        throw last ?: java.io.IOException("Add the server's address first")
    }

    /**
     * Pairs this phone with a tap of the owner's security key. The first phone (setup token) registers the key in
     * the same tap; later phones need only the key. Afterwards the token is gone from this phone.
     */
    fun pairPhone(name: String, method: OwnerMethod, done: (String?) -> Unit) {
        val front = activity?.get() ?: return done("Open Krewd first")
        keyJob = scope.launch {
            val error = try {
                _state.update { it.copy(securityStep = "Getting ready…") }
                val settings = prefs.load()
                val setup = Credentials(settings.token, "")
                val (client, n, keys) = withContext(Dispatchers.IO) {
                    val client = pairingClient(settings, setup)
                    Keys.delete()
                    Triple(client, client.nonce("pair"), Keys.ensure())
                }
                val first = !n.optBoolean("paired")
                if (first && settings.token.isBlank()) throw java.io.IOException("This server is not set up yet: open the code from ./master.sh token first")
                val nonce = n.getString("nonce")
                val deviceId = java.util.UUID.randomUUID().toString()
                val pinSeen = client.pinSeen()
                val (deviceKey, presenceKey) = keys
                val payload = "$deviceId|${b64(sha256(deviceKey))}|${b64(sha256(presenceKey))}|$pinSeen"
                val challenge = sha256("krewd-u2f-v1\npair\n$nonce\n$payload".toByteArray())
                val register = sha256("krewd-u2f-v1\nregister\n$nonce\n".toByteArray())
                val body = JSONObject().put("nonce", nonce).put("device_id", deviceId).put("name", name.ifBlank { android.os.Build.MODEL })
                    .put("pin_seen", pinSeen).put("device_key", b64(deviceKey)).put("presence_key", b64(presenceKey))
                    .put("device_sig", b64(Keys.signWithDevice("krewd-device-v1\npair\n$nonce\n$pinSeen".toByteArray())))
                when {
                    first && method == OwnerMethod.PASSKEY -> {
                        // Set up with a passkey: make it, then sign this phone's keys with it (two fingerprint prompts).
                        val rp = n.optStringOrNull("passkey_rp_id") ?: throw java.io.IOException("Passkeys are not set up on the Krewd server")
                        _state.update { it.copy(securityStep = "Save a passkey for Krewd") }
                        val reg = Passkeys.create(front, rp, register, emptyList())
                        body.put("passkey_registration", reg).put("key_name", "Passkey (${android.os.Build.MODEL})")
                        body.merge(ownerSign(front, n, "pair", payload, method, allowPasskeys = listOf(reg.getString("credential_id"))))
                    }
                    first -> {
                        val tap = U2f.tap(front, unb64(n.getString("app_param")), challenge, registerChallenge = register, step = keyStep)
                        body.put("assertion", b64(tap.assertion)).put("registration", b64(tap.registration!!))
                    }
                    else -> body.merge(ownerSign(front, n, "pair", payload, method))
                }
                _state.update { it.copy(securityStep = "Checking with Krewd…") }
                val result = withContext(Dispatchers.IO) { client.pair(body, withToken = first) }
                val capture = client.captured()
                val pin = if (capture != null && !capture.trustedBySystem) capture.pin.orEmpty() else settings.pin
                val paired = settings.copy(token = "", deviceId = result.getString("device_id"), pin = pin)
                prefs.save(paired)
                creds = Credentials.of(paired).also { it.session = result.optStringOrNull("session") }
                _state.update { it.copy(settings = prefs.load(), message = if (method == OwnerMethod.PASSKEY) "This phone is set up with your passkey" else "This phone is set up with your security key") }
                connect()
                null
            } catch (e: kotlinx.coroutines.CancellationException) {
                "Cancelled"
            } catch (e: Exception) {
                e.message ?: "Pairing failed"
            } finally {
                _state.update { it.copy(securityStep = null) }
            }
            done(error)
        }
    }

    /** Removes a phone from Krewd, with a tap of the owner's security key. */
    fun removeDevice(device: Device, method: OwnerMethod, done: (String?) -> Unit) {
        val front = activity?.get() ?: return done("Open Krewd first")
        val client = api ?: return done("Not connected to Krewd")
        keyJob = scope.launch {
            val error = try {
                val n = withContext(Dispatchers.IO) { client.nonce("revoke") }
                val body = JSONObject().put("nonce", n.getString("nonce")).merge(ownerSign(front, n, "revoke", device.id, method))
                withContext(Dispatchers.IO) { client.revokeDevice(device.id, body) }
                if (device.current) forgetThisPhone() else loadSecurity()
                null
            } catch (e: kotlinx.coroutines.CancellationException) {
                "Cancelled"
            } catch (e: Exception) {
                e.message ?: "Could not remove the phone"
            } finally {
                _state.update { it.copy(securityStep = null) }
            }
            done(error)
        }
    }

    /** This phone was removed from Krewd: drop its keys, so it can be set up again. */
    private fun forgetThisPhone() {
        Keys.delete()
        prefs.save(prefs.load().copy(deviceId = ""))
        _state.update { it.copy(settings = prefs.load(), devices = null, ownerKeys = null) }
        connect()
    }

    /**
     * Adds an owner key: a passkey made on this phone ([kind] "passkey") or a security key ("u2f", when there is
     * none), confirmed with a key already registered ([confirm]).
     */
    fun addOwnerKey(kind: String, confirm: OwnerMethod, done: (String?) -> Unit) {
        val front = activity?.get() ?: return done("Open Krewd first")
        val client = api ?: return done("Not connected to Krewd")
        keyJob = scope.launch {
            val error = try {
                _state.update { it.copy(securityStep = "Getting ready…") }
                val n = withContext(Dispatchers.IO) { client.nonce("add-key") }
                val nonce = n.getString("nonce")
                val register = sha256("krewd-u2f-v1\nregister\n$nonce\n".toByteArray())
                val body = JSONObject().put("nonce", nonce).put("kind", kind)
                val blob: ByteArray
                if (kind == "passkey") {
                    val rp = n.optStringOrNull("passkey_rp_id") ?: throw java.io.IOException("Passkeys are not set up on the Krewd server")
                    val existing = n.optJSONArray("keys").objects().map(OwnerKey::from).mapNotNull { it.credentialId }
                    _state.update { it.copy(securityStep = "Save a passkey for Krewd") }
                    val reg = Passkeys.create(front, rp, register, existing)
                    body.put("passkey_registration", reg).put("name", "Passkey (${android.os.Build.MODEL})")
                    blob = unb64(reg.getString("attestation_object"))
                } else {
                    _state.update { it.copy(securityStep = "Now the new security key") }
                    val tap = U2f.tap(front, unb64(n.getString("app_param")), register, registerChallenge = register, step = keyStep)
                    blob = tap.registration!!
                    body.put("registration", b64(blob)).put("name", "Security key")
                }
                // An owner key already registered signs for the new one (its exact registration).
                body.merge(ownerSign(front, n, "add-key", b64(sha256(blob)), confirm))
                _state.update { it.copy(securityStep = "Checking with Krewd…") }
                withContext(Dispatchers.IO) { client.addOwnerKey(body) }
                _state.update { it.copy(message = if (kind == "passkey") "Passkey added: it can now set up and remove phones" else "Security key added") }
                loadSecurity()
                null
            } catch (e: kotlinx.coroutines.CancellationException) {
                "Cancelled"
            } catch (e: Exception) {
                e.message ?: "Could not add the key"
            } finally {
                _state.update { it.copy(securityStep = null) }
            }
            done(error)
        }
    }

    /** Removes an owner key, confirmed with an owner key (it may be the same one); phones added with it go too. */
    fun removeOwnerKey(key: OwnerKey, confirm: OwnerMethod, done: (String?) -> Unit) {
        val front = activity?.get() ?: return done("Open Krewd first")
        val client = api ?: return done("Not connected to Krewd")
        keyJob = scope.launch {
            val error = try {
                val n = withContext(Dispatchers.IO) { client.nonce("remove-key") }
                val body = JSONObject().put("nonce", n.getString("nonce")).merge(ownerSign(front, n, "remove-key", key.id, confirm))
                val result = withContext(Dispatchers.IO) { client.removeOwnerKey(key.id, body) }
                val gone = result.optJSONArray("phones_removed")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                _state.update { it.copy(message = "${key.name} removed" + if (gone.isNotEmpty()) ", with ${gone.size} phone(s) added with it" else "") }
                if (prefs.load().deviceId in gone) forgetThisPhone() else loadSecurity()
                null
            } catch (e: kotlinx.coroutines.CancellationException) {
                "Cancelled"
            } catch (e: Exception) {
                e.message ?: "Could not remove the key"
            } finally {
                _state.update { it.copy(securityStep = null) }
            }
            done(error)
        }
    }

    // the app's own settings on the server

    private fun syncPrefs(client: KrewdApi) {
        val server = runCatching { client.serverPrefs() }.getOrNull() ?: return
        if (server.length() == 0) {
            prefs.syncedValues().forEach { (k, v) -> runCatching { client.setPref(k, v) } }  // first time: this phone's picks
        } else {
            prefs.apply(server)
            _state.update { it.copy(highQuality = prefs.highQuality(), savedPrompts = prefs.savedPrompts()) }
        }
    }

    // sign-in requests

    /** Sends the owner's values for a sign-in request: fingerprint first, then Krewd types them into the page. */
    fun fillSecret(ask: SecretAsk, values: Map<String, String>, done: (String?) -> Unit) {
        val client = api ?: return done("Not connected to Krewd")
        scope.launch {
            val error = try {
                if (!_state.value.settings.paired) throw java.io.IOException("Set this phone up with your security key first (Settings > Security)")
                if (!client.secure) throw java.io.IOException("Not on an encrypted connection")
                val path = "/api/secrets/${ask.id}/fill"
                val text = JSONObject().put("values", JSONObject(values)).toString()
                val header = presence(client, "POST", path, text, "Sign in on ${ask.site}", ask.reason.ifBlank { "Krewd types it into the page" })
                val result = SecretAsk.from(JSONObject(withContext(Dispatchers.IO) {
                    client.send("POST", path, text, mapOf("X-Krewd-Presence" to header))
                }))
                _state.update { s -> s.copy(secrets = s.secrets.filterNot { it.id == ask.id }) }
                if (result.status == "filled") null else (result.error ?: "Not filled: ${result.status}")
            } catch (e: PresenceCancelled) {
                "Cancelled"
            } catch (e: Exception) {
                e.message ?: "Could not send it"
            }
            done(error)
        }
    }

    fun cancelSecret(ask: SecretAsk) = act { client ->
        client.send("POST", "/api/secrets/${ask.id}/cancel", "{}", emptyMap(), 20_000)
        _state.update { s -> s.copy(secrets = s.secrets.filterNot { it.id == ask.id }) }
    }

    private fun act(block: (KrewdApi) -> Unit) {
        val client = api
        if (client == null) {
            _state.update { it.copy(message = "Not connected to Krewd") }
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                block(client)
            } catch (e: Exception) {
                _state.update { it.copy(message = e.message ?: "Request failed") }
            }
        }
    }

    private fun launchMain(block: () -> Unit) {
        scope.launch(Dispatchers.Main) { block() }
    }

    // Last in the class: everything above must be initialised before the loops start.
    init {
        startInputWorker()
        connect()
    }
}

/** What the live-activity notification shows for one running task. */
data class LiveTask(
    val sessionId: String,
    var title: String,
    val engine: String,
    var activity: String,
    var since: Long,
    var waiting: Boolean,
    /** "read", "write" or "think": which face the agent makes on the Krewd tab. */
    var kind: String = "think",
    /** Tool calls in the current turn, for the step count. */
    val toolIds: MutableSet<String> = LinkedHashSet(),
) {
    fun copy(): LiveTask = LiveTask(sessionId, title, engine, activity, since, waiting, kind, LinkedHashSet(toolIds))
}

/** What a running task is doing, for the UI: [kind] is read, write, think or needs. */
data class LiveInfo(val activity: String, val since: Long, val kind: String, val steps: Int)

/** How the owner confirms: the FIDO security key or a phone passkey. */
enum class OwnerMethod { SECURITY_KEY, PASSKEY }

private fun sha256(data: ByteArray): ByteArray = java.security.MessageDigest.getInstance("SHA-256").digest(data)

private fun b64(data: ByteArray): String = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)

private fun unb64(text: String): ByteArray = android.util.Base64.decode(text, android.util.Base64.DEFAULT)

/** What to do when the server does not take this phone: shown with the reason, in Settings. */
const val RECONNECT_HINT = "On the server, run ./master.sh token, copy its krewd://connect link, then tap Paste connect code in Settings > Server."

/**
 * The krewd://connect link in pasted text, or null. A terminal copy can break the link over lines, so line breaks
 * inside it are dropped; a space or any character a link cannot hold (the next prompt, a quote) ends it.
 */
internal fun findConnectLink(text: String): String? {
    val start = text.indexOf("krewd://connect")
    if (start < 0) return null
    val link = StringBuilder()
    for (c in text.substring(start)) {
        when {
            c == '\n' || c == '\r' -> continue
            c.isLetterOrDigit() && c.code < 128 || c in "%&=?:/._~+-" -> link.append(c)
            else -> break
        }
    }
    return link.toString().takeIf { "?" in it }
}
