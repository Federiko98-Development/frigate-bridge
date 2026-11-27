package it.federicoraponi.frigatebridge.ui.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.federicoraponi.frigatebridge.R
import it.federicoraponi.frigatebridge.camera.CameraOption
import it.federicoraponi.frigatebridge.camera.GroupRules
import it.federicoraponi.frigatebridge.server.NameProblem
import it.federicoraponi.frigatebridge.ui.CameraGroupEditorArgs
import it.federicoraponi.frigatebridge.ui.CameraGroupEditorState
import it.federicoraponi.frigatebridge.ui.CameraGroupEditorViewModel
import it.federicoraponi.frigatebridge.ui.designsystem.BackButton
import it.federicoraponi.frigatebridge.ui.designsystem.ConfirmSheet
import it.federicoraponi.frigatebridge.ui.designsystem.FormField
import it.federicoraponi.frigatebridge.ui.designsystem.GroupHint
import it.federicoraponi.frigatebridge.ui.designsystem.ListGroup
import it.federicoraponi.frigatebridge.ui.designsystem.LoadingContent
import it.federicoraponi.frigatebridge.ui.designsystem.NoticeCard
import it.federicoraponi.frigatebridge.ui.designsystem.ScreenTitle
import it.federicoraponi.frigatebridge.ui.designsystem.ShapedIcon
import it.federicoraponi.frigatebridge.ui.designsystem.SheetTitle
import it.federicoraponi.frigatebridge.ui.designsystem.Spacing
import it.federicoraponi.frigatebridge.ui.designsystem.adminFailureText
import it.federicoraponi.frigatebridge.ui.designsystem.failureText
import it.federicoraponi.frigatebridge.ui.designsystem.rememberFullSheetState
import it.federicoraponi.frigatebridge.ui.theme.FrigateBridgeTheme
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/** Creates a group on the server, or changes the one called [groupName]. */
@Composable
fun CameraGroupEditRoute(
    serverId: String,
    groupName: String?,
    onClose: () -> Unit,
    // Keyed by the group: opening another one in the same place must not find the editor of the first.
    viewModel: CameraGroupEditorViewModel =
        koinViewModel(key = "group/$serverId/${groupName.orEmpty()}") {
            parametersOf(CameraGroupEditorArgs(serverId, groupName))
        },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.isDone, state.isGone) {
        if (state.isDone || state.isGone) onClose()
    }
    CameraGroupEditScreen(
        state = state,
        // The name the group had: the one in the state changes as it is typed.
        originalName = groupName,
        actions =
            CameraGroupEditActions(
                close = onClose,
                setName = viewModel::setName,
                setIcon = viewModel::setIcon,
                toggle = viewModel::toggle,
                save = viewModel::save,
                delete = viewModel::delete,
                dismissFailure = viewModel::dismissFailure,
            ),
    )
}

/** What the editor of a camera group can ask for. */
data class CameraGroupEditActions(
    val close: () -> Unit = {},
    val setName: (String) -> Unit = {},
    val setIcon: (String) -> Unit = {},
    val toggle: (String) -> Unit = {},
    val save: () -> Unit = {},
    val delete: () -> Unit = {},
    val dismissFailure: () -> Unit = {},
)

