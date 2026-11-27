package it.federicoraponi.frigatebridge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.federicoraponi.frigatebridge.camera.CameraOption
import it.federicoraponi.frigatebridge.frigate.FrigateError
import it.federicoraponi.frigatebridge.server.AccessRules
import it.federicoraponi.frigatebridge.server.AdminConfig
import it.federicoraponi.frigatebridge.server.CustomRole
import it.federicoraponi.frigatebridge.server.FrigateUser
import it.federicoraponi.frigatebridge.server.NameProblem
import it.federicoraponi.frigatebridge.server.PasswordProblem
import it.federicoraponi.frigatebridge.server.ServerRegistry
import it.federicoraponi.frigatebridge.server.ServerSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UserRow(
    val username: String,
    val role: String,
    val isSelf: Boolean,
    val canChangeRole: Boolean,
    val canDelete: Boolean,
)

sealed interface AccessAction {
    data class CreateUser(
        val username: String,
    ) : AccessAction

    data class ChangeRole(
        val username: String,
    ) : AccessAction

    data class SetPassword(
        val username: String,
    ) : AccessAction

    data class DeleteUser(
        val username: String,
    ) : AccessAction

    data class SaveRole(
        val name: String,
        val isNew: Boolean,
    ) : AccessAction

    data class DeleteRole(
        val name: String,
    ) : AccessAction
}

sealed interface AccessResult {
    val action: AccessAction

    data class Done(
        override val action: AccessAction,
    ) : AccessResult

    /** Nothing was sent. */
    data class Invalid(
        override val action: AccessAction,
        val name: NameProblem? = null,
        val password: PasswordProblem? = null,
        val camerasMissing: Boolean = false,
    ) : AccessResult

    data class Failed(
        override val action: AccessAction,
        val failure: AdminFailure,
    ) : AccessResult
}

data class AccessState(
    val serverName: String = "",
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    /** Users are listed only when the server signs users in. */
    val authEnabled: Boolean = true,
    /** By name, as the server lists them. */
    val users: List<UserRow> = emptyList(),
    /** The roles other than admin and viewer, by name. */
    val roles: List<CustomRole> = emptyList(),
    /** The roles a user can be given: admin, viewer, then the custom ones. */
    val roleChoices: List<String> = AccessRules.BUILT_IN_ROLES,
    /** The cameras a role can be given. */
    val cameras: List<CameraOption> = emptyList(),
    /** The lists could not be read. */
    val failure: VideoFailure? = null,
    /** The action being sent. */
    val running: AccessAction? = null,
    /** The outcome of the last action, until [AccessViewModel.dismissResult]. */
    val result: AccessResult? = null,
    /** There is no server to show, or the user no longer administers it: go back. */
    val isGone: Boolean = false,
)

