package it.federicoraponi.frigatebridge.ui.access

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.federicoraponi.frigatebridge.R
import it.federicoraponi.frigatebridge.camera.CameraOption
import it.federicoraponi.frigatebridge.server.CustomRole
import it.federicoraponi.frigatebridge.ui.AccessAction
import it.federicoraponi.frigatebridge.ui.AccessResult
import it.federicoraponi.frigatebridge.ui.AccessState
import it.federicoraponi.frigatebridge.ui.AccessViewModel
import it.federicoraponi.frigatebridge.ui.UserRow
import it.federicoraponi.frigatebridge.ui.designsystem.BackButton
import it.federicoraponi.frigatebridge.ui.designsystem.ExpressiveMenu
import it.federicoraponi.frigatebridge.ui.designsystem.GroupHint
import it.federicoraponi.frigatebridge.ui.designsystem.ListGroup
import it.federicoraponi.frigatebridge.ui.designsystem.LoadingContent
import it.federicoraponi.frigatebridge.ui.designsystem.MenuEntry
import it.federicoraponi.frigatebridge.ui.designsystem.NoticeCard
import it.federicoraponi.frigatebridge.ui.designsystem.RefreshableContent
import it.federicoraponi.frigatebridge.ui.designsystem.ScreenTitle
import it.federicoraponi.frigatebridge.ui.designsystem.ShapedIcon
import it.federicoraponi.frigatebridge.ui.designsystem.Spacing
import it.federicoraponi.frigatebridge.ui.designsystem.Symbols
import it.federicoraponi.frigatebridge.ui.designsystem.adminFailureText
import it.federicoraponi.frigatebridge.ui.designsystem.failureText
import it.federicoraponi.frigatebridge.ui.theme.FrigateBridgeTheme
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/** The users and roles of the active server. */
@Composable
fun AccessRoute(
    onClose: () -> Unit,
    onChangePassword: () -> Unit,
    viewModel: AccessViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.isGone) {
        if (state.isGone) onClose()
    }
    AccessScreen(
        state = state,
        actions =
            AccessActions(
                close = onClose,
                refresh = viewModel::refresh,
                createUser = viewModel::createUser,
                changeRole = viewModel::changeRole,
                setPassword = viewModel::setPassword,
                deleteUser = viewModel::deleteUser,
                saveRole = viewModel::saveRole,
                deleteRole = viewModel::deleteRole,
                dismissResult = viewModel::dismissResult,
                changeOwnPassword = onChangePassword,
            ),
    )
}

/** What the users and roles screen can ask for. */
data class AccessActions(
    val close: () -> Unit = {},
    val refresh: () -> Unit = {},
    /** User name, password, its confirmation and role. */
    val createUser: (String, String, String, String) -> Unit = { _, _, _, _ -> },
    val changeRole: (String, String) -> Unit = { _, _ -> },
    /** User name, password and its confirmation. */
    val setPassword: (String, String, String) -> Unit = { _, _, _ -> },
    val deleteUser: (String) -> Unit = {},
    /** Name, cameras, and whether the role is new. */
    val saveRole: (String, Set<String>, Boolean) -> Unit = { _, _, _ -> },
    val deleteRole: (String) -> Unit = {},
    val dismissResult: () -> Unit = {},
    val changeOwnPassword: () -> Unit = {},
)

@Composable
fun AccessScreen(
    state: AccessState,
    actions: AccessActions,
    modifier: Modifier = Modifier,
) {
    // Not saveable: a sheet may hold a password that is being typed.
    var sheet by remember { mutableStateOf<AccessSheet?>(null) }
    val open: (AccessSheet) -> Unit = {
        // While an action runs, no other can be asked for.
        if (state.running == null) {
            if (state.result != null) actions.dismissResult()
            sheet = it
        }
    }
    val close = {
        sheet = null
        if (state.result != null) actions.dismissResult()
    }
    val messages = remember { SnackbarHostState() }
    ResultEffect(state.result, sheet, messages, onClose = { sheet = null }, onSeen = actions.dismissResult)

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(messages) },
        topBar = {
            TopAppBar(
                title = { ScreenTitle(stringResource(R.string.access_title)) },
                navigationIcon = { BackButton(actions.close) },
            )
        },
        floatingActionButton = {
            // With sign-in there is always a user, so an empty list is one that was not read.
            if (state.authEnabled && state.users.isNotEmpty()) {
                val label = stringResource(R.string.user_add)
                SmallExtendedFloatingActionButton(
                    onClick = { open(AccessSheet.AddUser) },
                    // The button hides its text from accessibility services, so the icon carries the label.
                    icon = { Icon(Symbols.Add, contentDescription = label) },
                    text = { Text(label) },
                )
            }
        },
    ) { innerPadding ->
        RefreshableContent(
            isRefreshing = state.isRefreshing,
            onRefresh = actions.refresh,
            modifier = Modifier.padding(innerPadding),
        ) {
            if (state.isLoading) {
                LoadingContent()
            } else {
                AccessLists(state, actions, open)
            }
        }
    }
    sheet?.let { AccessSheetContent(it, state, actions, onOpen = open, onClose = close) }
}

