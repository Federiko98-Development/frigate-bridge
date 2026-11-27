package it.federicoraponi.watchdeck.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.federicoraponi.watchdeck.server.ConnectionState
import it.federicoraponi.watchdeck.server.ServerRegistry
import it.federicoraponi.watchdeck.settings.AppSettings
import it.federicoraponi.watchdeck.settings.GroupButtons
import it.federicoraponi.watchdeck.settings.HomeLayout
import it.federicoraponi.watchdeck.settings.PreviewRefresh
import it.federicoraponi.watchdeck.settings.SettingsStore
import it.federicoraponi.watchdeck.settings.ThemeMode
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

/** The signed-in user of the active server. */
data class AccountEntry(
    val serverName: String,
    val username: String,
)

data class SettingsState(
    /** `null` while the saved settings are being read. */
    val settings: AppSettings? = null,
    /** The active server's name, when its user is an administrator and its pages can be offered. */
    val managedServer: String? = null,
    /** The signed-in account of the active server, when its password can be changed here. */
    val account: AccountEntry? = null,
)

class SettingsViewModel(
    private val store: SettingsStore,
    private val registry: ServerRegistry,
) : ViewModel() {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val serverEntries: Flow<ServerEntries> =
        registry.state.flatMapLatest { current ->
            val server = current?.activeServer
            val session = server?.let { registry.session(it.id) }
            session?.state?.map { entries(server.name, it) } ?: flowOf(ServerEntries())
        }

    val state: StateFlow<SettingsState> =
        combine(store.settings, serverEntries) { settings, entries ->
            SettingsState(settings, entries.managedServer, entries.account)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SettingsState())

    private fun entries(
        serverName: String,
        connection: ConnectionState,
    ): ServerEntries {
        val snapshot = (connection as? ConnectionState.Connected)?.snapshot ?: return ServerEntries()
        val hasPassword = snapshot.authEnabled && !snapshot.account.isAnonymous
        return ServerEntries(
            managedServer = serverName.takeIf { snapshot.catalog.canChangeSettings },
            account = if (hasPassword) AccountEntry(serverName, snapshot.account.username) else null,
        )
    }

    fun setThemeMode(mode: ThemeMode) = update { it.copy(themeMode = mode) }

    fun setDynamicColor(enabled: Boolean) = update { it.copy(dynamicColor = enabled) }

    fun setHomeLayout(layout: HomeLayout) = update { it.copy(homeLayout = layout) }

    fun setGroupButtons(buttons: GroupButtons) = update { it.copy(groupButtons = buttons) }

    fun setPreviewRefresh(refresh: PreviewRefresh) = update { it.copy(previewRefresh = refresh) }

    fun setSavePreviewData(enabled: Boolean) = update { it.copy(savePreviewData = enabled) }

    private fun update(change: (AppSettings) -> AppSettings) {
        viewModelScope.launch { store.update(change) }
    }

    private data class ServerEntries(
        val managedServer: String? = null,
        val account: AccountEntry? = null,
    )
}
