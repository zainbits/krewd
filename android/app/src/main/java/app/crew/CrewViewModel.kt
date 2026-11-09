package app.crew

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.crew.constants.Timing
import app.crew.data.ApiException
import app.crew.data.BrowserFrame
import app.crew.data.BrowserTab
import app.crew.data.BrowserTabs
import app.crew.data.FrameStream
import app.crew.data.browserInput
import app.crew.data.browserTabs
import app.crew.data.Approval
import app.crew.data.ChatItem
import app.crew.data.ConfigOption
import app.crew.data.PendingFile
import app.crew.data.ConnectionSettings
import app.crew.data.CrewApi
import app.crew.data.EngineInfo
import app.crew.data.Event
import app.crew.data.Prefs
import app.crew.data.SessionInfo
import app.crew.data.Timeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONObject

sealed interface Connection {
    data object NotSetUp : Connection
    data object Connecting : Connection
    data class Online(val route: String, val version: String) : Connection
    data class Offline(val reason: String) : Connection
}

data class UiState(
    val settings: ConnectionSettings,
    val connection: Connection = Connection.Connecting,
    val engines: List<EngineInfo> = emptyList(),
    val sessions: List<SessionInfo> = emptyList(),
    val approvals: List<Approval> = emptyList(),
    val openSessionId: String? = null,
    val timeline: List<ChatItem> = emptyList(),
    val timelineLoading: Boolean = false,
    val message: String? = null,
    val browser: BrowserTabs? = null,
    val openTabId: String? = null,
    val streamError: String? = null,
    /** Files picked for the next message, per draft: "new" for the new-task card, else a session id. */
    val drafts: Map<String, List<PendingFile>> = emptyMap(),
) {
    fun draft(key: String): List<PendingFile> = drafts[key].orEmpty()
    val openTab: BrowserTab? get() = browser?.tabs?.firstOrNull { it.id == openTabId }
    val openSession: SessionInfo? get() = sessions.firstOrNull { it.id == openSessionId }
}

/**
 * One long-poll loop keeps sessions, approvals and the open task's chat in sync with the server.
 * Every change on the server is a durable event, so reconnecting just continues from the last seq.
 */
const val NEW_DRAFT = "new"

class CrewViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = Prefs(application)
    private val notifier = Notifier(application)
    private val _state = MutableStateFlow(UiState(settings = prefs.load()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    @Volatile private var api: CrewApi? = null
    private var loop: Job? = null
    private var timeline: Timeline? = null
    private val pendingLive = ArrayList<Event>()
    @Volatile var inForeground = true

    // The agents' browser. Frames live in their own flow so a new picture does not re-render everything.
    private val _frame = MutableStateFlow<BrowserFrame?>(null)
    val frame: StateFlow<BrowserFrame?> = _frame.asStateFlow()
    private var streamJob: Job? = null
    @Volatile private var stream: FrameStream? = null

    init {
        connect()
    }

    fun connect() {
        loop?.cancel()
        val settings = _state.value.settings
        if (!settings.configured) {
            _state.update { it.copy(connection = Connection.NotSetUp) }
            return
        }
        loop = viewModelScope.launch(Dispatchers.IO) { runLoop(settings) }
    }

    fun saveSettings(settings: ConnectionSettings) {
        prefs.save(settings)
        _state.update { it.copy(settings = prefs.load()) }
        connect()
    }

    private suspend fun runLoop(settings: ConnectionSettings) {
        var attempt = 0
        _state.update { it.copy(connection = Connection.Connecting) }
        while (true) {
            try {
                val client = CrewApi.resolve(settings)
                val version = client.health().optString("version")
                val server = client.state()
                api = client
                val sessions = client.sessions()
                val approvals = client.approvals()
                _state.update {
                    it.copy(
                        connection = Connection.Online(client.route.label, version),
                        engines = server.engines,
                        sessions = sessions,
                        approvals = approvals,
                    )
                }
                _state.value.openSessionId?.let { loadTimeline(client, it) }
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
                    else -> e.message ?: e.javaClass.simpleName
                }
                _state.update { it.copy(connection = Connection.Offline(reason)) }
                if (e is ApiException && e.code == 401) return
                delay(Timing.RETRY_DELAYS_MS[minOf(attempt, Timing.RETRY_DELAYS_MS.lastIndex)])
                attempt++
            }
        }
    }

    private fun handle(event: Event) {
        when (event.kind) {
            "session" -> {
                val session = SessionInfo.from(event.payload)
                _state.update { s -> s.copy(sessions = upsert(s.sessions, session)) }
            }
            "approval" -> {
                val approval = Approval.from(event.payload)
                _state.update { s ->
                    val rest = s.approvals.filterNot { it.id == approval.id }
                    s.copy(approvals = if (approval.pending) rest + approval else rest)
                }
                if (approval.pending && !viewing(event.sessionId)) {
                    notifier.approval(approval, titleOf(event.sessionId))
                }
            }
            "turn_end" -> if (!viewing(event.sessionId)) notifier.finished(event.sessionId, titleOf(event.sessionId))
        }
        if (event.sessionId == _state.value.openSessionId) {
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
            if (snapshot != null) _state.update { it.copy(timeline = snapshot) }
        }
    }

    private fun viewing(sessionId: String) = inForeground && _state.value.openSessionId == sessionId

    private fun titleOf(sessionId: String) = _state.value.sessions.firstOrNull { it.id == sessionId }?.title ?: "Crew"

    private fun upsert(list: List<SessionInfo>, session: SessionInfo): List<SessionInfo> {
        val rest = list.filterNot { it.id == session.id }
        val merged = if (session.archived) rest else rest + session
        return merged.sortedWith(compareByDescending<SessionInfo> { it.pinned }.thenByDescending { it.updatedAt })
    }

    // the open task

    fun openSession(id: String) {
        synchronized(pendingLive) {
            timeline = null
            pendingLive.clear()
        }
        _state.update { it.copy(openSessionId = id, timeline = emptyList(), timelineLoading = true) }
        notifier.clear(id)
        val client = api ?: return
        viewModelScope.launch(Dispatchers.IO) { loadTimeline(client, id) }
    }

    fun closeSession() {
        synchronized(pendingLive) { timeline = null }
        _state.update { it.copy(openSessionId = null, timeline = emptyList()) }
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
        } catch (e: Exception) {
            _state.update { it.copy(timelineLoading = false, message = "Could not load the task: ${e.message}") }
        }
    }

    // actions

    fun newTask(text: String, autoApprove: Boolean, engine: String?, config: Map<String, String>) = act { client ->
        val files = readyUploads(NEW_DRAFT)
        val session = client.createSession(text, autoApprove, engine, config, files)
        clearDraft(NEW_DRAFT)
        _state.update { it.copy(sessions = upsert(it.sessions, session)) }
        launchMain { openSession(session.id) }
    }

    fun send(text: String) {
        val id = _state.value.openSessionId ?: return
        act { client ->
            client.send(id, text, readyUploads(id))
            clearDraft(id)
        }
    }

    // attachments: each picked file uploads at once; the message carries the upload ids

    private var nextLocalId = 1L

    fun attach(draft: String, uris: List<android.net.Uri>) {
        val client = api ?: run { _state.update { it.copy(message = "Not connected to Crew") }; return }
        val resolver = getApplication<Application>().contentResolver
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
            viewModelScope.launch(Dispatchers.IO) {
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

    fun setAutoApprove(id: String, on: Boolean) = act { it.updateSession(id, JSONObject().put("auto_approve", on)) }

    fun setMode(id: String, mode: String) = act { it.updateSession(id, JSONObject().put("mode", mode)) }

    fun setConfig(id: String, configId: String, value: String) = act { client ->
        val session = client.updateSession(id, JSONObject().put("config", JSONObject().put(configId, value)))
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

    fun archive(id: String) = act { client ->
        client.updateSession(id, JSONObject().put("archived", true))
        _state.update { s -> s.copy(sessions = s.sessions.filterNot { it.id == id }) }
        launchMain { if (_state.value.openSessionId == id) closeSession() }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    // the agents' browser

    fun refreshTabs() = act { client ->
        val tabs = client.browserTabs()
        _state.update { it.copy(browser = tabs) }
    }

    /** Opens a tab's live view; with no id, the tab the browser shows in front. */
    fun openTab(id: String? = null) {
        val client = api ?: run { _state.update { it.copy(message = "Not connected to Crew") }; return }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val tabs = client.browserTabs()
                val target = id ?: tabs.tabs.firstOrNull()?.id
                if (target == null) {
                    _state.update { it.copy(browser = tabs, message = tabs.reason ?: "The browser has no open tabs") }
                    return@launch
                }
                _state.update { it.copy(browser = tabs, openTabId = target, streamError = null) }
                startStream(client, target)
            } catch (e: Exception) {
                _state.update { it.copy(message = e.message ?: "Could not open the browser") }
            }
        }
    }

    fun closeTab() {
        _state.update { it.copy(openTabId = null, streamError = null) }
        stopStream()
        _frame.value = null
    }

    fun tabInput(event: JSONObject) {
        val id = _state.value.openTabId ?: return
        act { it.browserInput(id, event) }
    }

    private fun startStream(client: CrewApi, tabId: String) {
        stopStream()
        _frame.value = null
        streamJob = viewModelScope.launch(Dispatchers.IO) {
            var failures = 0
            while (_state.value.openTabId == tabId) {
                try {
                    val s = client.openBrowserStream(tabId)
                    stream = s
                    s.read { frame ->
                        failures = 0
                        _frame.value = frame
                        if (_state.value.streamError != null) _state.update { it.copy(streamError = null) }
                    }
                } catch (e: Exception) {
                    if (_state.value.openTabId != tabId) break
                    failures++
                    _state.update { it.copy(streamError = e.message ?: "The live view stopped") }
                    if (e is ApiException && e.code == 404) break
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
        if (s != null) viewModelScope.launch(Dispatchers.IO) { runCatching { s.close() } }
    }

    private fun act(block: (CrewApi) -> Unit) {
        val client = api
        if (client == null) {
            _state.update { it.copy(message = "Not connected to Crew") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block(client)
            } catch (e: Exception) {
                _state.update { it.copy(message = e.message ?: "Request failed") }
            }
        }
    }

    private fun launchMain(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.Main) { block() }
    }
}
