package it.federicoraponi.frigatebridge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.federicoraponi.frigatebridge.server.ConnectionState
import it.federicoraponi.frigatebridge.server.ServerRegistry
import it.federicoraponi.frigatebridge.settings.AppSettings
import it.federicoraponi.frigatebridge.settings.HomeLayout
import it.federicoraponi.frigatebridge.settings.PreviewRefresh
import it.federicoraponi.frigatebridge.settings.SettingsStore
import it.federicoraponi.frigatebridge.settings.ThemeMode
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

data class SettingsState(
    /** `null` while the saved settings are being read. */
    val settings: AppSettings? = null,
    /** The active server's name, when its user is an administrator and its pages can be offered. */
    val managedServer: String? = null,
)

class SettingsViewModel(
    private val store: SettingsStore,
    private val registry: ServerRegistry,
) : ViewModel() {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val managedServer: Flow<String?> =
        registry.state.flatMapLatest { current ->
            val server = current?.activeServer
            val session = server?.let { registry.session(it.id) }
            session?.state?.map { connection ->
                val admin = (connection as? ConnectionState.Connected)?.snapshot?.catalog?.canChangeSettings == true
                server.name.takeIf { admin }
            } ?: flowOf(null)
        }

    val state: StateFlow<SettingsState> =
        combine(store.settings, managedServer, ::SettingsState)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SettingsState())

    fun setThemeMode(mode: ThemeMode) = update { it.copy(themeMode = mode) }

    fun setDynamicColor(enabled: Boolean) = update { it.copy(dynamicColor = enabled) }

    fun setHomeLayout(layout: HomeLayout) = update { it.copy(homeLayout = layout) }

    fun setPreviewRefresh(refresh: PreviewRefresh) = update { it.copy(previewRefresh = refresh) }

    fun setSavePreviewData(enabled: Boolean) = update { it.copy(savePreviewData = enabled) }

    private fun update(change: (AppSettings) -> AppSettings) {
        viewModelScope.launch { store.update(change) }
    }
}
