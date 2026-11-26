package it.federicoraponi.frigatebridge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.federicoraponi.frigatebridge.frigate.FrigateError
import it.federicoraponi.frigatebridge.server.ServerRegistry
import it.federicoraponi.frigatebridge.server.ServerStatistics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ServerStatusState(
    val serverName: String = "",
    val statistics: ServerStatistics? = null,
    /** The names of the cameras as shown to the user, by their name on the server. */
    val cameraNames: Map<String, String> = emptyMap(),
    val isRefreshing: Boolean = false,
    val failure: VideoFailure? = null,
    /** There is no server to show: go back. */
    val isGone: Boolean = false,
)

/** How the active server is doing. For administrators. */
class ServerStatusViewModel(
    private val registry: ServerRegistry,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ServerStatusState())
    val state: StateFlow<ServerStatusState> = mutableState.asStateFlow()

    init {
        load()
    }

    fun refresh() {
        if (mutableState.value.isRefreshing) return
        mutableState.update { it.copy(isRefreshing = true) }
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val server = registry.load().activeServer
            val session = server?.let { registry.session(it.id) }
            if (session == null) {
                mutableState.update { it.copy(isGone = true) }
                return@launch
            }
            mutableState.update { it.copy(serverName = server.name) }
            try {
                var statistics: ServerStatistics? = null
                visibleRefresh { statistics = session.statistics() }
                mutableState.update {
                    it.copy(
                        statistics = statistics,
                        cameraNames = session.cameraNames,
                        isRefreshing = false,
                        failure = null,
                    )
                }
            } catch (error: FrigateError) {
                mutableState.update { it.copy(isRefreshing = false, failure = error.toVideoFailure()) }
            }
        }
    }
}
