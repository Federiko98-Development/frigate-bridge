package it.federicoraponi.frigatebridge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.federicoraponi.frigatebridge.settings.AppSettings
import it.federicoraponi.frigatebridge.settings.HomeLayout
import it.federicoraponi.frigatebridge.settings.PreviewRefresh
import it.federicoraponi.frigatebridge.settings.SettingsStore
import it.federicoraponi.frigatebridge.settings.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsState(
    /** `null` while the saved settings are being read. */
    val settings: AppSettings? = null,
)

class SettingsViewModel(
    private val store: SettingsStore,
) : ViewModel() {
    val state: StateFlow<SettingsState> =
        store.settings
            .map { SettingsState(it) }
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
