package it.federicoraponi.frigatebridge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.federicoraponi.frigatebridge.camera.Camera
import it.federicoraponi.frigatebridge.camera.CameraRef
import it.federicoraponi.frigatebridge.camera.GroupChoice
import it.federicoraponi.frigatebridge.camera.groupChoices
import it.federicoraponi.frigatebridge.camera.showing
import it.federicoraponi.frigatebridge.frigate.FrigateError
import it.federicoraponi.frigatebridge.network.NetworkMonitor
import it.federicoraponi.frigatebridge.network.connections
import it.federicoraponi.frigatebridge.server.ConnectionState
import it.federicoraponi.frigatebridge.server.RegistryState
import it.federicoraponi.frigatebridge.server.Server
import it.federicoraponi.frigatebridge.server.ServerRegistry
import it.federicoraponi.frigatebridge.settings.AppSettings
import it.federicoraponi.frigatebridge.settings.HomeLayout
import it.federicoraponi.frigatebridge.settings.SettingsStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ServerSummary(
    val id: String,
    val name: String,
    val colorIndex: Int,
)

sealed interface CamerasContent {
    data object Loading : CamerasContent

    /** There is no active server any more, e.g. it was just deleted: go back to the list. */
    data object NoServer : CamerasContent

    data class Ready(
        val cameras: List<Camera>,
        /** Set when the server is older than the oldest supported Frigate release. */
        val outdatedVersion: String?,
    ) : CamerasContent

    data class Failed(
        val problem: ConnectionProblem,
    ) : CamerasContent
}

data class CamerasState(
    val server: ServerSummary? = null,
    /** All saved servers, for the quick switcher. */
    val servers: List<ServerSummary> = emptyList(),
    val groups: List<String> = emptyList(),
    /** `null` means "all cameras". */
    val selectedGroup: String? = null,
    /** "All cameras" and every group, each with the cameras switched on and off in it. */
    val choices: List<GroupChoice> = emptyList(),
    val content: CamerasContent = CamerasContent.Loading,
    val settings: AppSettings = AppSettings(),
    /** The user asked to read the server again and it has not answered yet. */
    val isRefreshing: Boolean = false,
    /** Grows after every refresh, so that the pictures are loaded again too. */
    val reloads: Int = 0,
    /** The phone has no network: what is on screen stays as it was. */
    val isOffline: Boolean = false,
)

