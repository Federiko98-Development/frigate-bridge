package it.federicoraponi.frigatebridge.ui.access

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import it.federicoraponi.frigatebridge.R
import it.federicoraponi.frigatebridge.server.AccessRules
import it.federicoraponi.frigatebridge.server.CustomRole
import it.federicoraponi.frigatebridge.server.NameProblem
import it.federicoraponi.frigatebridge.server.PasswordProblem
import it.federicoraponi.frigatebridge.ui.AccessAction
import it.federicoraponi.frigatebridge.ui.AccessResult
import it.federicoraponi.frigatebridge.ui.AccessState
import it.federicoraponi.frigatebridge.ui.designsystem.ConfirmSheet
import it.federicoraponi.frigatebridge.ui.designsystem.ConnectedChoice
import it.federicoraponi.frigatebridge.ui.designsystem.FormField
import it.federicoraponi.frigatebridge.ui.designsystem.GroupHint
import it.federicoraponi.frigatebridge.ui.designsystem.ListGroup
import it.federicoraponi.frigatebridge.ui.designsystem.LockableSheet
import it.federicoraponi.frigatebridge.ui.designsystem.NoticeCard
import it.federicoraponi.frigatebridge.ui.designsystem.PasswordField
import it.federicoraponi.frigatebridge.ui.designsystem.PrimaryButton
import it.federicoraponi.frigatebridge.ui.designsystem.SheetTitle
import it.federicoraponi.frigatebridge.ui.designsystem.Spacing
import it.federicoraponi.frigatebridge.ui.designsystem.adminFailureText

/** With more roles than this, they are chosen from a list instead of a row of buttons. */
private const val MAX_CONNECTED_ROLES = 3

/** The sheet that is open on the users and roles screen. */
internal sealed interface AccessSheet {
    data object AddUser : AccessSheet

    data class ChangeRole(
        val username: String,
        val role: String,
    ) : AccessSheet

    data class SetPassword(
        val username: String,
    ) : AccessSheet

    data class DeleteUser(
        val username: String,
    ) : AccessSheet

    data object AddRole : AccessSheet

    data class EditRole(
        val role: CustomRole,
    ) : AccessSheet

    data class DeleteRole(
        val name: String,
    ) : AccessSheet
}

/** Whether [action] is the one this sheet asks for: the state of another action says nothing about it. */
internal fun AccessSheet.asked(action: AccessAction): Boolean =
    when (this) {
        AccessSheet.AddUser -> action is AccessAction.CreateUser
        is AccessSheet.ChangeRole -> action == AccessAction.ChangeRole(username)
        is AccessSheet.SetPassword -> action == AccessAction.SetPassword(username)
        is AccessSheet.DeleteUser -> action == AccessAction.DeleteUser(username)
        AccessSheet.AddRole -> action is AccessAction.SaveRole && action.isNew
        is AccessSheet.EditRole -> action == AccessAction.SaveRole(role.name, isNew = false)
        is AccessSheet.DeleteRole -> action == AccessAction.DeleteRole(name)
    }

/** What the state says about the action of the open sheet. */
private data class SheetOutcome(
    val isRunning: Boolean,
    val invalid: AccessResult.Invalid?,
    val failure: String?,
)