/** The users and roles of the active server. For administrators. */
class AccessViewModel(
    private val registry: ServerRegistry,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AccessState())
    val state: StateFlow<AccessState> = mutableState.asStateFlow()
    private var loadJob: Job? = null

    // The server on screen. Its session is asked for each time: editing the server replaces it.
    private var serverId: String? = null

    init {
        load()
    }

    fun refresh() {
        val current = mutableState.value
        if (current.isRefreshing || current.running != null) return
        mutableState.update { it.copy(isRefreshing = true) }
        load()
    }

    /** Reads the lists. While an action runs it does nothing: the lists are read again after it. */
    fun load() {
        if (mutableState.value.running != null) return
        loadJob?.cancel()
        loadJob =
            viewModelScope.launch {
                val session = session() ?: return@launch
                try {
                    var lists: Lists? = null
                    visibleRefresh { lists = read(session) }
                    show(session, lists)
                } catch (error: FrigateError) {
                    showFailure(error)
                }
            }
    }

    fun createUser(
        username: String,
        password: String,
        confirmation: String,
        role: String,
    ) {
        val action = AccessAction.CreateUser(username)
        if (isBusy()) return
        val nameProblem = AccessRules.usernameProblem(username, mutableState.value.users.map { it.username })
        val passwordProblem = AccessRules.passwordProblem(password, confirmation)
        if (nameProblem != null || passwordProblem != null) {
            invalid(AccessResult.Invalid(action, name = nameProblem, password = passwordProblem))
            return
        }
        run(action) { it.createUser(username, password, role) }
    }

    fun changeRole(
        username: String,
        role: String,
    ) {
        if (isBusy() || row(username)?.canChangeRole == false) return
        run(AccessAction.ChangeRole(username)) { it.setUserRole(username, role) }
    }

    /** Sets the password of another user. The signed-in one changes theirs with [ChangePasswordViewModel]. */
    fun setPassword(
        username: String,
        password: String,
        confirmation: String,
    ) {
        val action = AccessAction.SetPassword(username)
        if (isBusy() || row(username)?.isSelf == true) return
        val problem = AccessRules.passwordProblem(password, confirmation)
        if (problem != null) {
            invalid(AccessResult.Invalid(action, password = problem))
            return
        }
        run(action) { it.setUserPassword(username, password) }
    }

    fun deleteUser(username: String) {
        if (isBusy() || row(username)?.canDelete == false) return
        run(AccessAction.DeleteUser(username)) { it.deleteUser(username) }
    }

    /** Creates a role ([isNew]) or changes the cameras of one. */
    fun saveRole(
        name: String,
        cameras: Set<String>,
        isNew: Boolean,
    ) {
        val action = AccessAction.SaveRole(name, isNew)
        if (isBusy()) return
        val current = mutableState.value
        val nameProblem =
            if (isNew) AccessRules.roleNameProblem(name, current.roles.map { it.name }) else null
        // Frigate takes a role without cameras for one that sees them all.
        if (nameProblem != null || cameras.isEmpty()) {
            invalid(AccessResult.Invalid(action, name = nameProblem, camerasMissing = cameras.isEmpty()))
            return
        }
        val known = current.cameras.map { it.name }
        val ordered = known.filter { it in cameras } + cameras.filterNot { it in known }
        run(
            action = action,
            isApplied = { lists -> lists.config.customRoles.any { it.name == name && it.cameras == ordered } },
        ) { it.saveRole(name, ordered) }
    }

    /** Makes viewers of the users who have the role, then removes it. */
    fun deleteRole(name: String) {
        if (isBusy()) return
        val holders =
            mutableState.value.users
                .filter { it.role == name }
                .map { it.username }
        run(
            action = AccessAction.DeleteRole(name),
            isApplied = { lists -> lists.config.customRoles.none { it.name == name } },
        ) { it.deleteRole(name, holders) }
    }

    fun dismissResult() {
        mutableState.update { it.copy(result = null) }
    }

    // Until the lists are there, nothing can be checked against them.
    private fun isBusy(): Boolean = mutableState.value.let { it.running != null || it.isLoading || it.isGone }

    private fun row(username: String): UserRow? = mutableState.value.users.firstOrNull { it.username == username }

    private fun invalid(result: AccessResult.Invalid) {
        mutableState.update { it.copy(result = result) }
    }

    /**
     * Sends an action and reads the lists again, also after a failure, which may have left it half done. Whether
     * it worked does not depend on that reading, except for a change of the configuration: if [isApplied] finds
     * it there, it was made, and only checking it failed.
     */
    private fun run(
        action: AccessAction,
        isApplied: ((Lists) -> Boolean)? = null,
        send: suspend (ServerSession) -> Unit,
    ) {
        // A reading still on its way would bring back the lists from before the action.
        loadJob?.cancel()
        mutableState.update { it.copy(running = action, result = null, isRefreshing = false) }
        viewModelScope.launch {
            try {
                val session = session() ?: return@launch
                var failure = adminFailureOf { send(session) }
                try {
                    val lists = read(session)
                    if (failure != null && lists != null && isApplied?.invoke(lists) == true) {
                        failure = null
                        session.refresh()
                    }
                    show(session, lists)
                } catch (error: FrigateError) {
                    showFailure(error)
                }
                val result = failure?.let { AccessResult.Failed(action, it) } ?: AccessResult.Done(action)
                mutableState.update { it.copy(result = result) }
            } finally {
                mutableState.update { it.copy(running = null) }
            }
        }
    }

    /** `null` when the signed-in user turns out not to be an administrator any more. */
    private suspend fun read(session: ServerSession): Lists? {
        val config = session.adminConfig()
        val account = session.account
        if (!config.authEnabled) return Lists(config, emptyList(), account?.username)
        val users =
            try {
                session.users()
            } catch (error: FrigateError.UnexpectedResponse) {
                if (error.status != FORBIDDEN) throw error
                return null
            }
        // The token keeps the role it was issued with, so the list is what tells the truth.
        val isDemoted =
            account != null &&
                !account.isAnonymous &&
                users.none { it.username == account.username && it.role == "admin" }
        return if (isDemoted) null else Lists(config, users, account?.username)
    }

    private suspend fun show(
        session: ServerSession,
        lists: Lists?,
    ) {
        if (lists == null) {
            session.signInAgain()
            mutableState.update { it.copy(isLoading = false, isRefreshing = false, isGone = true) }
            return
        }
        val rows =
            lists.users.map { user ->
                val isSelf = user.username == lists.self
                val isEditable = !isSelf && !user.isBuiltInAdmin
                UserRow(user.username, user.role, isSelf, canChangeRole = isEditable, canDelete = isEditable)
            }
        mutableState.update {
            it.copy(
                authEnabled = lists.config.authEnabled,
                users = rows,
                roles = lists.config.customRoles,
                roleChoices = AccessRules.BUILT_IN_ROLES + lists.config.customRoles.map { role -> role.name },
                cameras = lists.config.cameras,
                isLoading = false,
                isRefreshing = false,
                failure = null,
            )
        }
    }

    private fun showFailure(error: FrigateError) {
        mutableState.update { it.copy(isLoading = false, isRefreshing = false, failure = error.toVideoFailure()) }
    }

    private suspend fun session(): ServerSession? {
        val current = registry.load()
        val server =
            current.servers.firstOrNull { it.id == (serverId ?: current.activeServerId) }
        val session = server?.let { registry.session(it.id) }
        if (server == null || session == null) {
            mutableState.update { it.copy(isGone = true) }
            return null
        }
        serverId = server.id
        mutableState.update { it.copy(serverName = server.name) }
        return session
    }

    private class Lists(
        val config: AdminConfig,
        val users: List<FrigateUser>,
        /** The signed-in user's name. */
        val self: String?,
    )

    private companion object {
        const val FORBIDDEN = 403
    }
}
