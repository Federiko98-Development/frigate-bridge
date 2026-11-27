package it.federicoraponi.watchdeck.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.federicoraponi.watchdeck.camera.Camera
import it.federicoraponi.watchdeck.camera.GroupChoice
import it.federicoraponi.watchdeck.camera.groupChoices
import it.federicoraponi.watchdeck.camera.showing
import it.federicoraponi.watchdeck.frigate.FrigateError
import it.federicoraponi.watchdeck.network.NetworkMonitor
import it.federicoraponi.watchdeck.network.connections
import it.federicoraponi.watchdeck.recordings.ReviewItem
import it.federicoraponi.watchdeck.recordings.ReviewSeverity
import it.federicoraponi.watchdeck.recordings.momentOfDay
import it.federicoraponi.watchdeck.recordings.recordingDay
import it.federicoraponi.watchdeck.server.ConnectionState
import it.federicoraponi.watchdeck.server.ExportedFile
import it.federicoraponi.watchdeck.server.RegistryState
import it.federicoraponi.watchdeck.server.ServerRegistry
import it.federicoraponi.watchdeck.server.ServerSession
import it.federicoraponi.watchdeck.settings.AppSettings
import it.federicoraponi.watchdeck.settings.SettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

data class EventsFilter(
    val severity: ReviewSeverity = ReviewSeverity.Alert,
    /** The group of cameras shown, `null` for all of them. */
    val group: String? = null,
    /** `null` for everything that can be seen. */
    val label: String? = null,
    /** The day shown, as `yyyy-mm-dd`; `null` follows today. */
    val date: String? = null,
    /** Minutes after midnight from which the day is shown, or `null` for the whole day. */
    val fromMinute: Int? = null,
)

/** The sharing of the chosen moments. */
sealed interface SelectionShare {
    data object None : SelectionShare

    /** The server is cutting the video of each moment, one at a time. */
    data class Preparing(
        val done: Int,
        val total: Int,
    ) : SelectionShare

    /** The videos can be downloaded. [missing] of the chosen moments have none. */
    data class Ready(
        val files: List<ExportedFile>,
        val missing: Int,
    ) : SelectionShare

    data class Failed(
        val failure: VideoFailure,
    ) : SelectionShare
}

/** How many moments can be shared at once: each one is a video the server has to cut. */
const val MAXIMUM_SHARED_MOMENTS = 10

data class EventsState(
    val server: ServerSummary? = null,
    val servers: List<ServerSummary> = emptyList(),
    val cameras: List<Camera> = emptyList(),
    /** "All cameras" and every group, each with the cameras switched on and off in it. */
    val choices: List<GroupChoice> = emptyList(),
    /** The phone's current day, as `yyyy-mm-dd`. */
    val today: String = "",
    /** The labels seen so far, to filter by. */
    val labels: List<String> = emptyList(),
    val filter: EventsFilter = EventsFilter(),
    /** Newest first. */
    val items: List<ReviewItem> = emptyList(),
    /** The first page of the current filter has not arrived yet. */
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val problem: ConnectionProblem? = null,
    /** There is no active server: go back to the list. */
    val hasNoServer: Boolean = false,
    /** The phone has no network. */
    val isOffline: Boolean = false,
    /** The user is an administrator of the server. */
    val canManage: Boolean = false,
    /** The ids of the chosen moments; while there are any, the list is being selected from. */
    val selected: Set<String> = emptySet(),
    val isDeleting: Boolean = false,
    val deleteFailure: AdminFailure? = null,
    val share: SelectionShare = SelectionShare.None,
) {
    val isSelecting: Boolean get() = selected.isNotEmpty()

    /**
     * The chosen moments that can be deleted: one still going on has no end yet, and deleting recordings up to an
     * unknown time is not something to try.
     */
    val deletable: List<ReviewItem> get() = items.filter { it.id in selected && it.end != null }

    val canShareSelection: Boolean get() = selected.size in 1..MAXIMUM_SHARED_MOMENTS

    /** The day on screen, as `yyyy-mm-dd`. */
    val date: String get() = filter.date ?: today
}