@Composable
internal fun AccessSheetContent(
    sheet: AccessSheet,
    state: AccessState,
    actions: AccessActions,
    onOpen: (AccessSheet) -> Unit,
    onClose: () -> Unit,
) {
    val result = state.result?.takeIf { sheet.asked(it.action) }
    val outcome =
        SheetOutcome(
            isRunning = state.running?.let { sheet.asked(it) } == true,
            invalid = result as? AccessResult.Invalid,
            failure = (result as? AccessResult.Failed)?.let { adminFailureText(it.failure) },
        )
    // A sheet that takes the place of another starts afresh.
    key(sheet) {
        when (sheet) {
            AccessSheet.AddUser -> AddUserSheet(state.roleChoices, outcome, actions.createUser, onClose)
            is AccessSheet.ChangeRole ->
                ChangeRoleSheet(sheet, state.roleChoices, outcome, actions.changeRole, onClose)
            is AccessSheet.SetPassword -> SetPasswordSheet(sheet.username, outcome, actions.setPassword, onClose)
            is AccessSheet.DeleteUser ->
                ConfirmSheet(
                    title = stringResource(R.string.user_delete_title, sheet.username),
                    message = stringResource(R.string.user_delete_message),
                    confirmLabel = stringResource(R.string.user_delete),
                    onConfirm = { actions.deleteUser(sheet.username) },
                    onDismiss = onClose,
                    running = outcome.isRunning,
                    error = outcome.failure,
                )
            AccessSheet.AddRole -> RoleSheet(null, state, outcome, actions.saveRole, onDelete = {}, onClose)
            is AccessSheet.EditRole ->
                RoleSheet(
                    role = sheet.role,
                    state = state,
                    outcome = outcome,
                    onSave = actions.saveRole,
                    onDelete = { onOpen(AccessSheet.DeleteRole(sheet.role.name)) },
                    onClose = onClose,
                )
            is AccessSheet.DeleteRole -> {
                val holders = state.users.count { it.role == sheet.name }
                ConfirmSheet(
                    title = stringResource(R.string.role_delete_title, sheet.name),
                    message =
                        if (holders == 0) {
                            stringResource(R.string.role_delete_unused)
                        } else {
                            pluralStringResource(R.plurals.role_delete_users, holders, holders)
                        },
                    confirmLabel = stringResource(R.string.user_delete),
                    onConfirm = { actions.deleteRole(sheet.name) },
                    onDismiss = onClose,
                    running = outcome.isRunning,
                    error = outcome.failure,
                )
            }
        }
    }
}

@Composable
private fun AddUserSheet(
    roles: List<String>,
    outcome: SheetOutcome,
    onCreate: (String, String, String, String) -> Unit,
    onClose: () -> Unit,
) {
    var username by remember { mutableStateOf("") }
    // Not saveable on purpose, like the fields themselves.
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("viewer") }
    val scroll = rememberScrollState()
    ScrollToError(scroll, outcome.invalid)
    FormSheet(
        title = stringResource(R.string.user_add),
        submitLabel = stringResource(R.string.user_add),
        outcome = outcome,
        onSubmit = { onCreate(username.trim(), password, confirmation, role) },
        onClose = onClose,
        scroll = scroll,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                FormField(
                    containerColor = sheetFieldColor(),
                    value = username,
                    onValueChange = { username = it },
                    label = R.string.form_username,
                    index = 0,
                    count = 3,
                    keyboard = nameKeyboard(ImeAction.Next),
                    error = outcome.invalid?.name?.let { userNameError(it) },
                )
                NewPasswordFields(
                    firstIndex = 1,
                    count = 3,
                    problem = outcome.invalid?.password,
                    onPassword = { password = it },
                    onConfirmation = { confirmation = it },
                )
            }
            GroupHint(stringResource(R.string.password_hint, AccessRules.MIN_PASSWORD))
        }
        RolePicker(roles, role, onSelect = { role = it })
    }
}

@Composable
private fun ChangeRoleSheet(
    sheet: AccessSheet.ChangeRole,
    roles: List<String>,
    outcome: SheetOutcome,
    onChange: (String, String) -> Unit,
    onClose: () -> Unit,
) {
    var role by remember { mutableStateOf(sheet.role) }
    FormSheet(
        title = stringResource(R.string.user_change_role_title, sheet.username),
        submitLabel = stringResource(R.string.user_change_role),
        outcome = outcome,
        onSubmit = { onChange(sheet.username, role) },
        onClose = onClose,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            RolePicker(roles, role, onSelect = { role = it })
            GroupHint(stringResource(R.string.user_change_role_hint))
        }
    }
}