/**
 * Says what was done and closes the sheet that asked for it. A failure stays in its sheet; one without a sheet,
 * e.g. after the screen was recreated, is said in a message.
 */
@Composable
private fun ResultEffect(
    result: AccessResult?,
    sheet: AccessSheet?,
    messages: SnackbarHostState,
    onClose: () -> Unit,
    onSeen: () -> Unit,
) {
    val text =
        when (result) {
            is AccessResult.Done -> doneText(result.action)
            is AccessResult.Failed -> adminFailureText(result.failure)
            is AccessResult.Invalid, null -> null
        }
    // Outlives the effect, which starts again as soon as the result is dismissed.
    val scope = rememberCoroutineScope()
    LaunchedEffect(result) {
        if (result == null) return@LaunchedEffect
        val isOwn = sheet?.asked(result.action) == true
        if (isOwn && result !is AccessResult.Done) return@LaunchedEffect
        if (isOwn) onClose()
        onSeen()
        if (text != null) scope.launch { messages.showSnackbar(text) }
    }
}

@Composable
private fun doneText(action: AccessAction): String =
    when (action) {
        is AccessAction.CreateUser -> stringResource(R.string.user_created, action.username)
        is AccessAction.ChangeRole -> stringResource(R.string.user_role_changed, action.username)
        is AccessAction.SetPassword -> stringResource(R.string.user_password_set, action.username)
        is AccessAction.DeleteUser -> stringResource(R.string.user_deleted, action.username)
        is AccessAction.SaveRole -> stringResource(R.string.role_saved, action.name)
        is AccessAction.DeleteRole -> stringResource(R.string.role_deleted, action.name)
    }

@Composable
private fun AccessLists(
    state: AccessState,
    actions: AccessActions,
    onOpen: (AccessSheet) -> Unit,
) {
    // After a failed first reading there is nothing to list.
    val hasLists = state.users.isNotEmpty() || state.roles.isNotEmpty() || state.cameras.isNotEmpty()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // Room for the floating button below the last item.
        contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = Spacing.sm, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        state.failure?.let { failure ->
            item(key = "failure") {
                NoticeCard(
                    message = failureText(failure, R.string.access_failed),
                    actionLabel = stringResource(R.string.retry),
                    onAction = actions.refresh,
                    announce = true,
                )
            }
        }
        if (state.failure == null || hasLists) {
            if (state.authEnabled) {
                item(key = "users") { Users(state.users, actions.changeOwnPassword, onOpen) }
            }
            item(key = "roles") { Roles(state, onOpen) }
        }
    }
}

