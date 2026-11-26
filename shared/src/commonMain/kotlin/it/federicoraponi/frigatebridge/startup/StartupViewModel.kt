package it.federicoraponi.frigatebridge.startup

import androidx.lifecycle.ViewModel
import it.federicoraponi.frigatebridge.Platform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class StartupState(
    val platformName: String,
    val tapCount: Int = 0,
)

/**
 * Placeholder ViewModel that sets the contract every screen follows:
 * an observable state, plus actions exposed as functions.
 */
class StartupViewModel(
    platform: Platform,
) : ViewModel() {
    private val mutableState = MutableStateFlow(StartupState(platformName = platform.name))
    val state: StateFlow<StartupState> = mutableState.asStateFlow()

    fun onTap() {
        mutableState.update { it.copy(tapCount = it.tapCount + 1) }
    }
}
