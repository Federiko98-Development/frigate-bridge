package it.federicoraponi.frigatebridge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.federicoraponi.frigatebridge.server.ConnectionState
import it.federicoraponi.frigatebridge.server.RegistryState
import it.federicoraponi.frigatebridge.server.ServerRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ServerListItem(
    val id: String,
    val name: String,
    val address: String,
    val colorIndex: Int,
    val isDefault: Boolean,
    val status: ServerStatus,
)

data class ServerListState(
    val isLoading: Boolean = true,
    val servers: List<ServerListItem> = emptyList(),
)

class ServerListViewModel(
    private val registry: ServerRegistry,
) : ViewModel() {
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<ServerListState> =
        registry.state
            .flatMapLatest { items(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ServerListState())

    init {
        viewModelScope.launch {
            registry.load()
            // Each server is checked on its own, so a slow one does not hold back the others.
            registry.state.collect { current ->
                current?.servers.orEmpty().forEach { server ->
                    val session = registry.session(server.id) ?: return@forEach
                    if (session.state.value == ConnectionState.Idle) launch { session.refresh() }
                }
            }
        }
    }

    fun refresh() {
        registry.state.value?.servers.orEmpty().forEach { server ->
            val session = registry.session(server.id) ?: return@forEach
            viewModelScope.launch { session.refresh() }
        }
    }

    fun open(serverId: String) {
        viewModelScope.launch { registry.activate(serverId) }
    }

    /** Makes [serverId] the server the app opens on, or removes the default when it already is. */
    fun toggleDefault(serverId: String) {
        viewModelScope.launch {
            val isDefault = registry.state.value?.defaultServerId == serverId
            registry.setDefault(if (isDefault) null else serverId)
        }
    }

    fun remove(serverId: String) {
        viewModelScope.launch { registry.remove(serverId) }
    }

    private fun items(current: RegistryState?): Flow<ServerListState> {
        if (current == null) return flowOf(ServerListState())
        if (current.servers.isEmpty()) return flowOf(ServerListState(isLoading = false))
        val rows =
            current.servers.mapNotNull { server ->
                registry.session(server.id)?.state?.map { connection ->
                    ServerListItem(
                        id = server.id,
                        name = server.name,
                        address = server.url,
                        colorIndex = server.colorIndex,
                        isDefault = server.id == current.defaultServerId,
                        status = connection.toStatus(),
                    )
                }
            }
        return combine(rows) { ServerListState(isLoading = false, servers = it.toList()) }
    }
}

internal const val STOP_TIMEOUT_MILLIS = 5_000L
