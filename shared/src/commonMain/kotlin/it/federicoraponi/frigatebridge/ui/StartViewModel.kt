package it.federicoraponi.frigatebridge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.federicoraponi.frigatebridge.server.ServerRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class StartDestination {
    ServerList,

    /** The cameras of the default server. */
    Cameras,
}

data class StartState(
    /** `null` while the saved servers are being read. */
    val destination: StartDestination? = null,
)

/** Decides which screen the app opens on. */
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
            mutableState.value = StartState(destination)
        }
    }
}
