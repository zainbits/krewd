package app.crew

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.graphics.asImageBitmap
import app.crew.constants.Timing
import app.crew.data.AgentLogin
import app.crew.data.ApiException
import app.crew.data.Approval
import app.crew.data.Attachment
import app.crew.data.BrowserFrame
import app.crew.data.BrowserTab
import app.crew.data.BrowserTabs
import app.crew.data.ChatItem
import app.crew.data.ConfigOption
import app.crew.data.ConnectionSettings
import app.crew.data.CrewApi
import app.crew.data.Discovery
import app.crew.data.EngineDefaults
import app.crew.data.EngineInfo
import app.crew.data.Event
import app.crew.data.FrameStream
import app.crew.data.PendingFile
import app.crew.data.ProfilesState
import app.crew.data.Prefs
import app.crew.data.Routine
import app.crew.data.RoutineRun
import app.crew.data.SchedulePreview
import app.crew.data.SessionInfo
import app.crew.data.Timeline
import app.crew.data.UserItem
import app.crew.data.Verified
import app.crew.data.browserInput
import app.crew.data.browserTabs
import app.crew.data.closeBrowserTab
import app.crew.data.newTab
import app.crew.data.setTabView
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
)

object NavTab {
    const val TASKS = 0
    const val ROUTINES = 1
    const val BROWSER = 2
    const val SETTINGS = 3
}