@Composable
fun CameraGroupEditScreen(
    state: CameraGroupEditorState,
    originalName: String?,
    actions: CameraGroupEditActions,
    modifier: Modifier = Modifier,
) {
    var isConfirmingDelete by remember { mutableStateOf(false) }
    val isReady = !state.isLoading && !state.isGone && state.loadFailure == null
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    ScreenTitle(
                        stringResource(if (state.isNew) R.string.group_new_title else R.string.group_edit_title),
                    )
                },
                navigationIcon = { BackButton(actions.close) },
                actions = {
                    if (state.isSaving) {
                        ContainedLoadingIndicator(modifier = Modifier.padding(end = Spacing.sm).size(40.dp))
                    } else if (isReady) {
                        TextButton(
                            onClick = actions.save,
                            enabled = !state.isDone,
                            shapes = ButtonDefaults.shapes(),
                        ) { Text(stringResource(R.string.group_save)) }
                    }
                },
            )
        },
    ) { innerPadding ->
        val loadFailure = state.loadFailure
        when {
            loadFailure != null ->
                NoticeCard(
                    title = stringResource(R.string.group_load_failed),
                    message = failureText(loadFailure, R.string.group_load_failed_hint),
                    modifier = Modifier.padding(innerPadding).padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                    announce = true,
                )
            !isReady -> LoadingContent(Modifier.padding(innerPadding))
            else ->
                GroupForm(
                    state = state,
                    actions = actions,
                    // While the sheet is open, it says what went wrong.
                    showFailure = !isConfirmingDelete,
                    onDelete = {
                        // A failure left from saving says nothing about deleting.
                        actions.dismissFailure()
                        isConfirmingDelete = true
                    },
                    modifier = Modifier.fillMaxSize().padding(innerPadding).imePadding(),
                )
        }
    }
    if (isConfirmingDelete && originalName != null && !state.isDone) {
        ConfirmSheet(
            title = stringResource(R.string.group_delete_title, originalName.replace('_', ' ')),
            message = stringResource(R.string.group_delete_message),
            confirmLabel = stringResource(R.string.group_delete_confirm),
            onConfirm = actions.delete,
            onDismiss = {
                isConfirmingDelete = false
                actions.dismissFailure()
            },
            running = state.isSaving,
            error = state.failure?.let { adminFailureText(it) },
        )
    }
}

@Composable
private fun GroupForm(
    state: CameraGroupEditorState,
    actions: CameraGroupEditActions,
    showFailure: Boolean,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    val cameras = remember { BringIntoViewRequester() }
    val failure = state.failure.takeIf { showFailure }
    // Save is in the top bar: what is wrong may be out of sight.
    LaunchedEffect(failure, state.nameProblem, state.camerasMissing) {
        if (failure != null || state.nameProblem != null) {
            scroll.animateScrollTo(0)
        } else if (state.camerasMissing) {
            cameras.bringIntoView()
        }
    }
    Column(
        modifier =
            modifier
                .verticalScroll(scroll)
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        failure?.let {
            NoticeCard(
                message = adminFailureText(it),
                actionLabel = stringResource(R.string.close),
                onAction = actions.dismissFailure,
                announce = true,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            ListGroup(title = stringResource(R.string.group_section_name)) {
                FormField(
                    value = state.name,
                    onValueChange = actions.setName,
                    label = R.string.group_name,
                    index = 0,
                    count = 1,
                    keyboard =
                        KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Done,
                        ),
                    error = state.nameProblem?.let { nameError(it) },
                    enabled = !state.isSaving,
                )
            }
            GroupHint(stringResource(R.string.group_name_hint))
        }
        IconPicker(state.icon, enabled = !state.isSaving, onSelect = actions.setIcon)
        Column(
            modifier = Modifier.bringIntoViewRequester(cameras),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            CameraChoices(state, actions.toggle)
            if (state.camerasMissing) {
                NoticeCard(stringResource(R.string.group_cameras_missing), announce = true)
            }
        }
        if (!state.isNew) {
            TextButton(
                onClick = onDelete,
                enabled = !state.isSaving,
                shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.group_delete)) }
        }
    }
}

/** The group's icon, chosen in a sheet: all of them on the page would push the cameras far below. */
@Composable
private fun IconPicker(
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var isChoosing by rememberSaveable { mutableStateOf(false) }
    ListGroup(title = stringResource(R.string.group_section_icon)) {
        SegmentedListItem(
            onClick = { isChoosing = true },
            enabled = enabled,
            shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
            colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            leadingContent = { ShapedIcon(groupIcon(selected), size = 40.dp) },
            supportingContent = {
                Text(stringResource(R.string.group_icon_change), style = MaterialTheme.typography.bodySmall)
            },
            verticalAlignment = Alignment.CenterVertically,
        ) { Text(stringResource(groupIconLabel(selected)), style = MaterialTheme.typography.titleSmall) }
    }
    if (isChoosing) {
        IconSheet(
            selected = selected,
            onSelect = {
                isChoosing = false
                onSelect(it)
            },
            onDismiss = { isChoosing = false },
        )
    }
}

