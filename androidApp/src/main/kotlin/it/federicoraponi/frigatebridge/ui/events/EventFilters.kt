package it.federicoraponi.frigatebridge.ui.events

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import it.federicoraponi.frigatebridge.R
import it.federicoraponi.frigatebridge.recordings.ReviewSeverity
import it.federicoraponi.frigatebridge.ui.EventsState
import it.federicoraponi.frigatebridge.ui.cameras.CameraGroupBar
import it.federicoraponi.frigatebridge.ui.designsystem.ExpressiveMenu
import it.federicoraponi.frigatebridge.ui.designsystem.MenuEntry
import it.federicoraponi.frigatebridge.ui.designsystem.Spacing
import it.federicoraponi.frigatebridge.ui.designsystem.Symbols
import it.federicoraponi.frigatebridge.ui.recordings.dayLabel
import it.federicoraponi.frigatebridge.ui.recordings.minuteLabel

/** The cameras, and below them one row with what to show, of what and of when. */
@Composable
internal fun Filters(
    state: EventsState,
    actions: EventsActions,
    onChooseMoment: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(bottom = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        if (state.choices.isNotEmpty()) {
            CameraGroupBar(
                choices = state.choices,
                selected = state.filter.group,
                onSelect = actions.setGroup,
                onShow = actions.showCameras,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SeverityChoice(state.filter.severity, actions.setSeverity)
            // The chosen label stays on offer even if the list it filtered no longer mentions others.
            val labels = (state.labels + listOfNotNull(state.filter.label)).distinct()
            if (labels.isNotEmpty()) LabelFilter(labels, state.filter.label, actions.setLabel)
            if (state.date.isNotEmpty()) DateFilter(state, actions.showToday, onChooseMoment)
        }
    }
}

/** Alerts or detections. Only the chosen one says its name, which keeps the row short. */
@Composable
private fun SeverityChoice(
    selected: ReviewSeverity,
    onSelect: (ReviewSeverity) -> Unit,
) {
    Row(
        modifier = Modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        ReviewSeverity.entries.forEachIndexed { index, severity ->
            val isAlert = severity == ReviewSeverity.Alert
            val name = stringResource(if (isAlert) R.string.events_alerts else R.string.events_detections)
            ToggleButton(
                checked = severity == selected,
                onCheckedChange = { onSelect(severity) },
                shapes =
                    if (index == 0) {
                        ButtonGroupDefaults.connectedLeadingButtonShapes()
                    } else {
                        ButtonGroupDefaults.connectedTrailingButtonShapes()
                    },
                colors =
                    ToggleButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                contentPadding = PaddingValues(horizontal = Spacing.md),
                modifier =
                    Modifier.semantics {
                        role = Role.RadioButton
                        contentDescription = name
                    },
            ) {
                Icon(
                    if (isAlert) Symbols.Warning else Symbols.MonitorHeart,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                AnimatedVisibility(visible = severity == selected) {
                    Row {
                        Spacer(Modifier.width(Spacing.xs))
                        Text(name, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** What was seen: an icon, with its name once one is chosen, opening the list of them. */
@Composable
private fun LabelFilter(
    labels: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    var isOpen by remember { mutableStateOf(false) }
    val all = stringResource(R.string.events_all_labels)
    val entries =
        (listOf<String?>(null) + labels).map { label ->
            val icon = labelIcon(label)
            MenuEntry(
                label = if (label == null) all else labelName(label),
                onClick = { onSelect(label) },
                leading = { Icon(icon, contentDescription = null) },
                trailing =
                    if (label == selected) {
                        { Icon(Symbols.Check, contentDescription = null) }
                    } else {
                        null
                    },
            )
        }
    val name = selected?.let { labelName(it) }
    val description = stringResource(R.string.events_object_filter, name ?: all)
    Box {
        FilledTonalButton(
            onClick = { isOpen = true },
            shapes = ButtonDefaults.shapes(),
            contentPadding = PaddingValues(horizontal = Spacing.md),
            modifier = Modifier.semantics { contentDescription = description },
        ) {
            Icon(labelIcon(selected), contentDescription = null, modifier = Modifier.size(20.dp))
            if (name != null) {
                Spacer(Modifier.width(Spacing.xs))
                Text(name, maxLines = 1)
            }
            Icon(Symbols.ArrowDropDown, contentDescription = null, modifier = Modifier.size(20.dp))
        }
        ExpressiveMenu(expanded = isOpen, onDismiss = { isOpen = false }, entries = entries)
    }
}

@Composable
private fun DateFilter(
    state: EventsState,
    onToday: () -> Unit,
    onChoose: () -> Unit,
) {
    val day = dayLabel(state.date)
    val from = state.filter.fromMinute?.let { stringResource(R.string.events_from, minuteLabel(it)) }
    val choose = stringResource(R.string.events_choose_moment)
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(
            onClick = onChoose,
            shapes = ButtonDefaults.shapes(),
            contentPadding = PaddingValues(horizontal = Spacing.md),
        ) {
            Icon(Symbols.DateRange, contentDescription = choose, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Spacing.xs))
            Text(listOfNotNull(day, from).joinToString(" · "), maxLines = 1)
        }
        if (state.filter.date != null || state.filter.fromMinute != null) {
            IconButton(onClick = onToday, shapes = IconButtonDefaults.shapes()) {
                Icon(Symbols.Close, contentDescription = stringResource(R.string.events_today_again))
            }
        }
    }
}

/** The icon of something Frigate recognises; `null` stands for everything. */
@Composable
private fun labelIcon(label: String?): ImageVector {
    val icon =
        when (label) {
            null -> R.drawable.symbol_category
            "person" -> R.drawable.symbol_person
            "car" -> R.drawable.symbol_directions_car
            "dog", "cat" -> R.drawable.symbol_pets
            "bicycle", "motorcycle" -> R.drawable.symbol_pedal_bike
            "truck" -> R.drawable.symbol_local_shipping
            "bus" -> R.drawable.symbol_directions_bus
            "bird" -> R.drawable.symbol_raven
            "package" -> R.drawable.symbol_inventory_2
            "on_demand" -> R.drawable.symbol_fiber_manual_record
            else -> R.drawable.symbol_label
        }
    return ImageVector.vectorResource(icon)
}
