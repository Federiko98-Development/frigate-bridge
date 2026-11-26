package it.federicoraponi.frigatebridge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.federicoraponi.frigatebridge.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why the user is being asked who they are. */
enum class LockPurpose {
    Unlock,
    Enable,
    Disable,
}

data class LockState(
    /** `null` while the settings are being read: nothing of the app may be shown yet. */
    val isLocked: Boolean? = null,
    val isEnabled: Boolean = false,
    /** Why the last attempt did not succeed, in the system's words. */
    val problem: String? = null,
)

/** Time out of sight after which the app locks again: enough for a share sheet or a permission dialog. */
const val LOCK_AFTER_MILLIS = 10_000L

/**
 * The app lock. The platform asks for the fingerprint, face or code and reports the outcome; times are from a
 * clock that never goes back, such as the time since the phone started.
 */
class LockViewModel(
    private val settings: SettingsStore,
) : ViewModel() {
    private val mutableState = MutableStateFlow(LockState())
    val state: StateFlow<LockState> = mutableState.asStateFlow()
    private var purpose: LockPurpose? = null
    private var hiddenAt: Long? = null

    init {
        viewModelScope.launch {
            settings.settings.map { it.appLock }.distinctUntilChanged().collect { enabled ->
                mutableState.update {
                    // Locked from the start when enabled; enabling it later does not lock who just did.
                    val locked = if (enabled) it.isLocked ?: true else false
                    it.copy(isLocked = locked, isEnabled = enabled)
                }
            }
        }
    }

    /** The platform is about to ask the user. */
    fun asking(why: LockPurpose) {
        purpose = why
        mutableState.update { it.copy(problem = null) }
    }

    /** The user proved to be the phone's owner. */
    fun authenticated() {
        when (purpose) {
            LockPurpose.Unlock -> mutableState.update { it.copy(isLocked = false) }
            LockPurpose.Enable -> save(true)
            LockPurpose.Disable -> {
                mutableState.update { it.copy(isLocked = false) }
                save(false)
            }
            null -> Unit
        }
        purpose = null
    }

    fun authenticationFailed(message: String?) {
        purpose = null
        mutableState.update { it.copy(problem = message) }
    }

    /** The phone has no screen lock any more, so there is nothing to ask for. */
    fun cannotAuthenticate() {
        mutableState.update { it.copy(isLocked = false) }
    }

    fun appHidden(atMillis: Long) {
        if (mutableState.value.isLocked == false) hiddenAt = atMillis
    }

    fun appShown(atMillis: Long) {
        val since = hiddenAt ?: return
        hiddenAt = null
        if (mutableState.value.isEnabled && atMillis - since >= LOCK_AFTER_MILLIS) {
            mutableState.update { it.copy(isLocked = true, problem = null) }
        }
    }

    private fun save(enabled: Boolean) {
        viewModelScope.launch { settings.update { it.copy(appLock = enabled) } }
    }
}