data class UiState(
    val settings: ConnectionSettings,
    /** A connect link (crew://connect) waiting for the owner's yes: it would replace the server settings. */
    val pendingConnect: ConnectionSettings? = null,
    val connection: Connection = Connection.Connecting,
    val engines: List<EngineInfo> = emptyList(),
    val sessions: List<SessionInfo> = emptyList(),
    val approvals: List<Approval> = emptyList(),
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
    /** What each running task is doing right now, by session id (the Crew tab and the chat's activity line). */
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
const val MAX_SAVED_PROMPTS = 12
/** Width of the tab stills on working cards, in pixels. */
const val SNAPSHOT_WIDTH = 360
const val UNDO_MS = 3_000L

/**
 * The app's state and its one connection to the server. It lives as long as the process (see
 * [CrewApplication]), not the screen, so the long poll and its notifications keep going while the app
 * is in the background and the live-activity service keeps the process alive.
 *
 * Every change on the server is a durable event, so reconnecting just continues from the last seq.
 */
class CrewViewModel(private val app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = Prefs(app)
    private val notifier = Notifier(app)
    private val _state = MutableStateFlow(UiState(settings = prefs.load(), highQuality = prefs.highQuality(), savedPrompts = prefs.savedPrompts()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    @Volatile private var api: CrewApi? = null
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

    /** A crew://connect link (from `./master.sh token`): asks before it replaces the server settings. */
    fun openConnectLink(uri: android.net.Uri) {
        if (uri.scheme != "crew" || uri.host != "connect") return
        val addresses = uri.getQueryParameters("a").flatMap(ConnectionSettings::parseAddresses).distinct()
        val token = uri.getQueryParameter("t").orEmpty()
        if (addresses.isEmpty() || token.isBlank()) {
            _state.update { it.copy(message = "That connect link is incomplete") }
            return
        }
        val pin = uri.getQueryParameter("p").orEmpty()
        _state.update { it.copy(pendingConnect = ConnectionSettings(addresses, token, pin), navTab = NavTab.SETTINGS) }
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
    private fun reach(settings: ConnectionSettings): CrewApi = try {
        CrewApi.resolve(settings)
    } catch (e: Exception) {
        if (settings.pin.isBlank()) throw e
        val found = Discovery.find(app, settings.pin).filterNot { it in settings.addresses }
        if (found.isEmpty()) throw e
        val client = runCatching { CrewApi.resolve(settings, found) }.getOrNull()?.takeIf { it.verified == Verified.Pin } ?: throw e
        prefs.save(settings.copy(addresses = listOf(client.baseUrl) + settings.addresses))
        _state.update { it.copy(settings = prefs.load()) }
        client
    }

    /**
     * Over an address an authority vouches for (Tailscale, a domain), learn the server's own certificate pin,
     * so the home network address can be checked too. Never learned over a pinned or plain connection.
     */
    private fun learnPin(client: CrewApi) {
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
                val routines = runCatching { client.routines() }.getOrDefault(_state.value.routines)
                val wasRestarting = _state.value.restarting && sawDown
                _state.update {
                    it.copy(
                        connection = Connection.Online(client.label, version, client.secure),
                        engines = server.engines,
                        sessions = sessions,
                        approvals = approvals,
                        routines = routines,
                        restarting = if (wasRestarting) false else it.restarting,
                        message = if (wasRestarting) "Crew is back" else it.message,
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
                    e is ApiException && e.code == 401 -> "The token was not accepted"
                    e is javax.net.ssl.SSLException || e.cause is java.security.cert.CertificateException ->
                        "The server's certificate is not the one this app trusts. On the server, run ./master.sh token and open its code here"
                    else -> e.message ?: e.javaClass.simpleName
                }
                if (_state.value.restarting) sawDown = true
                _state.update { it.copy(connection = Connection.Offline(reason)) }
                if (e is ApiException && e.code == 401) return
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
        return s.engine(id)?.name ?: id ?: "Crew"
    }

    private fun viewing(sessionId: String) = inForeground && _state.value.openSessionId == sessionId

    private fun titleOf(sessionId: String) = _state.value.sessions.firstOrNull { it.id == sessionId }?.title ?: "Crew"

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

    private fun loadRoutine(client: CrewApi, id: String) {
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

    private fun loadTimeline(client: CrewApi, id: String) {
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

    fun newTask(text: String, autoApprove: Boolean, engine: String?, config: Map<String, String>) = act { client ->
        val files = readyUploads(NEW_DRAFT)
        val engineId = engine ?: _state.value.engines.firstOrNull { it.isDefault }?.id
        val session = client.createSession(text, autoApprove, engine, config, files)
        engineId?.let { id -> prefs.updateEngine(id) { it.copy(autoApprove = autoApprove, config = it.config + config) } }
        clearDraft(NEW_DRAFT)
        _state.update { it.copy(sessions = upsert(it.sessions, session)) }
        launchMain { openSession(session.id) }
    }

    /** What a new task on [engineId] starts with: the owner's last picks for that agent. */
    fun engineDefaults(engineId: String?): EngineDefaults = engineId?.let(prefs::engine) ?: EngineDefaults()

    // The new-task card saves each pick at once, so leaving the screen or switching agents loses nothing.

    fun lastEngine(): String? = prefs.lastEngine()

    fun setNewTaskEngine(id: String) = prefs.setLastEngine(id)

    fun rememberPick(engineId: String, configId: String, value: String) =
        prefs.updateEngine(engineId) { it.copy(config = it.config + (configId to value)) }

    fun rememberAutoApprove(engineId: String, on: Boolean) = prefs.updateEngine(engineId) { it.copy(autoApprove = on) }

    fun setNewTaskText(text: String) = _state.update { it.copy(newTaskText = text) }

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

    /** A small still of a browser tab for the Crew tab's working cards; null when the server has none. */
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
        )
        clearDraft(id)
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
                client.send(message.sessionId, message.text, message.uploads, message.tabs, message.delivery, message.clientId)
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
        )
    }

    // attachments: each picked file uploads at once; the message carries the upload ids

    private var nextLocalId = 1L

    fun attach(draft: String, uris: List<android.net.Uri>) {
        val client = api ?: run { _state.update { it.copy(message = "Not connected to Crew") }; return }
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
            updateDraft(draft) { it + PendingFile(localId, name, size) }
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
    fun engineConfig(engineId: String?): List<ConfigOption> {
        val s = _state.value
        val engine = s.engines.firstOrNull { it.id == engineId } ?: s.engines.firstOrNull { it.isDefault }
        return engine?.config?.takeIf { it.isNotEmpty() }
            ?: s.sessions.firstOrNull { it.engine == engine?.id && it.config.isNotEmpty() }?.config
            ?: emptyList()
    }

    /** Re-read engines (their options appear once the server has probed a new engine). */
    fun refreshEngines() = act { client ->
        val engines = client.state().engines
        _state.update { it.copy(engines = engines) }
    }

    // profiles and sign-ins (Settings)

    /** Profiles, presets, sign-ins and engines, read again together (they change one another). */
    fun loadProfiles() = act { client -> reloadProfiles(client) }

    private fun reloadProfiles(client: CrewApi) {
        val profiles = client.profiles()
        val logins = client.logins()
        val engines = client.state().engines
        _state.update { it.copy(profiles = profiles, logins = logins, engines = engines) }
    }

    /** Create (id null) or change a profile; [done] gets null on success, else what went wrong. */
    fun saveProfile(id: String?, fields: JSONObject, key: String?, done: (String?) -> Unit) = withResult(done) { client ->
        val target = if (key.isNullOrBlank()) client else CrewApi.forKeys(client, _state.value.settings)
        if (!key.isNullOrBlank()) fields.put("key", key.trim())
        target.saveProfile(id, fields)
        reloadProfiles(client)
    }

    fun deleteProfile(id: String, done: (String?) -> Unit) = withResult(done) { client ->
        client.deleteProfile(id)
        reloadProfiles(client)
    }

    fun refreshLogins() = act { client -> _state.update { it.copy(logins = client.logins()) } }

    fun startLogin(engine: String, done: (String?) -> Unit) = withResult(done) { client ->
        client.startLogin(engine)
        _state.update { it.copy(logins = client.logins()) }
    }

    fun submitLoginCode(engine: String, code: String, done: (String?) -> Unit) = withResult(done) { client ->
        client.submitLoginCode(engine, code.trim())
        reloadProfiles(client)
    }

    fun cancelLogin(engine: String) = act { client ->
        client.cancelLogin(engine)
        _state.update { it.copy(logins = client.logins()) }
    }

    fun logout(engine: String) = act { client ->
        client.logout(engine)
        reloadProfiles(client)
    }

    fun setAgentProfile(engine: String, profile: String?) = act { client ->
        client.setAgentProfile(engine, profile)
        reloadProfiles(client)
    }

    /** Runs [block] off the main thread and reports null (it worked) or the error message to [done], on the main thread. */
    private fun withResult(done: (String?) -> Unit, block: (CrewApi) -> Unit) {
        val client = api ?: return done("Not connected to Crew")
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
        act { it.deleteSession(session.id) }
    }

    // Tool-result images, decoded at most about phone width; a handful stay in memory.
    private val toolImages = object : android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: androidx.compose.ui.graphics.ImageBitmap) = value.width * value.height * 4
    }

    /** An image from a tool result, or null when not connected. Throws if the server cannot give it. */
    suspend fun toolImage(name: String): androidx.compose.ui.graphics.ImageBitmap? {
        toolImages.get(name)?.let { return it }
        val client = api ?: return null
        val bitmap = withContext(Dispatchers.IO) {
            val bytes = client.toolImage(name)
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 1600) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap() ?: error("Not an image")
        }
        toolImages.put(name, bitmap)
        return bitmap
    }

    private val _downloading = MutableStateFlow<Set<String>>(emptySet())
    /** Paths of handed-back files being downloaded right now. */
    val downloading: StateFlow<Set<String>> = _downloading.asStateFlow()
    private val saved = HashMap<String, Uri>() // "path|size" -> its copy in Downloads

    /** Saves a file the agent handed back to Downloads/Crew (once), then opens it. */
    fun openSharedFile(file: Attachment) {
        val client = api ?: run { toast("Not connected to Crew"); return }
        if (file.path in _downloading.value) return
        val key = "${file.path}|${file.size}"
        saved[key]?.takeIf { exists(it) }?.let { if (view(it, file.mime)) return }
        _downloading.update { it + file.path }
        scope.launch {
            try {
                val uri = withContext(Dispatchers.IO) { saveToDownloads(client, file) }
                saved[key] = uri
                if (!view(uri, file.mime)) toast("Saved ${file.name} to Downloads/Crew")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(e.message ?: "Download failed")
            } finally {
                _downloading.update { it - file.path }
            }
        }
    }

    private fun saveToDownloads(client: CrewApi, file: Attachment): Uri {
        val resolver = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, file.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Crew")
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
            if (_state.value.restarting) _state.update { it.copy(restarting = false, message = "Crew did not come back yet; still retrying") }
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

    private fun startStream(client: CrewApi, tabId: String, keepFrame: Boolean = false) {
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

    private fun act(block: (CrewApi) -> Unit) {
        val client = api
        if (client == null) {
            _state.update { it.copy(message = "Not connected to Crew") }
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
    /** "read", "write" or "think": which face the agent makes on the Crew tab. */
    var kind: String = "think",
    /** Tool calls in the current turn, for the step count. */
    val toolIds: MutableSet<String> = LinkedHashSet(),
) {
    fun copy(): LiveTask = LiveTask(sessionId, title, engine, activity, since, waiting, kind, LinkedHashSet(toolIds))
}

/** What a running task is doing, for the UI: [kind] is read, write, think or needs. */
data class LiveInfo(val activity: String, val since: Long, val kind: String, val steps: Int)
