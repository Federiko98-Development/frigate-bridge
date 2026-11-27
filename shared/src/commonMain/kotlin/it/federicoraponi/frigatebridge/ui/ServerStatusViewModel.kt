package it.federicoraponi.frigatebridge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.federicoraponi.frigatebridge.frigate.FrigateError
import it.federicoraponi.frigatebridge.server.ServerRegistry
import it.federicoraponi.frigatebridge.server.ServerStatistics
import kotlinx.coroutines.Job
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
    /** A restart was asked for, and the server has not been seen answering since. */
    val isRestarting: Boolean = false,
    val restartFailure: AdminFailure? = null,
    /** There is no server to show: go back. */
    val isGone: Boolean = false,
)

/** How the active server is doing. For administrators. */
class ServerStatusViewModel(
    private val registry: ServerRegistry,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ServerStatusState())
    val state: StateFlow<ServerStatusState> = mutableState.asStateFlow()
    private var restartJob: Job? = null

    // The server on screen, which is the one to restart.
    private var serverId: String? = null

    init {
        load()
    }

    fun refresh() {
        if (mutableState.value.isRefreshing) return
        mutableState.update { it.copy(isRefreshing = true, restartFailure = null) }
        load()
    }

    /** Restarts Frigate. The screen confirms first. */
    fun restart() {
        if (mutableState.value.isRestarting) return
        val id = serverId ?: return
        mutableState.update { it.copy(isRestarting = true, restartFailure = null) }
        restartJob =
            viewModelScope.launch {
                val session = registry.session(id)
                if (session == null) {
                    mutableState.update { it.copy(isRestarting = false, isGone = true) }
                    return@launch
                }
                val failure = adminFailureOf { session.restart() }
                if (failure != null) {
                    mutableState.update { it.copy(isRestarting = false, restartFailure = failure) }
                }
            }
    }

    private fun load() {
        viewModelScope.launch {
            val server = registry.load().activeServer
            val session = server?.let { registry.session(it.id) }
            if (session == null) {
                mutableState.update { it.copy(isGone = true) }
                return@launch
            }
            serverId = server.id
            mutableState.update { it.copy(serverName = server.name) }
            // While the request to restart is on its way, an answer says nothing about the restart.
            val afterRestart = mutableState.value.isRestarting && restartJob?.isActive != true
            try {
                var statistics: ServerStatistics? = null
                visibleRefresh {
                    // The other screens must not keep showing the outage.
                    if (afterRestart) session.refresh()
                    statistics = session.statistics()
                }
                mutableState.update {
                    it.copy(
                        statistics = statistics,
                        cameraNames = session.cameraNames,
                        isRefreshing = false,
                        failure = null,
                        isRestarting = it.isRestarting && !afterRestart,
                    )
                }
            } catch (error: FrigateError) {
                mutableState.update { it.copy(isRefreshing = false, failure = error.toVideoFailure()) }
            }
        }
    }
}