@Composable
private fun Users(
    users: List<UserRow>,
    onChangeOwnPassword: () -> Unit,
    onOpen: (AccessSheet) -> Unit,
) {
    ListGroup(title = stringResource(R.string.access_users)) {
        if (users.isEmpty()) GroupHint(stringResource(R.string.access_no_users))
        users.forEachIndexed { index, user ->
            val isAdmin = user.role == "admin"
            SegmentedListItem(
                shapes = ListItemDefaults.segmentedShapes(index = index, count = users.size),
                colors = rowColors(),
                leadingContent = {
                    ShapedIcon(if (isAdmin) Symbols.Lock else Symbols.Person, size = 40.dp)
                },
                supportingContent = { Text(roleLabel(user.role), style = MaterialTheme.typography.bodySmall) },
                trailingContent = { UserMenu(user, onChangeOwnPassword, onOpen) },
            ) {
                Text(
                    if (user.isSelf) stringResource(R.string.user_self, user.username) else user.username,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
    }
}

@Composable
private fun UserMenu(
    user: UserRow,
    onChangeOwnPassword: () -> Unit,
    onOpen: (AccessSheet) -> Unit,
) {
    var isOpen by remember { mutableStateOf(false) }
    val changeRole = stringResource(R.string.user_change_role)
    val password =
        stringResource(if (user.isSelf) R.string.user_change_own_password else R.string.user_set_password)
    val delete = stringResource(R.string.user_delete)
    val entries =
        listOfNotNull(
            MenuEntry(
                label = changeRole,
                onClick = { onOpen(AccessSheet.ChangeRole(user.username, user.role)) },
                leading = { Icon(Symbols.Edit, contentDescription = null) },
            ).takeIf { user.canChangeRole },
            MenuEntry(
                label = password,
                onClick = {
                    if (user.isSelf) onChangeOwnPassword() else onOpen(AccessSheet.SetPassword(user.username))
                },
                leading = { Icon(Symbols.Lock, contentDescription = null) },
            ),
            MenuEntry(
                label = delete,
                onClick = { onOpen(AccessSheet.DeleteUser(user.username)) },
                leading = { Icon(Symbols.Delete, contentDescription = null) },
            ).takeIf { user.canDelete },
        )
    Box {
        IconButton(onClick = { isOpen = true }, shapes = IconButtonDefaults.shapes()) {
            Icon(
                Symbols.MoreVert,
                contentDescription = stringResource(R.string.user_options, user.username),
            )
        }
        ExpressiveMenu(expanded = isOpen, onDismiss = { isOpen = false }, entries = entries)
    }
}

/** The viewer role, which cannot change, the custom ones, and the way to add one. */
@Composable
private fun Roles(
    state: AccessState,
    onOpen: (AccessSheet) -> Unit,
) {
    val count = state.roles.size + 2
    val cameraNames = state.cameras.associate { it.name to it.displayName }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (!state.authEnabled) GroupHint(stringResource(R.string.access_roles_proxy))
        ListGroup(title = stringResource(R.string.access_roles)) {
            SegmentedListItem(
                shapes = ListItemDefaults.segmentedShapes(index = 0, count = count),
                colors = rowColors(),
                supportingContent = {
                    Text(stringResource(R.string.role_viewer_hint), style = MaterialTheme.typography.bodySmall)
                },
            ) { Text(roleLabel("viewer"), style = MaterialTheme.typography.titleSmall) }
            state.roles.forEachIndexed { index, role ->
                SegmentedListItem(
                    onClick = { onOpen(AccessSheet.EditRole(role)) },
                    shapes = ListItemDefaults.segmentedShapes(index = index + 1, count = count),
                    colors = rowColors(),
                    supportingContent = {
                        Text(
                            stringResource(
                                R.string.role_summary,
                                pluralStringResource(R.plurals.role_cameras, role.cameras.size, role.cameras.size),
                                role.cameras.joinToString { cameraNames[it] ?: it },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                ) { Text(role.name, style = MaterialTheme.typography.titleSmall) }
            }
            SegmentedListItem(
                onClick = { onOpen(AccessSheet.AddRole) },
                shapes = ListItemDefaults.segmentedShapes(index = count - 1, count = count),
                colors = rowColors(),
                leadingContent = { Icon(Symbols.Add, contentDescription = null) },
            ) { Text(stringResource(R.string.role_add), style = MaterialTheme.typography.titleSmall) }
        }
        GroupHint(stringResource(R.string.access_roles_hint))
    }
}

@Composable
private fun rowColors(): ListItemColors =
    ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)

/** The built-in roles have a name of their own; custom ones are shown as they are. */
@Composable
internal fun roleLabel(role: String): String =
    when (role) {
        "admin" -> stringResource(R.string.role_admin)
        "viewer" -> stringResource(R.string.role_viewer)
        else -> role
    }

@Preview
@Composable
private fun AccessScreenPreview() {
    FrigateBridgeTheme {
        AccessScreen(
            state =
                AccessState(
                    serverName = "Home",
                    isLoading = false,
                    users =
                        listOf(
                            UserRow("admin", "admin", isSelf = true, canChangeRole = false, canDelete = false),
                            UserRow("alice", "viewer", isSelf = false, canChangeRole = true, canDelete = true),
                            UserRow("bob", "garden_only", isSelf = false, canChangeRole = true, canDelete = true),
                        ),
                    roles = listOf(CustomRole("garden_only", listOf("garden"))),
                    cameras = listOf(CameraOption("front_door", "Front door"), CameraOption("garden", "Garden")),
                ),
            actions = AccessActions(),
        )
    }
}
