package it.federicoraponi.watchdeck.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.federicoraponi.watchdeck.server.ServerRegistry
import it.federicoraponi.watchdeck.server.parseEventLink
import it.federicoraponi.watchdeck.server.serverFor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class StartDestination {
    ServerList,

    /** The cameras of the default server. */
    Cameras,
}

/** A review moment to show, asked for by a link. */
data class EventTarget(
    val serverId: String,
    val reviewId: String,
)

data class StartState(
    /** `null` while the saved servers are being read. */
    val destination: StartDestination? = null,
    /** Set until [StartViewModel.linkHandled]: show this moment. */
    val target: EventTarget? = null,
    /** Set until [StartViewModel.linkHandled]: a link was not valid, or means a server that is not saved. */
    val isLinkRejected: Boolean = false,
)

/** Decides which screen the app opens on, and where a link leads. */
class StartViewModel(
    private val registry: ServerRegistry,
) : ViewModel() {
    private val mutableState = MutableStateFlow(StartState())
    val state: StateFlow<StartState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val loaded = registry.load()
            val destination =
                if (loaded.activeServer != null) StartDestination.Cameras else StartDestination.ServerList
            mutableState.update { it.copy(destination = destination) }
        }
    }

    /** The app was asked to open [link]. */
    fun openLink(link: String) {
        viewModelScope.launch {
            val parsed = parseEventLink(link)
            val server = parsed?.let { registry.load().serverFor(it) }
            if (parsed == null || server == null) {
                mutableState.update { it.copy(isLinkRejected = true) }
            } else {
                registry.activate(server.id)
                mutableState.update { it.copy(target = EventTarget(server.id, parsed.reviewId)) }
            }
        }
    }

    fun linkHandled() {
        mutableState.update { it.copy(target = null, isLinkRejected = false) }
    }
}
