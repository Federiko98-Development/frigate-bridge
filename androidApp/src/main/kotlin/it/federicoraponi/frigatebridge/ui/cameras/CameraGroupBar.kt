package it.federicoraponi.frigatebridge.ui.cameras

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import it.federicoraponi.frigatebridge.R
import it.federicoraponi.frigatebridge.camera.GroupChoice
import it.federicoraponi.frigatebridge.settings.GroupButtons
import it.federicoraponi.frigatebridge.ui.designsystem.PrimaryButton
import it.federicoraponi.frigatebridge.ui.designsystem.SheetTitle
import it.federicoraponi.frigatebridge.ui.designsystem.Spacing
import it.federicoraponi.frigatebridge.ui.designsystem.Symbols
import it.federicoraponi.frigatebridge.ui.designsystem.rememberFullSheetState
import it.federicoraponi.frigatebridge.ui.groups.knownGroupIcon

/** What the group buttons show, as chosen in the settings. */
val LocalGroupButtons = staticCompositionLocalOf { GroupButtons.Names }

@Composable
private fun groupName(name: String?) = name?.replace('_', ' ') ?: stringResource(R.string.cameras_all)

/**
 * "All cameras" and the server's groups as a connected button group. Holding one opens the list of its cameras to
 * switch each on or off. Without groups there is "all cameras" alone.
 */
@Composable
fun CameraGroupBar(
    choices: List<GroupChoice>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onShow: (group: String?, shown: Set<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The index, rather than the name, survives a rotation also for "all cameras".
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    val showsIcons = LocalGroupButtons.current == GroupButtons.Icons
    val choose = stringResource(R.string.cameras_choose)
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        choices.forEachIndexed { index, choice ->
            val count = "${choice.shown.size}/${choice.cameras.size}"
            val name = groupName(choice.name)
            // A group whose icon the app does not know keeps its name.
            val icon =
                when {
                    !showsIcons -> null
                    choice.name == null -> Symbols.Apps
                    else -> choice.icon?.let { knownGroupIcon(it) }
                }
            val shown =
                pluralStringResource(
                    R.plurals.cameras_shown_count,
                    choice.cameras.size,
                    choice.shown.size,
                    choice.cameras.size,
                )
            GroupButton(
                label =
                    when {
                        icon == null -> name + if (choice.isPartial) " · $count" else ""
                        choice.isPartial -> count
                        else -> null
                    },
                icon = icon,
                // "3/5" read aloud says less than "3 of 5 cameras shown".
                description = if (choice.isPartial) "$name, $shown" else name,
                isSelected = choice.name == selected,
                index = index,
                count = choices.size,
                longClickLabel = choose,
                onClick = { onSelect(choice.name) },
                onLongClick = { editing = index },
            )
        }
    }
    editing?.let { index ->
        choices.getOrNull(index)?.let { choice ->
            CameraSheet(
                choice = choice,
                onDismiss = { editing = null },
                onShow = {
                    editing = null
                    onShow(choice.name, it)
                },
            )
        }
    }
}

// A toggle button keeps its clicks to itself, so holding it could not be heard.
@Composable
private fun GroupButton(
    label: String?,
    icon: ImageVector?,
    description: String,
    isSelected: Boolean,
    index: Int,
    count: Int,
    longClickLabel: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val shapes =
        when {
            index == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
            index == count - 1 -> ButtonGroupDefaults.connectedTrailingButtonShapes()
            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
        }
    // On its own a button has nothing to connect to.
    val shape = if (isSelected || count == 1) shapes.checkedShape else shapes.shape
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = shape,
        color = if (isSelected) colors.primary else colors.surfaceContainerHighest,
        contentColor = if (isSelected) colors.onPrimary else colors.onSurfaceVariant,
        modifier =
            Modifier
                .minimumInteractiveComponentSize()
                .clip(shape)
                .combinedClickable(
                    role = Role.RadioButton,
                    onLongClickLabel = longClickLabel,
                    onLongClick = onLongClick,
                    onClick = onClick,
                ).semantics {
                    selected = isSelected
                    contentDescription = description
                },
    ) {
        Row(
            modifier = Modifier.heightIn(min = 40.dp).padding(horizontal = Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The description of the button says what the icon and the count stand for.
            icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(20.dp)) }
            label?.let {
                Text(it, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clearAndSetSemantics {})
            }
        }
    }
}

@Composable
private fun CameraSheet(
    choice: GroupChoice,
    onDismiss: () -> Unit,
    onShow: (Set<String>) -> Unit,
) {
    val names = choice.cameras.map { it.camera.ref.cameraName }
    var shown by remember(choice.name) {
        mutableStateOf(choice.shown.map { it.ref.cameraName }.toSet())
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberFullSheetState(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg).padding(bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SheetTitle(groupName(choice.name), modifier = Modifier.weight(1f).padding(start = Spacing.sm))
                TextButton(
                    onClick = { shown = names.toSet() },
                    enabled = !shown.containsAll(names),
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(R.string.cameras_select_all)) }
            }
            Column(
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                choice.cameras.forEachIndexed { index, entry ->
                    val name = entry.camera.ref.cameraName
                    val shape = ListItemDefaults.segmentedShapes(index = index, count = names.size).shape
                    Surface(
                        shape = shape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(shape)
                                .toggleable(value = name in shown, role = Role.Switch) {
                                    shown = if (it) shown + name else shown - name
                                },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                entry.camera.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(checked = name in shown, onCheckedChange = null)
                        }
                    }
                }
            }
            PrimaryButton(
                text = stringResource(R.string.cameras_show),
                onClick = { onShow(shown) },
                enabled = names.any { it in shown },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