/** The moments Frigate marked for review on the active server. */
class EventsViewModel(
    private val registry: ServerRegistry,
    private val settingsStore: SettingsStore,
    private val network: NetworkMonitor,
) : ViewModel() {
    private val zoneId = TimeZone.currentSystemDefault().id

    // The cameras switched off in each group, as saved in the settings.
    private var hiddenCameras: Map<String, Set<String>> = emptyMap()
    private val mutableState = MutableStateFlow(EventsState(today = today()))
    val state: StateFlow<EventsState> = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private val thumbnails = mutableMapOf<String, ByteArray>()
    private var shareJob: Job? = null
    private var shareSession: ServerSession? = null

    // The files the server has cut for the sharing in progress.
    private val sharedExports = mutableListOf<String>()

    init {
        viewModelScope.launch {
            registry.load()
            val server = registry.state.map { it?.let(::activeServer) }.distinctUntilChanged()
            val hidden = settingsStore.settings.map { it.hiddenCameras }.distinctUntilChanged()
            var shown: ActiveServer? = null
            combine(server, hidden) { active, off -> active to off }
                .collectLatest { (active, off) ->
                    // A choice made here is already on screen when the settings report it.
                    val changed = active != shown || off != hiddenCameras
                    hiddenCameras = off
                    if (active != null && changed) {
                        shown = active
                        show(active)
                    }
                }
        }
        viewModelScope.launch {
            network.status.collect { status -> mutableState.update { it.copy(isOffline = !status.isOnline) } }
        }
        viewModelScope.launch {
            // The first connection is the one the list is loaded on anyway.
            network.status.connections().drop(1).collect {
                if (mutableState.value.problem != null) load(first = true)
            }
        }
    }

    fun setSeverity(severity: ReviewSeverity) = applyFilter { it.copy(severity = severity) }

    fun setGroup(name: String?) = applyFilter { it.copy(group = name) }

    /** Switches on, in a group, only the cameras named in [shown], and shows that group. */
    fun showCameras(
        group: String?,
        shown: Set<String>,
    ) {
        val serverId = mutableState.value.server?.id ?: return
        val choice = mutableState.value.choices.firstOrNull { it.name == group } ?: return
        val changed = AppSettings(hiddenCameras = hiddenCameras).showing(serverId, choice, shown).hiddenCameras
        if (changed == hiddenCameras) {
            setGroup(group)
            return
        }
        hiddenCameras = changed
        reload(mutableState.value.filter.copy(group = group))
        viewModelScope.launch { settingsStore.update { it.showing(serverId, choice, shown) } }
    }

    /** Shows the day [date], from [fromMinute] minutes after midnight or whole. */
    fun setMoment(
        date: String,
        fromMinute: Int?,
    ) = applyFilter { it.copy(date = date.takeUnless { day -> day == today() }, fromMinute = fromMinute) }

    /** Goes back to the whole of today. */
    fun showToday() = applyFilter { it.copy(date = null, fromMinute = null) }

    fun setLabel(label: String?) = applyFilter { it.copy(label = label) }

    fun refresh() {
        if (mutableState.value.isRefreshing) return
        mutableState.update { it.copy(isRefreshing = true) }
        if (mutableState.value.isOffline) {
            viewModelScope.launch {
                visibleRefresh {}
                mutableState.update { it.copy(isRefreshing = false) }
            }
        } else {
            load(first = true)
        }
    }

    /** Reads the older moments that follow the ones shown. */
    fun loadMore() {
        val current = mutableState.value
        if (!current.hasMore || current.isLoadingMore || current.isLoading) return
        mutableState.update { it.copy(isLoadingMore = true) }
        load(first = false)
    }

    fun markViewed(item: ReviewItem) = markViewed(listOf(item))

    /** Marks everything on screen as viewed. */
    fun markAllViewed() = markViewed(mutableState.value.items)

    /** Adds a moment to the chosen ones, or takes it away. */
    fun toggleSelection(item: ReviewItem) {
        if (isActing()) return
        mutableState.update {
            val selected = if (item.id in it.selected) it.selected - item.id else it.selected + item.id
            it.copy(selected = selected, deleteFailure = null)
        }
    }

    /** Chooses every moment on screen. */
    fun selectAll() {
        if (isActing()) return
        mutableState.update { it.copy(selected = it.items.map { item -> item.id }.toSet()) }
    }

    fun clearSelection() {
        if (isActing()) return
        mutableState.update { it.copy(selected = emptySet(), deleteFailure = null) }
    }

    fun markSelectedViewed() {
        if (isActing()) return
        val current = mutableState.value
        markViewed(current.items.filter { it.id in current.selected })
        mutableState.update { it.copy(selected = emptySet()) }
    }

    /**
     * Deletes the chosen moments **with the recordings of their intervals**. The screen asks first. For
     * administrators.
     */
    fun deleteSelected() {
        val current = mutableState.value
        if (!current.canManage || isActing()) return
        val moments = current.deletable
        if (moments.isEmpty()) return
        val ids = moments.map { it.id }.toSet()
        mutableState.update { it.copy(isDeleting = true, deleteFailure = null) }
        viewModelScope.launch {
            val session = registry.session(moments.first().serverId) ?: return@launch
            // A list still being read would bring the deleted moments back.
            loadJob?.cancel()
            val failure = adminFailureOf { session.deleteReviews(ids.toList()) }
            mutableState.update {
                if (failure == null) {
                    it.copy(
                        items = it.items.filterNot { item -> item.id in ids },
                        selected = emptySet(),
                        isDeleting = false,
                        isLoading = false,
                        isRefreshing = false,
                        isLoadingMore = false,
                    )
                } else {
                    it.copy(isDeleting = false, deleteFailure = failure)
                }
            }
        }
    }

    fun dismissDeleteFailure() {
        mutableState.update { it.copy(deleteFailure = null) }
    }

    /** Has the server cut the video of each chosen moment, so that they can be handed to another app. */
    fun shareSelected() {
        val current = mutableState.value
        if (!current.canShareSelection || isActing()) return
        val moments = current.items.filter { it.id in current.selected }
        if (moments.isEmpty()) return
        val session = registry.session(moments.first().serverId) ?: return
        mutableState.update { it.copy(share = SelectionShare.Preparing(0, moments.size)) }
        shareSession = session
        shareJob =
            viewModelScope.launch {
                val files = mutableListOf<ExportedFile>()
                var failure: VideoFailure? = null
                moments.forEachIndexed { index, moment ->
                    val range = clipRange(moment)
                    val result =
                        session.cutExport(moment.camera.cameraName, range.first, range.last) { sharedExports += it }
                    when (result) {
                        is ExportState.Ready -> files += result.file
                        is ExportState.Failed -> failure = result.failure
                        else -> Unit
                    }
                    mutableState.update { it.copy(share = SelectionShare.Preparing(index + 1, moments.size)) }
                }
                val missing = moments.size - files.size
                val outcome =
                    if (files.isEmpty()) {
                        SelectionShare.Failed(failure ?: VideoFailure())
                    } else {
                        SelectionShare.Ready(files, missing)
                    }
                if (files.isEmpty()) removeSharedExports()
                mutableState.update { it.copy(share = outcome) }
            }
    }

    /**
     * Ends the sharing: call it when the videos have been downloaded, or to give up. The files the server cut for it
     * are removed, so that they do not pile up there; only an administrator may do so.
     */
    fun endSharing(shared: Boolean) {
        shareJob?.cancel()
        shareJob = null
        removeSharedExports()
        mutableState.update {
            it.copy(share = SelectionShare.None, selected = if (shared) emptySet() else it.selected)
        }
    }

    private fun removeSharedExports() {
        val session = shareSession ?: return
        val ids = sharedExports.toList()
        sharedExports.clear()
        if (ids.isEmpty() || !mutableState.value.canManage) return
        viewModelScope.launch {
            // One at a time: Frigate removes none of a list if one of them is still being cut.
            ids.forEach { id ->
                try {
                    session.deleteExport(id)
                } catch (_: FrigateError) {
                    // It stays on the server, where it can be deleted from the exported clips.
                }
            }
        }
    }

    private fun isActing(): Boolean = mutableState.value.let { it.isDeleting || it.share != SelectionShare.None }

    fun switchTo(serverId: String) {
        viewModelScope.launch { registry.activate(serverId) }
    }

    fun cachedThumbnail(item: ReviewItem): ByteArray? = thumbnails[item.id]

    suspend fun loadThumbnail(item: ReviewItem): ByteArray? =
        thumbnails[item.id]
            ?: try {
                registry.session(item.serverId)?.mediaFile(item.thumbnailPath)?.also { thumbnails[item.id] = it }
            } catch (_: FrigateError) {
                null
            }

    private fun markViewed(items: List<ReviewItem>) {
        val unseen = items.filterNot { it.isViewed }
        if (unseen.isEmpty()) return
        val ids = unseen.map { it.id }.toSet()
        // Shown at once; the server is told in the background.
        mutableState.update { state ->
            state.copy(items = state.items.map { if (it.id in ids) it.copy(isViewed = true) else it })
        }
        viewModelScope.launch {
            try {
                session()?.markReviewed(ids.toList())
            } catch (error: FrigateError) {
                mutableState.update { state ->
                    state.copy(
                        items = state.items.map { if (it.id in ids) it.copy(isViewed = false) else it },
                        problem = error.toProblem(),
                    )
                }
            }
        }
    }

    private data class ActiveServer(
        val summary: ServerSummary,
        val servers: List<ServerSummary>,
        val revision: Int,
    )

    private fun activeServer(current: RegistryState): ActiveServer? {
        val server = current.activeServer
        if (server == null) {
            mutableState.update { it.copy(hasNoServer = true) }
            return null
        }
        val summaries = current.servers.map { ServerSummary(it.id, it.name, it.colorIndex) }
        return ActiveServer(ServerSummary(server.id, server.name, server.colorIndex), summaries, current.revision)
    }

    private fun show(active: ActiveServer) {
        val changed = mutableState.value.server?.id != active.summary.id
        mutableState.update {
            // Filters and lists belong to one server.
            val base = if (changed) EventsState(isOffline = it.isOffline, today = today()) else it
            base.copy(server = active.summary, servers = active.servers, hasNoServer = false)
        }
        load(first = true)
    }

    private fun applyFilter(change: (EventsFilter) -> EventsFilter) {
        val filter = change(mutableState.value.filter)
        if (filter != mutableState.value.filter) reload(filter)
    }

    private fun reload(filter: EventsFilter) {
        mutableState.update {
            // What is chosen belongs to the list on screen.
            it.copy(
                filter = filter,
                items = emptyList(),
                selected = emptySet(),
                isLoading = true,
                hasMore = false,
                problem = null,
            )
        }
        load(first = true)
    }

    @OptIn(ExperimentalTime::class)
    private fun today(): String =
        Clock.System
            .now()
            .toLocalDateTime(TimeZone.of(zoneId))
            .date
            .toString()

    private fun load(first: Boolean) {
        loadJob?.cancel()
        loadJob =
            viewModelScope.launch {
                val session = session() ?: return@launch
                val filter = mutableState.value.filter
                val shown = mutableState.value.items
                val today = today()
                val date = filter.date ?: today
                val day = recordingDay(date, zoneId) ?: return@launch
                val after = filter.fromMinute?.let { momentOfDay(date, it, zoneId) } ?: day.start
                val before = if (first) day.end else shown.lastOrNull()?.start ?: return@launch
                // After a failure, or when the user asks, the server is read again: it may answer elsewhere.
                val current = mutableState.value
                val reread = !current.isOffline && (current.isRefreshing || current.problem != null)
                try {
                    val known = (session.state.value as? ConnectionState.Connected).takeUnless { reread }
                    val connection = known ?: session.refresh()
                    val catalog = (connection as? ConnectionState.Connected)?.snapshot?.catalog
                    val cameras = catalog?.cameras.orEmpty()
                    val choices = catalog?.groupChoices(session.server.id, cameras, hiddenCameras).orEmpty()
                    // A group that no longer exists gives way to all the cameras.
                    val choice = choices.firstOrNull { it.name == filter.group } ?: choices.firstOrNull()
                    val everything = choice == null || (choice.name == null && !choice.isPartial)
                    val names = if (everything) null else choice.shown.map { it.ref.cameraName }
                    // An empty list would ask for every camera.
                    val page =
                        if (names != null && names.isEmpty()) {
                            emptyList()
                        } else {
                            session.reviews(
                                after = after,
                                before = before,
                                cameras = names,
                                labels = filter.label?.let(::listOf),
                                severity = filter.severity,
                                limit = PAGE_SIZE,
                            )
                        }
                    mutableState.update { state ->
                        val items = if (first) page else (shown + page).distinctBy { it.id }
                        state.copy(
                            cameras = cameras,
                            choices = choices,
                            canManage = catalog?.canChangeSettings == true,
                            today = today,
                            filter = state.filter.copy(group = choice?.name),
                            labels = (state.labels + page.flatMap { it.labels }).distinct().sorted(),
                            items = items,
                            // A moment that is no longer listed is no longer chosen.
                            selected = state.selected.filter { id -> items.any { it.id == id } }.toSet(),
                            isLoading = false,
                            isRefreshing = false,
                            isLoadingMore = false,
                            hasMore = page.size >= PAGE_SIZE,
                            problem = null,
                        )
                    }
                } catch (error: FrigateError) {
                    mutableState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            isLoadingMore = false,
                            problem = error.toProblem(),
                        )
                    }
                }
            }
    }

    private fun session(): ServerSession? =
        registry.state.value
            ?.activeServerId
            ?.let { registry.session(it) }
}

private const val PAGE_SIZE = 50
