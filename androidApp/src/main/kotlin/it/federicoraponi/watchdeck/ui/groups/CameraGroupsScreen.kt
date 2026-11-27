package it.federicoraponi.watchdeck.ui.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.federicoraponi.watchdeck.R
import it.federicoraponi.watchdeck.ui.CameraGroupsState
import it.federicoraponi.watchdeck.ui.CameraGroupsViewModel
import it.federicoraponi.watchdeck.ui.GroupRow
import it.federicoraponi.watchdeck.ui.designsystem.BackButton
import it.federicoraponi.watchdeck.ui.designsystem.LoadingContent
import it.federicoraponi.watchdeck.ui.designsystem.MessageState
import it.federicoraponi.watchdeck.ui.designsystem.NoticeCard
import it.federicoraponi.watchdeck.ui.designsystem.RefreshableContent
import it.federicoraponi.watchdeck.ui.designsystem.ScreenTitle
import it.federicoraponi.watchdeck.ui.designsystem.ShapedIcon
import it.federicoraponi.watchdeck.ui.designsystem.Spacing
import it.federicoraponi.watchdeck.ui.designsystem.Symbols
import it.federicoraponi.watchdeck.ui.designsystem.adminFailureText
import it.federicoraponi.watchdeck.ui.designsystem.failureText
import it.federicoraponi.watchdeck.ui.theme.WatchdeckTheme
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** The camera groups of the active server. [onOpen] and [onAdd] get its id, [onOpen] also the group's name. */
@Composable
fun CameraGroupsRoute(
    onClose: () -> Unit,
    onOpen: (String, String) -> Unit,
    onAdd: (String) -> Unit,
    viewModel: CameraGroupsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.isGone) {
        if (state.isGone) onClose()
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.load()
        onPauseOrDispose {}
    }
    CameraGroupsScreen(
        state = state,
        actions =
            CameraGroupsActions(
                close = onClose,
                refresh = viewModel::refresh,
                open = { onOpen(state.serverId, it) },
                add = { onAdd(state.serverId) },
                reorder = viewModel::reorder,
                dismissFailure = viewModel::dismissFailure,
            ),
    )
}

/** What the list of camera groups can ask for. */
data class CameraGroupsActions(
    val close: () -> Unit = {},
    val refresh: () -> Unit = {},
    val open: (String) -> Unit = {},
    val add: () -> Unit = {},
    val reorder: (List<String>) -> Unit = {},
    val dismissFailure: () -> Unit = {},
)

@Composable
fun CameraGroupsScreen(
    state: CameraGroupsState,
    actions: CameraGroupsActions,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { ScreenTitle(stringResource(R.string.group_title)) },
                navigationIcon = { BackButton(actions.close) },
            )
        },
        floatingActionButton = {
            // A list that was never read is not one to add to.
            if (!state.isLoading && (state.failure == null || state.groups.isNotEmpty())) {
                val label = stringResource(R.string.group_add)
                SmallExtendedFloatingActionButton(
                    // While a change is sent the editor would start from the groups as they were before it.
                    onClick = { if (!state.running) actions.add() },
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
                GroupList(state, actions)
            }
        }
    }
}

