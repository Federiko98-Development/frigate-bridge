package it.federicoraponi.frigatebridge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.federicoraponi.frigatebridge.server.AccessRules
import it.federicoraponi.frigatebridge.server.ConnectionState
import it.federicoraponi.frigatebridge.server.PasswordProblem
import it.federicoraponi.frigatebridge.server.ServerRegistry
import it.federicoraponi.frigatebridge.server.ServerSession
import it.federicoraponi.frigatebridge.server.ServerSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChangePasswordState(
    val serverName: String = "",
    val username: String = "",
    val isSaving: Boolean = false,
    /** What is wrong with the fields: nothing was sent. */
    val password: PasswordProblem? = null,
    val failure: AdminFailure? = null,
    val isDone: Boolean = false,
    /** There is no server, or nobody to change the password of: go back. */
    val isGone: Boolean = false,
)

/** The signed-in user's own password on the active server. Passwords are never kept in the state. */
class ChangePasswordViewModel(
    private val registry: ServerRegistry,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ChangePasswordState())
    val state: StateFlow<ChangePasswordState> = mutableState.asStateFlow()

    // The server on screen. Its session is asked for each time: editing the server replaces it.
    private var serverId: String? = null

    init {
        viewModelScope.launch {
            val server = registry.load().activeServer
            if (server == null) {
                gone()
                return@launch
            }
            serverId = server.id
            mutableState.update { it.copy(serverName = server.name) }
            val session = registry.session(server.id)
            if (session == null) gone() else signedIn(session)
        }
    }

    fun save(
        current: String,
        new: String,
        confirmation: String,
    ) {
        val before = mutableState.value
        if (before.isSaving || before.isDone || before.isGone) return
        val id = serverId ?: return
        val problem =
            if (current.isEmpty()) PasswordProblem.CurrentMissing else AccessRules.passwordProblem(new, confirmation)
        if (problem != null) {
            mutableState.update { it.copy(password = problem, failure = null) }
            return
        }
        mutableState.update { it.copy(isSaving = true, password = null, failure = null) }
        viewModelScope.launch {
            try {
                val session = registry.session(id)
                if (session == null) {
                    gone()
                    return@launch
                }
                val snapshot = signedIn(session) ?: return@launch
                // Frigate does not check an administrator's current password: the app does, with the saved one.
                if (snapshot.account.isAdmin && current != registry.storedPassword(id)) {
                    mutableState.update { it.copy(failure = AdminFailure.WrongCurrentPassword) }
                    return@launch
                }
                val failure = adminFailureOf { session.changeOwnPassword(current, new) }
                mutableState.update { it.copy(failure = failure, isDone = failure == null) }
            } finally {
                mutableState.update { it.copy(isSaving = false) }
            }
        }
    }

    fun dismissFailure() {
        mutableState.update { it.copy(failure = null) }
    }

    /** Connects if needed. `null` when the server cannot be used or signs nobody in, which the state then says. */
    private suspend fun signedIn(session: ServerSession): ServerSnapshot? {
        var connection = session.state.value
        if (connection !is ConnectionState.Connected) connection = session.refresh()
        if (connection is ConnectionState.Failed) {
            mutableState.update { it.copy(failure = connection.error.toAdminFailure()) }
        }
        val snapshot = (connection as? ConnectionState.Connected)?.snapshot ?: return null
        if (!snapshot.authEnabled || snapshot.account.isAnonymous) {
            gone()
            return null
        }
        mutableState.update { it.copy(username = snapshot.account.username) }
        return snapshot
    }

    private fun gone() {
        mutableState.update { it.copy(isGone = true) }
    }
}
