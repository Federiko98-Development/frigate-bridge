package it.federicoraponi.frigatebridge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.federicoraponi.frigatebridge.camera.Camera
import it.federicoraponi.frigatebridge.server.ConnectionState
import it.federicoraponi.frigatebridge.server.RegistryState
import it.federicoraponi.frigatebridge.server.Server
import it.federicoraponi.frigatebridge.server.ServerRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    val content: CamerasContent = CamerasContent.Loading,
)

/** The cameras of the active server, and only of that one. */
class CamerasViewModel(
    private val registry: ServerRegistry,
) : ViewModel() {
    private val selectedGroup = MutableStateFlow<GroupSelection?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<CamerasState> =
        registry.state
            .flatMapLatest { activeServer(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), CamerasState())

    init {
        viewModelScope.launch {
            registry.load()
            registry.state
                .map { it?.activeServerId to it?.revision }
                .distinctUntilChanged()
                // Switching server abandons the request to the previous one.
                .collectLatest { (serverId, _) -> serverId?.let { registry.session(it)?.refresh() } }
        }
    }

    fun selectGroup(name: String?) {
        val serverId = registry.state.value?.activeServerId ?: return
        selectedGroup.value = name?.let { GroupSelection(serverId, it) }
    }

    fun switchTo(serverId: String) {
        viewModelScope.launch { registry.activate(serverId) }
    }

    fun retry() {
        val serverId = registry.state.value?.activeServerId ?: return
        viewModelScope.launch { registry.session(serverId)?.refresh() }
    }

    private fun activeServer(current: RegistryState?): Flow<CamerasState> {
        if (current == null) return flowOf(CamerasState())
        val servers = current.servers.map { it.summary() }
        val server = current.activeServer
        val session = server?.let { registry.session(it.id) }
        if (server == null || session == null) {
            return flowOf(CamerasState(servers = servers, content = CamerasContent.NoServer))
        }
        return combine(session.state, selectedGroup) { connection, selection ->
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
                    CamerasState(
                        server = summary,
                        servers = servers,
                        groups = groups,
                        selectedGroup = group,
                        content =
                            CamerasContent.Ready(
                                cameras = snapshot.catalog.camerasIn(group),
                                outdatedVersion = snapshot.version.takeUnless { snapshot.isSupportedVersion },
                            ),
                    )
                }
            }
        }
    }

    private fun Server.summary() = ServerSummary(id, name, colorIndex)

    private data class GroupSelection(
        val serverId: String,
        val name: String,
    )
}