/** Every icon the app offers, by section. A stored icon that is not among them stays until another is picked. */
@Composable
private fun IconSheet(
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberFullSheetState()) {
        SheetTitle(
            stringResource(R.string.group_section_icon),
            modifier = Modifier.padding(horizontal = Spacing.lg + Spacing.sm),
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            contentPadding = PaddingValues(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            items(GroupIconCategory.entries, key = { it.name }) { category ->
                ListGroup(title = stringResource(category.title)) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        FlowRow(
                            modifier = Modifier.padding(Spacing.sm),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.CenterHorizontally),
                            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                        ) {
                            category.icons.forEach { icon -> IconChoice(icon, icon == selected) { onSelect(icon) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IconChoice(
    icon: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    FilledIconToggleButton(
        checked = isSelected,
        onCheckedChange = { onClick() },
        shapes = IconButtonDefaults.toggleableShapes(),
        // A step above the card, so that every choice shows its shape.
        colors =
            IconButtonDefaults.filledIconToggleButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                checkedContainerColor = MaterialTheme.colorScheme.primary,
                checkedContentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        modifier = Modifier.semantics { role = Role.RadioButton },
    ) {
        Icon(groupIcon(icon), contentDescription = stringResource(groupIconLabel(icon)))
    }
}

@Composable
private fun CameraChoices(
    state: CameraGroupEditorState,
    onToggle: (String) -> Unit,
) {
    val container = MaterialTheme.colorScheme.surfaceContainer
    ListGroup(title = stringResource(R.string.settings_cameras)) {
        state.choices.forEachIndexed { index, choice ->
            val isChosen = choice.name in state.selected
            val isBirdseye = choice.name == GroupRules.BIRDSEYE
            SegmentedListItem(
                checked = isChosen,
                onCheckedChange = { onToggle(choice.name) },
                enabled = !state.isSaving,
                shapes = ListItemDefaults.segmentedShapes(index = index, count = state.choices.size),
                // The box shows the state, so a chosen row keeps the colours of the others.
                colors =
                    ListItemDefaults.segmentedColors(
                        containerColor = container,
                        selectedContainerColor = container,
                        selectedContentColor = MaterialTheme.colorScheme.onSurface,
                        selectedSupportingContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        disabledContainerColor = container,
                    ),
                supportingContent =
                    if (isBirdseye || choice.name == choice.displayName) {
                        null
                    } else {
                        { Text(choice.name, style = MaterialTheme.typography.bodySmall) }
                    },
                // The whole row toggles; the box only shows the state.
                trailingContent = {
                    Checkbox(checked = isChosen, onCheckedChange = null, enabled = !state.isSaving)
                },
            ) {
                Text(
                    if (isBirdseye) stringResource(R.string.group_birdseye) else choice.displayName,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
    }
}

@Composable
private fun nameError(problem: NameProblem): String =
    stringResource(
        when (problem) {
            NameProblem.Missing, NameProblem.TooShort -> R.string.group_name_too_short
            // Group names have no maximum length.
            NameProblem.TooLong, NameProblem.InvalidCharacters -> R.string.group_name_characters
            NameProblem.NeedsLetter -> R.string.group_name_letter
            NameProblem.Reserved -> R.string.group_name_reserved
            NameProblem.Taken -> R.string.group_name_taken
        },
    )

@Preview
@Composable
private fun CameraGroupEditScreenPreview() {
    FrigateBridgeTheme {
        CameraGroupEditScreen(
            state =
                CameraGroupEditorState(
                    isNew = false,
                    isLoading = false,
                    name = "Outside",
                    icon = "LuTrees",
                    choices =
                        listOf(
                            CameraOption("front_door", "front door"),
                            CameraOption("garage", "garage"),
                            CameraOption("garden", "garden"),
                        ),
                    selected = setOf("front_door", "garden"),
                ),
            originalName = "Outside",
            actions = CameraGroupEditActions(),
        )
    }
}

@Preview
@Composable
private fun IndoorGroupEditScreenPreview() {
    FrigateBridgeTheme {
        CameraGroupEditScreen(
            state =
                CameraGroupEditorState(
                    isNew = false,
                    isLoading = false,
                    name = "Indoor",
                    icon = "generic",
                    choices = listOf(CameraOption("garage", "garage")),
                    selected = setOf("garage"),
                ),
            originalName = "Indoor",
            actions = CameraGroupEditActions(),
        )
    }
}