@Composable
private fun SetPasswordSheet(
    username: String,
    outcome: SheetOutcome,
    onSet: (String, String, String) -> Unit,
    onClose: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    FormSheet(
        title = stringResource(R.string.user_set_password_title, username),
        submitLabel = stringResource(R.string.user_set_password),
        outcome = outcome,
        onSubmit = { onSet(username, password, confirmation) },
        onClose = onClose,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                NewPasswordFields(
                    firstIndex = 0,
                    count = 2,
                    problem = outcome.invalid?.password,
                    onPassword = { password = it },
                    onConfirmation = { confirmation = it },
                )
            }
            GroupHint(stringResource(R.string.password_hint, AccessRules.MIN_PASSWORD))
            GroupHint(stringResource(R.string.user_set_password_hint))
        }
    }
}

/** Creates a role, or changes the cameras of [role]. */
@Composable
private fun RoleSheet(
    role: CustomRole?,
    state: AccessState,
    outcome: SheetOutcome,
    onSave: (String, Set<String>, Boolean) -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    var name by remember { mutableStateOf(role?.name.orEmpty()) }
    var chosen by remember { mutableStateOf(role?.cameras.orEmpty().toSet()) }
    val scroll = rememberScrollState()
    ScrollToError(scroll, outcome.invalid)
    FormSheet(
        title =
            if (role == null) {
                stringResource(R.string.role_add)
            } else {
                stringResource(R.string.role_edit_title, role.name)
            },
        submitLabel = stringResource(R.string.role_save),
        outcome = outcome,
        onSubmit = { onSave(name.trim(), chosen, role == null) },
        onClose = onClose,
        scroll = scroll,
        error = if (outcome.invalid?.camerasMissing == true) stringResource(R.string.role_cameras_missing) else null,
        destructiveLabel = role?.let { stringResource(R.string.role_delete) },
        onDestructive = onDelete,
    ) {
        if (role == null) {
            FormField(
                containerColor = sheetFieldColor(),
                value = name,
                onValueChange = { name = it },
                label = R.string.role_name,
                index = 0,
                count = 1,
                keyboard = nameKeyboard(ImeAction.Done),
                error = outcome.invalid?.name?.let { roleNameError(it) },
            )
        }
        ListGroup(title = stringResource(R.string.settings_cameras)) {
            state.cameras.forEachIndexed { index, camera ->
                val isChosen = camera.name in chosen
                SegmentedListItem(
                    checked = isChosen,
                    onCheckedChange = { chosen = if (it) chosen + camera.name else chosen - camera.name },
                    shapes = ListItemDefaults.segmentedShapes(index = index, count = state.cameras.size),
                    colors = choiceColors(),
                    supportingContent =
                        if (camera.name == camera.displayName) {
                            null
                        } else {
                            { Text(camera.name, style = MaterialTheme.typography.bodySmall) }
                        },
                    // The whole row toggles; the box only shows the state.
                    trailingContent = { Checkbox(checked = isChosen, onCheckedChange = null) },
                ) { Text(camera.displayName, style = MaterialTheme.typography.titleSmall) }
            }
        }
    }
}

/**
 * A sheet with fields and the button that sends them. It scrolls as a whole, so that everything stays reachable
 * above the keyboard, and stays open while its action runs.
 */
@Composable
private fun FormSheet(
    title: String,
    submitLabel: String,
    outcome: SheetOutcome,
    onSubmit: () -> Unit,
    onClose: () -> Unit,
    scroll: ScrollState = rememberScrollState(),
    /** What is wrong with the whole form, said next to the button. */
    error: String? = null,
    destructiveLabel: String? = null,
    onDestructive: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    LockableSheet(locked = outcome.isRunning, onDismiss = onClose) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            SheetTitle(title, modifier = Modifier.padding(horizontal = Spacing.sm))
            content()
            if (error != null) NoticeCard(error, announce = true)
            outcome.failure?.let { NoticeCard(it, announce = true) }
            if (outcome.isRunning) {
                ContainedLoadingIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    PrimaryButton(text = submitLabel, onClick = onSubmit, modifier = Modifier.fillMaxWidth())
                    TextButton(
                        onClick = onClose,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                    if (destructiveLabel != null) {
                        TextButton(
                            onClick = onDestructive,
                            shapes = ButtonDefaults.shapes(),
                            colors =
                                ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(destructiveLabel) }
                    }
                }
            }
        }
    }
}

