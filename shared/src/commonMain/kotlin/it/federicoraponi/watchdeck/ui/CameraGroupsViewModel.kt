package it.federicoraponi.watchdeck.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.federicoraponi.watchdeck.camera.GroupRules
import it.federicoraponi.watchdeck.camera.forgettingGroup
import it.federicoraponi.watchdeck.frigate.FrigateError
import it.federicoraponi.watchdeck.server.AdminConfig
import it.federicoraponi.watchdeck.server.ServerRegistry
import it.federicoraponi.watchdeck.server.ServerSession
import it.federicoraponi.watchdeck.settings.SettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GroupRow(
    val name: String,
    val icon: String,
    /** The names, as shown to the user, of the members that are cameras. */
    val cameraNames: List<String>,
    /** The screen shows this member with its own label. */
    val hasBirdseye: Boolean,
)

data class CameraGroupsState(
    /** The server whose groups are listed, to open the editor with; empty until known. */
    val serverId: String = "",
    val serverName: String = "",
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    /** In their order. */
    val groups: List<GroupRow> = emptyList(),
    /** The list could not be read. */
    val failure: VideoFailure? = null,
    /** A change is being sent. */
    val running: Boolean = false,
    val actionFailure: AdminFailure? = null,
    /** There is no server to show: go back. */
    val isGone: Boolean = false,
)

/** The camera groups of the active server. For administrators. */
class CameraGroupsViewModel(
    private val registry: ServerRegistry,
    private val settings: SettingsStore,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CameraGroupsState())
    val state: StateFlow<CameraGroupsState> = mutableState.asStateFlow()
    private var loadJob: Job? = null

    fun refresh() {
        val current = mutableState.value
        if (current.isRefreshing || current.running) return
        mutableState.update { it.copy(isRefreshing = true) }
        load()
    }

    /** Reads the list. The screen calls it each time it comes to the front: a group may have been edited. */
    fun load() {
        // While a change is sent it does nothing: the list is read again after it.
        if (mutableState.value.running) return
        loadJob?.cancel()
        loadJob =
            viewModelScope.launch {
                val session = session() ?: return@launch
                try {
                    var config: AdminConfig? = null
                    visibleRefresh { config = session.adminConfig() }
                    config?.let { show(it) }
                } catch (error: FrigateError) {
                    showFailure(error)
                }
            }
    }

    /**
     * Puts the groups in the order of [names], which must be the names of the listed ones. The list takes the new
     * order at once, and goes back if the server does not.
     */
    fun reorder(names: List<String>) {
        val before = mutableState.value
        if (before.running || before.isGone) return
        val rows = before.groups.associateBy { it.name }
        val reordered = names.mapNotNull { rows[it] }
        if (names.size != rows.size || reordered.toSet().size != rows.size || reordered == before.groups) return
        mutableState.update { it.copy(groups = reordered) }
        run(
            isApplied = { config -> config.groups.map { it.name } == names },
            onFailedUnread = { mutableState.update { it.copy(groups = before.groups) } },
        ) { it.reorderGroups(names) }
    }

    /** Removes a group. The screen confirms first. */
    fun delete(name: String) {
        run(
            isApplied = { config -> config.groups.none { it.name == name } },
            onApplied = { serverId -> settings.update { it.forgettingGroup(serverId, name) } },
        ) { it.deleteGroup(name) }
    }

    fun dismissFailure() {
        mutableState.update { it.copy(actionFailure = null) }
    }

    /**
     * Sends a change and reads the list again. If the change did not report success but [isApplied] finds it
     * there, it counts as made: only checking it failed, or someone else had already deleted the group.
     */
    private fun run(
        isApplied: (AdminConfig) -> Boolean,
        onApplied: suspend (serverId: String) -> Unit = {},
        /** Called when the change failed and the list could not be read to show what the server has. */
        onFailedUnread: () -> Unit = {},
        send: suspend (ServerSession) -> Unit,
    ) {
        if (mutableState.value.running || mutableState.value.isGone) return
        // A reading still on its way would bring back the list from before the change.
        loadJob?.cancel()
        mutableState.update { it.copy(running = true, actionFailure = null, isRefreshing = false) }
        viewModelScope.launch {
            try {
                val session = session() ?: return@launch
                // Leaving the screen must not stop between the change and moving the settings that follow it.
                var failure =
                    withContext(NonCancellable) {
                        adminFailureOf { send(session) }.also { if (it == null) onApplied(session.server.id) }
                    }
                // Read after the change whatever its outcome, without the delay of a refresh.
                try {
                    val config = session.adminConfig()
                    if (failure != null && isApplied(config)) {
                        failure = null
                        onApplied(session.server.id)
                        session.refresh()
                    }
                    show(config)
                } catch (error: FrigateError) {
                    if (failure != null) onFailedUnread()
                    showFailure(error)
                }
                mutableState.update { it.copy(actionFailure = failure) }
            } finally {
                mutableState.update { it.copy(running = false) }
            }
        }
    }

    private fun show(config: AdminConfig) {
        val displayNames = config.cameras.associate { it.name to it.displayName }
        val rows =
            config.groups.map { group ->
                GroupRow(
                    name = group.name,
                    icon = group.icon,
                    cameraNames = group.cameras.mapNotNull { displayNames[it] },
                    hasBirdseye = GroupRules.BIRDSEYE in group.cameras,
                )
            }
        mutableState.update { it.copy(groups = rows, isLoading = false, isRefreshing = false, failure = null) }
    }

    private fun showFailure(error: FrigateError) {
        mutableState.update { it.copy(isLoading = false, isRefreshing = false, failure = error.toVideoFailure()) }
    }

    private suspend fun session(): ServerSession? {
        val current = registry.load()
        // Once known, the server stays the one on screen. Its session is asked for each time.
        val shownId = mutableState.value.serverId.ifEmpty { current.activeServerId }
        val server = current.servers.firstOrNull { it.id == shownId }
        val session = server?.let { registry.session(it.id) }
        if (server == null || session == null) {
            mutableState.update { it.copy(isGone = true) }
            return null
        }
        mutableState.update { it.copy(serverId = server.id, serverName = server.name) }
        return session
    }
}