@Composable
private fun GroupList(
    state: CameraGroupsState,
    actions: CameraGroupsActions,
) {
    val list = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    // The order on screen, which a drag changes before the server hears of it.
    var rows by remember(state.groups) { mutableStateOf(state.groups) }
    val reorderable =
        rememberReorderableLazyListState(list) { from, to ->
            // By key: notices above the groups shift the positions in the list.
            val fromIndex = rows.indexOfFirst { itemKey(it) == from.key }
            val toIndex = rows.indexOfFirst { itemKey(it) == to.key }
            if (fromIndex >= 0 && toIndex >= 0) {
                rows = rows.moved(fromIndex, toIndex)
                haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
            }
        }
    // The notice is at the top, which may be out of sight.
    LaunchedEffect(state.actionFailure) {
        if (state.actionFailure != null) list.animateScrollToItem(0)
    }
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize(),
        // Room for the floating button below the last item.
        contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = Spacing.sm, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        state.actionFailure?.let { failure ->
            item(key = "action failure", contentType = "notice") {
                NoticeCard(
                    message = adminFailureText(failure),
                    modifier = Modifier.padding(bottom = Spacing.md),
                    actionLabel = stringResource(R.string.close),
                    onAction = actions.dismissFailure,
                    announce = true,
                )
            }
        }
        state.failure?.let { failure ->
            item(key = "failure", contentType = "notice") {
                NoticeCard(
                    message = failureText(failure, R.string.group_list_failed),
                    modifier = Modifier.padding(bottom = Spacing.md),
                    actionLabel = stringResource(R.string.retry),
                    onAction = actions.refresh,
                    announce = true,
                )
            }
        }
        if (state.groups.isEmpty() && state.failure == null) {
            item(key = "empty", contentType = "empty") {
                MessageState(
                    icon = Symbols.GridView,
                    title = stringResource(R.string.group_empty),
                    message = stringResource(R.string.group_empty_hint),
                    modifier = Modifier.fillParentMaxHeight(0.8f),
                )
            }
        }
        itemsIndexed(rows, key = { _, row -> itemKey(row) }) { index, row ->
            ReorderableItem(reorderable, key = itemKey(row)) { isDragging ->
                GroupItem(
                    row = row,
                    index = index,
                    count = rows.size,
                    isDragging = isDragging,
                    running = state.running,
                    onOpen = { actions.open(row.name) },
                    onMove = { to -> actions.reorder(rows.moved(index, to).map { it.name }) },
                    handle =
                        Modifier.draggableHandle(
                            enabled = !state.running,
                            onDragStarted = {
                                haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                            },
                            onDragStopped = {
                                haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                // Does nothing if the group was put back where it was.
                                actions.reorder(rows.map { it.name })
                            },
                        ),
                )
            }
        }
    }
}

// Prefixed: a group may be called like one of the other items of the list.
private fun itemKey(row: GroupRow) = "group ${row.name}"

private fun <T> List<T>.moved(
    from: Int,
    to: Int,
): List<T> = toMutableList().apply { add(to, removeAt(from)) }

/** [handle] goes on the icon that drags the row. Screen readers move it with actions of the row instead. */
@Composable
private fun GroupItem(
    row: GroupRow,
    index: Int,
    count: Int,
    isDragging: Boolean,
    running: Boolean,
    onOpen: () -> Unit,
    onMove: (Int) -> Unit,
    handle: Modifier,
) {
    val name = row.name.replace('_', ' ')
    val birdseye = stringResource(R.string.group_birdseye)
    val members = if (row.hasBirdseye) row.cameraNames + birdseye else row.cameraNames
    val summary = members.joinToString().ifEmpty { stringResource(R.string.group_no_cameras) }
    val moveUp = stringResource(R.string.group_move_up, name)
    val moveDown = stringResource(R.string.group_move_down, name)
    val moves =
        buildList {
            if (!running && index > 0) {
                add(
                    CustomAccessibilityAction(moveUp) {
                        onMove(index - 1)
                        true
                    },
                )
            }
            if (!running && index < count - 1) {
                add(
                    CustomAccessibilityAction(moveDown) {
                        onMove(index + 1)
                        true
                    },
                )
            }
        }
    SegmentedListItem(
        onClick = { if (!running) onOpen() },
        modifier = Modifier.semantics { customActions = moves },
        shapes =
            if (isDragging) {
                // Lifted out of the group: rounded on every side.
                ListItemDefaults.segmentedShapes(index = 0, count = 1)
            } else {
                ListItemDefaults.segmentedShapes(index = index, count = count)
            },
        colors =
            ListItemDefaults.segmentedColors(
                containerColor =
                    if (isDragging) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
            ),
        leadingContent = { ShapedIcon(groupIcon(row.icon), size = 40.dp) },
        supportingContent = {
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent =
            if (count < 2) {
                null
            } else {
                {
                    Box(handle.size(48.dp), contentAlignment = Alignment.Center) {
                        Icon(Symbols.DragIndicator, contentDescription = null)
                    }
                }
            },
    ) { Text(name, style = MaterialTheme.typography.titleSmall) }
}

@Preview
@Composable
private fun CameraGroupsScreenPreview() {
    WatchdeckTheme {
        CameraGroupsScreen(
            state =
                CameraGroupsState(
                    serverId = "home",
                    serverName = "Home",
                    isLoading = false,
                    groups =
                        listOf(
                            GroupRow("Outside", "LuTrees", listOf("garden", "front door"), hasBirdseye = false),
                            GroupRow("Indoor", "generic", listOf("garage"), hasBirdseye = false),
                        ),
                ),
            actions = CameraGroupsActions(),
        )
    }
}
