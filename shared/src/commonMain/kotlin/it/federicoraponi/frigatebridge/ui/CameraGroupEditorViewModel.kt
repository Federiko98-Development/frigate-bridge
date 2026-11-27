package it.federicoraponi.frigatebridge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.federicoraponi.frigatebridge.camera.CameraOption
import it.federicoraponi.frigatebridge.camera.EditableGroup
import it.federicoraponi.frigatebridge.camera.GroupRules
import it.federicoraponi.frigatebridge.camera.forgettingGroup
import it.federicoraponi.frigatebridge.camera.renamingGroup
import it.federicoraponi.frigatebridge.frigate.FrigateError
import it.federicoraponi.frigatebridge.server.AdminConfig
import it.federicoraponi.frigatebridge.server.NameProblem
import it.federicoraponi.frigatebridge.server.ServerRegistry
import it.federicoraponi.frigatebridge.server.ServerSession
import it.federicoraponi.frigatebridge.settings.SettingsStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** [groupName] is `null` for a new group. */
data class CameraGroupEditorArgs(
    val serverId: String,
    val groupName: String?,
)

data class CameraGroupEditorState(
    val isNew: Boolean = true,
    val isLoading: Boolean = true,
    /** As typed. */
    val name: String = "",
    val icon: String = GroupRules.DEFAULT_ICON,
    /** The cameras, followed by [GroupRules.BIRDSEYE] when the server offers it; the screen labels that one. */
    val choices: List<CameraOption> = emptyList(),
    /** Names among [choices]. */
    val selected: Set<String> = emptySet(),
    val nameProblem: NameProblem? = null,
    val camerasMissing: Boolean = false,
    val isSaving: Boolean = false,
    /** The group could not be saved or deleted. */
    val failure: AdminFailure? = null,
    /** The group could not be read: nothing can be saved. */
    val loadFailure: VideoFailure? = null,
    /** Saved or deleted: go back. */
    val isDone: Boolean = false,
    /** There is no such server or group: go back. */
    val isGone: Boolean = false,
)