/** The cameras of the active server, and only of that one. */
class CamerasViewModel(
    private val registry: ServerRegistry,
    private val settingsStore: SettingsStore,
    private val network: NetworkMonitor,
) : ViewModel() {
    private val selectedGroup = MutableStateFlow<GroupSelection?>(null)

    private val refresh = MutableStateFlow(RefreshProgress())

    private val offline = MutableStateFlow(false)

    // Kept so that the grid is not empty when the user comes back to it.
    private val previews = mutableMapOf<CameraRef, ByteArray>()

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<CamerasState> =
        combine(
            registry.state.flatMapLatest { activeServer(it) },
            settingsStore.settings,
            refresh,
            offline,
        ) { cameras, settings, progress, isOffline ->
            cameras.copy(
                settings = settings,
                isRefreshing = progress.running,
                reloads = progress.done,
                isOffline = isOffline,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), CamerasState())

    init {
        viewModelScope.launch { network.status.collect { offline.value = !it.isOnline } }
        viewModelScope.launch {
            registry.load()
            val servers = registry.state.map { it?.activeServerId to it?.revision }.distinctUntilChanged()
            // On a new network the server is read again: it may answer on its other address.
            combine(servers, network.status.connections()) { server, connection -> server to connection }
                // Switching server abandons the request to the previous one.
                .collectLatest { (server, _) -> server.first?.let { connect(it) } }
        }
    }

    private suspend fun connect(serverId: String) {
        val session = registry.session(serverId) ?: return
        // Without a network, reading a connected server again would only replace its cameras with an error.
        val isOnline = network.status.first().isOnline
        if (isOnline || session.state.value !is ConnectionState.Connected) session.refresh()
    }

    fun selectGroup(name: String?) {
        val serverId = registry.state.value?.activeServerId ?: return
        selectedGroup.value = name?.let { GroupSelection(serverId, it) }
    }

    fun switchTo(serverId: String) {
        viewModelScope.launch { registry.activate(serverId) }
    }

    /** Switches on, in a group, only the cameras named in [shown], and shows that group. */
    fun showCameras(
        group: String?,
        shown: Set<String>,
    ) {
        val serverId = registry.state.value?.activeServerId ?: return
        val choice = state.value.choices.firstOrNull { it.name == group } ?: return
        selectGroup(group)
        viewModelScope.launch { settingsStore.update { it.showing(serverId, choice, shown) } }
    }

    fun toggleLayout() {
        viewModelScope.launch {
            settingsStore.update {
                it.copy(homeLayout = if (it.homeLayout == HomeLayout.Grid) HomeLayout.List else HomeLayout.Grid)
            }
        }
    }

    /** Reads the active server again: cameras, groups, state and pictures. */
    fun retry() {
        val serverId = registry.state.value?.activeServerId ?: return
        if (refresh.value.running) return
        refresh.update { it.copy(running = true) }
        viewModelScope.launch {
            try {
                // Without a network, reading the server would only replace the cameras with an error.
                visibleRefresh { if (!offline.value) registry.session(serverId)?.refresh() }
                refresh.update { it.copy(done = it.done + 1) }
            } finally {
                refresh.update { it.copy(running = false) }
            }
        }
    }

    /** The last picture loaded for [camera], if any. */
    fun cachedPreview(camera: CameraRef): ByteArray? = previews[camera]

    /** A current low-resolution JPEG of [camera], or `null` if it cannot be read right now. */
    suspend fun loadPreview(camera: CameraRef): ByteArray? {
        if (offline.value) return null
        val session = registry.session(camera.serverId) ?: return null
        return try {
            session.latestFrame(camera.cameraName, PREVIEW_HEIGHT).also { previews[camera] = it }
        } catch (_: FrigateError) {
            // The grid keeps the previous picture; the server's state is reported elsewhere.
            null
        }
    }

    private fun activeServer(current: RegistryState?): Flow<CamerasState> {
        if (current == null) return flowOf(CamerasState())
        val servers = current.servers.map { it.summary() }
        val server = current.activeServer
        val session = server?.let { registry.session(it.id) }
        if (server == null || session == null) {
            return flowOf(CamerasState(servers = servers, content = CamerasContent.NoServer))
        }
        val hiddenCameras = settingsStore.settings.map { it.hiddenCameras }.distinctUntilChanged()
        return combine(session.state, selectedGroup, hiddenCameras) { connection, selection, hidden ->
            val summary = server.summary()
            when (connection) {
                ConnectionState.Idle, ConnectionState.Connecting ->
                    CamerasState(summary, servers)
                is ConnectionState.Failed ->
                    CamerasState(
                        server = summary,
                        servers = servers,
                        content = CamerasContent.Failed(connection.error.toProblem()),
                    )
                is ConnectionState.Connected -> {
                    val snapshot = connection.snapshot
                    val groups = snapshot.catalog.groups.map { it.name }
                    // A selection made on another server, or on a group that no longer exists, does not apply.
                    val group = selection?.takeIf { it.serverId == server.id && it.name in groups }?.name
                    val catalog = snapshot.catalog
                    val choices = catalog.groupChoices(server.id, catalog.camerasIn(null), hidden)
                    CamerasState(
                        server = summary,
                        servers = servers,
                        groups = groups,
                        selectedGroup = group,
                        choices = choices,
                        content =
                            CamerasContent.Ready(
                                cameras = choices.first { it.name == group }.shown,
                                outdatedVersion = snapshot.version.takeUnless { snapshot.isSupportedVersion },
                            ),
                    )
                }
            }
        }
    }

    private fun Server.summary() = ServerSummary(id, name, colorIndex)

    private data class RefreshProgress(
        val running: Boolean = false,
        val done: Int = 0,
    )

    private data class GroupSelection(
        val serverId: String,
        val name: String,
    )
}

/** Height in pixels of the pictures in the camera grid. */
private const val PREVIEW_HEIGHT = 270
