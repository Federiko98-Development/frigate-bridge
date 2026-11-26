package it.federicoraponi.frigatebridge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.federicoraponi.frigatebridge.camera.Camera
import it.federicoraponi.frigatebridge.frigate.FrigateError
import it.federicoraponi.frigatebridge.network.NetworkMonitor
import it.federicoraponi.frigatebridge.network.connections
import it.federicoraponi.frigatebridge.recordings.ReviewItem
import it.federicoraponi.frigatebridge.recordings.ReviewSeverity
import it.federicoraponi.frigatebridge.server.ConnectionState
import it.federicoraponi.frigatebridge.server.RegistryState
import it.federicoraponi.frigatebridge.server.ServerRegistry
import it.federicoraponi.frigatebridge.server.ServerSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

data class EventsFilter(
    val severity: ReviewSeverity = ReviewSeverity.Alert,
    /** `null` for every camera. */
    val camera: String? = null,
    /** `null` for everything that can be seen. */
    val label: String? = null,
)

data class EventsState(
    val server: ServerSummary? = null,
    val servers: List<ServerSummary> = emptyList(),
    /** The cameras to filter by. */
    val cameras: List<Camera> = emptyList(),
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
)

/** The moments Frigate marked for review on the active server. */
class EventsViewModel(
    private val registry: ServerRegistry,
    private val network: NetworkMonitor,
) : ViewModel() {
    private val mutableState = MutableStateFlow(EventsState())
    val state: StateFlow<EventsState> = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private val thumbnails = mutableMapOf<String, ByteArray>()

    init {
        viewModelScope.launch {
            registry.load()
            registry.state
                .map { it?.let(::activeServer) }
                .distinctUntilChanged()
                .collectLatest { active -> if (active != null) show(active) }
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

    fun setCamera(name: String?) = applyFilter { it.copy(camera = name) }

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
            val base = if (changed) EventsState(isOffline = it.isOffline) else it
            base.copy(server = active.summary, servers = active.servers, hasNoServer = false)
        }
        load(first = true)
    }

    private fun applyFilter(change: (EventsFilter) -> EventsFilter) {
        val filter = change(mutableState.value.filter)
        if (filter == mutableState.value.filter) return
        mutableState.update {
            it.copy(filter = filter, items = emptyList(), isLoading = true, hasMore = false, problem = null)
        }
        load(first = true)
    }

    @OptIn(ExperimentalTime::class)
    private fun load(first: Boolean) {
        loadJob?.cancel()
        loadJob =
            viewModelScope.launch {
                val session = session() ?: return@launch
                val filter = mutableState.value.filter
                val shown = mutableState.value.items
                val before =
                    if (first) Clock.System.now().epochSeconds + 1 else shown.lastOrNull()?.start ?: return@launch
                // After a failure, or when the user asks, the server is read again: it may answer elsewhere.
                val current = mutableState.value
                val reread = !current.isOffline && (current.isRefreshing || current.problem != null)
                try {
                    val known = (session.state.value as? ConnectionState.Connected).takeUnless { reread }
                    val connection = known ?: session.refresh()
                    val cameras =
                        (connection as? ConnectionState.Connected)
                            ?.snapshot
                            ?.catalog
                            ?.cameras
                            .orEmpty()
                    val page =
                        session.reviews(
                            // Zero would make Frigate return only the past day.
                            after = 1,
                            before = before,
                            cameras = filter.camera?.let(::listOf),
                            labels = filter.label?.let(::listOf),
                            severity = filter.severity,
                            limit = PAGE_SIZE,
                        )
                    mutableState.update { state ->
                        val items = if (first) page else (shown + page).distinctBy { it.id }
                        state.copy(
                            cameras = cameras,
                            labels = (state.labels + page.flatMap { it.labels }).distinct().sorted(),
                            items = items,
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