/** Creates or changes one camera group. For administrators. */
class CameraGroupEditorViewModel(
    private val args: CameraGroupEditorArgs,
    private val registry: ServerRegistry,
    private val settings: SettingsStore,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CameraGroupEditorState(isNew = args.groupName == null))
    val state: StateFlow<CameraGroupEditorState> = mutableState.asStateFlow()

    // `null` until read: saving before that would lose members of the group.
    private var loaded: AdminConfig? = null

    init {
        viewModelScope.launch {
            val session = session() ?: return@launch
            try {
                show(session.adminConfig())
            } catch (error: FrigateError) {
                mutableState.update { it.copy(isLoading = false, loadFailure = error.toVideoFailure()) }
            }
        }
    }

    fun setName(name: String) = edit { it.copy(name = name, nameProblem = null) }

    fun setIcon(icon: String) = edit { it.copy(icon = icon) }

    fun toggle(camera: String) =
        edit {
            if (it.choices.none { choice -> choice.name == camera }) {
                it
            } else {
                val selected = if (camera in it.selected) it.selected - camera else it.selected + camera
                it.copy(selected = selected, camerasMissing = false)
            }
        }

    fun save() {
        val config = loaded ?: return
        if (isBusy()) return
        val current = mutableState.value
        val original = original(config)
        // An unchanged name stays as it is, even one that would be written differently today.
        val name = if (current.name == original?.name) current.name else GroupRules.normalize(current.name)
        val otherNames = config.groups.map { it.name }.filterNot { it == original?.name }
        val nameProblem = GroupRules.nameProblem(name, otherNames, original?.name)
        val members = members(original, current)
        if (nameProblem != null || members.isEmpty()) {
            mutableState.update { it.copy(nameProblem = nameProblem, camerasMissing = members.isEmpty()) }
            return
        }
        val order = original?.order ?: ((config.groups.maxOfOrNull { it.order } ?: 0) + 1)
        val group = EditableGroup(name, members, current.icon, order)
        run(
            isApplied = { saved ->
                group in saved.groups &&
                    (original == null || original.name == name || saved.groups.none { it.name == original.name })
            },
            onApplied = {
                if (original != null && original.name != name) {
                    settings.update { it.renamingGroup(args.serverId, original.name, name) }
                }
            },
        ) { it.saveGroup(original?.name, group) }
    }

    /** Removes the group being edited. The screen confirms first. */
    fun delete() {
        if (loaded == null || isBusy()) return
        val name = args.groupName ?: return
        run(
            isApplied = { saved -> saved.groups.none { it.name == name } },
            onApplied = { settings.update { it.forgettingGroup(args.serverId, name) } },
        ) { it.deleteGroup(name) }
    }

    fun dismissFailure() {
        mutableState.update { it.copy(failure = null) }
    }

    private fun isBusy(): Boolean = mutableState.value.let { it.isSaving || it.isDone || it.isGone }

    private fun edit(change: (CameraGroupEditorState) -> CameraGroupEditorState) {
        if (isBusy()) return
        mutableState.update(change)
    }

    private fun original(config: AdminConfig): EditableGroup? = config.groups.firstOrNull { it.name == args.groupName }

    /**
     * The group's members in their order, then the newly selected ones. Members that are not offered as choices,
     * such as cameras since removed, are kept.
     */
    private fun members(
        original: EditableGroup?,
        current: CameraGroupEditorState,
    ): List<String> {
        val offered = current.choices.map { it.name }
        val before = original?.cameras.orEmpty()
        return before.filter { it !in offered || it in current.selected } +
            offered.filter { it in current.selected && it !in before }
    }

    private fun show(config: AdminConfig) {
        val original = original(config)
        if (args.groupName != null && original == null) {
            mutableState.update { it.copy(isLoading = false, isGone = true) }
            return
        }
        val choices =
            if (config.birdseyeEnabled) {
                config.cameras + CameraOption(GroupRules.BIRDSEYE, GroupRules.BIRDSEYE)
            } else {
                config.cameras
            }
        val members = original?.cameras.orEmpty().toSet()
        loaded = config
        mutableState.update {
            it.copy(
                isLoading = false,
                name = original?.name.orEmpty(),
                icon = original?.icon ?: GroupRules.DEFAULT_ICON,
                choices = choices,
                selected =
                    choices
                        .map { choice -> choice.name }
                        .filter { name -> name in members }
                        .toSet(),
            )
        }
    }

    /**
     * Sends a change. If it did not report success but [isApplied] finds it there when read again, it counts as
     * made: only checking it failed, or someone else had already deleted the group.
     */
    private fun run(
        isApplied: (AdminConfig) -> Boolean,
        onApplied: suspend () -> Unit,
        send: suspend (ServerSession) -> Unit,
    ) {
        mutableState.update { it.copy(isSaving = true, failure = null, nameProblem = null, camerasMissing = false) }
        viewModelScope.launch {
            try {
                val session = session() ?: return@launch
                // Leaving the screen must not stop between the change and moving the settings that follow it.
                withContext(NonCancellable) {
                    var failure = adminFailureOf { send(session) }
                    if (failure != null && isInPlace(session, isApplied)) {
                        failure = null
                        session.refresh()
                    }
                    if (failure == null) onApplied()
                    mutableState.update { it.copy(failure = failure, isDone = failure == null) }
                }
            } finally {
                mutableState.update { it.copy(isSaving = false) }
            }
        }
    }

    private suspend fun isInPlace(
        session: ServerSession,
        isApplied: (AdminConfig) -> Boolean,
    ): Boolean =
        try {
            isApplied(session.adminConfig())
        } catch (_: FrigateError) {
            false
        }

    private suspend fun session(): ServerSession? {
        registry.load()
        // Asked for each time: editing the server replaces its session.
        val session = registry.session(args.serverId)
        if (session == null) mutableState.update { it.copy(isLoading = false, isGone = true) }
        return session
    }
}