/** The name is the first field: shows it when it is what is wrong. */
@Composable
private fun ScrollToError(
    scroll: ScrollState,
    invalid: AccessResult.Invalid?,
) {
    LaunchedEffect(invalid) {
        if (invalid?.name != null) scroll.animateScrollTo(0)
    }
}

@Composable
private fun NewPasswordFields(
    firstIndex: Int,
    count: Int,
    problem: PasswordProblem?,
    onPassword: (String) -> Unit,
    onConfirmation: (String) -> Unit,
) {
    PasswordField(
        containerColor = sheetFieldColor(),
        label = R.string.form_password,
        index = firstIndex,
        count = count,
        onValueChange = onPassword,
        error =
            if (problem == PasswordProblem.TooShort) {
                stringResource(R.string.password_too_short, AccessRules.MIN_PASSWORD)
            } else {
                null
            },
    )
    PasswordField(
        containerColor = sheetFieldColor(),
        label = R.string.password_confirm,
        index = firstIndex + 1,
        count = count,
        onValueChange = onConfirmation,
        error = if (problem == PasswordProblem.Mismatch) stringResource(R.string.password_mismatch) else null,
    )
}

@Composable
private fun RolePicker(
    roles: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val labels = roles.map { roleLabel(it) }
    ListGroup(title = stringResource(R.string.user_role)) {
        if (roles.size <= MAX_CONNECTED_ROLES) {
            ConnectedChoice(
                labels = labels,
                selected = roles.indexOf(selected),
                onSelect = { onSelect(roles[it]) },
            )
        } else {
            Column(
                modifier = Modifier.selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                roles.forEachIndexed { index, role ->
                    SegmentedListItem(
                        selected = role == selected,
                        onClick = { onSelect(role) },
                        shapes = ListItemDefaults.segmentedShapes(index = index, count = roles.size),
                        colors = choiceColors(),
                        modifier = Modifier.semantics { this.role = Role.RadioButton },
                        trailingContent = { RadioButton(selected = role == selected, onClick = null) },
                    ) { Text(labels[index], style = MaterialTheme.typography.titleSmall) }
                }
            }
        }
    }
}

// A step above the sheet, so that every row shows its shape. The button or box shows the state, so a chosen row
// keeps the colours of the others.
@Composable
private fun choiceColors(): ListItemColors =
    ListItemDefaults.segmentedColors(
        containerColor = sheetFieldColor(),
        selectedContainerColor = sheetFieldColor(),
        selectedContentColor = MaterialTheme.colorScheme.onSurface,
        selectedSupportingContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )

@Composable
private fun sheetFieldColor(): Color = MaterialTheme.colorScheme.surfaceContainerHighest

private fun nameKeyboard(action: ImeAction) =
    KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false, imeAction = action)

@Composable
private fun userNameError(problem: NameProblem): String =
    when (problem) {
        NameProblem.Missing, NameProblem.TooShort, NameProblem.NeedsLetter ->
            stringResource(R.string.user_name_missing)
        NameProblem.TooLong -> stringResource(R.string.user_name_too_long, AccessRules.MAX_USERNAME)
        NameProblem.InvalidCharacters -> stringResource(R.string.user_name_characters)
        NameProblem.Reserved -> stringResource(R.string.user_name_reserved)
        NameProblem.Taken -> stringResource(R.string.user_name_taken)
    }

@Composable
private fun roleNameError(problem: NameProblem): String =
    when (problem) {
        NameProblem.Missing, NameProblem.TooShort -> stringResource(R.string.role_name_missing)
        NameProblem.TooLong -> stringResource(R.string.role_name_too_long, AccessRules.MAX_ROLE)
        NameProblem.InvalidCharacters -> stringResource(R.string.role_name_characters)
        NameProblem.NeedsLetter -> stringResource(R.string.role_name_letter)
        NameProblem.Reserved -> stringResource(R.string.role_name_reserved)
        NameProblem.Taken -> stringResource(R.string.role_name_taken)
    }
