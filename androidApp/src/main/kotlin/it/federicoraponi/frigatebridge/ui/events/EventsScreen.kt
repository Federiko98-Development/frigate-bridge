package it.federicoraponi.frigatebridge.ui.events

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.federicoraponi.frigatebridge.R
import it.federicoraponi.frigatebridge.camera.CameraRef
import it.federicoraponi.frigatebridge.recordings.ReviewItem
import it.federicoraponi.frigatebridge.recordings.ReviewSeverity
import it.federicoraponi.frigatebridge.ui.EventsState
import it.federicoraponi.frigatebridge.ui.EventsViewModel
import it.federicoraponi.frigatebridge.ui.cameras.ServerSwitcher
import it.federicoraponi.frigatebridge.ui.designsystem.MessageState
import it.federicoraponi.frigatebridge.ui.designsystem.NoticeCard
import it.federicoraponi.frigatebridge.ui.designsystem.OfflineNotice
import it.federicoraponi.frigatebridge.ui.designsystem.RefreshableContent
import it.federicoraponi.frigatebridge.ui.designsystem.ShapedIcon
import it.federicoraponi.frigatebridge.ui.designsystem.Spacing
import it.federicoraponi.frigatebridge.ui.designsystem.Symbols
import it.federicoraponi.frigatebridge.ui.designsystem.Thumbnail
import it.federicoraponi.frigatebridge.ui.designsystem.problemText
import it.federicoraponi.frigatebridge.ui.recordings.clockTime
import it.federicoraponi.frigatebridge.ui.theme.FrigateBridgeTheme
import org.koin.androidx.compose.koinViewModel

/** Alerts and detections of the active server. */
@Composable
fun EventsRoute(
    onShowServers: () -> Unit,
    onNoServer: () -> Unit,
    onOpen: (ReviewItem) -> Unit,
    onOpenSaved: () -> Unit,
    bottomBar: @Composable () -> Unit,
    viewModel: EventsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.hasNoServer) {
        if (state.hasNoServer) onNoServer()
    }
    EventsScreen(
        state = state,
        actions =
            EventsActions(
                showServers = onShowServers,
                switchTo = viewModel::switchTo,
                openSaved = onOpenSaved,
                open = {
                    viewModel.markViewed(it)
                    onOpen(it)
                },
                refresh = viewModel::refresh,
                loadMore = viewModel::loadMore,
                setSeverity = viewModel::setSeverity,
                setGroup = viewModel::setGroup,
                showCameras = viewModel::showCameras,
                setLabel = viewModel::setLabel,
                setMoment = viewModel::setMoment,
                showToday = viewModel::showToday,
                markAllViewed = viewModel::markAllViewed,
                thumbnail = { viewModel.cachedThumbnail(it) ?: viewModel.loadThumbnail(it) },
                selection =
                    SelectionActions(
                        toggle = viewModel::toggleSelection,
                        all = viewModel::selectAll,
                        clear = viewModel::clearSelection,
                        markViewed = viewModel::markSelectedViewed,
                        delete = viewModel::deleteSelected,
                        dismissDeleteFailure = viewModel::dismissDeleteFailure,
                        share = viewModel::shareSelected,
                        endSharing = viewModel::endSharing,
                    ),
            ),
        bottomBar = bottomBar,
    )
}

/** What the list of moments can ask for. */
data class EventsActions(
    val showServers: () -> Unit = {},
    val switchTo: (String) -> Unit = {},
    val openSaved: () -> Unit = {},
    val open: (ReviewItem) -> Unit = {},
    val refresh: () -> Unit = {},
    val loadMore: () -> Unit = {},
    val setSeverity: (ReviewSeverity) -> Unit = {},
    val setGroup: (String?) -> Unit = {},
    val showCameras: (String?, Set<String>) -> Unit = { _, _ -> },
    val setLabel: (String?) -> Unit = {},
    val setMoment: (date: String, fromMinute: Int?) -> Unit = { _, _ -> },
    val showToday: () -> Unit = {},
    val markAllViewed: () -> Unit = {},
    val thumbnail: suspend (ReviewItem) -> ByteArray? = { null },
    val selection: SelectionActions = SelectionActions(),
)

@Composable
fun EventsScreen(
    state: EventsState,
    actions: EventsActions,
    modifier: Modifier = Modifier,
    bottomBar: @Composable () -> Unit = {},
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    if (picking) {
        MomentPicker(
            date = state.date,
            today = state.today,
            fromMinute = state.filter.fromMinute,
            onDismiss = { picking = false },
            onPick = { date, fromMinute ->
                picking = false
                actions.setMoment(date, fromMinute)
            },
        )
    }
    // Back leaves the selection first, before it leaves the tab.
    BackHandler(enabled = state.isSelecting) { actions.selection.clear() }
    SelectionSheets(state, actions.selection)
    Scaffold(
        modifier = modifier,
        bottomBar = bottomBar,
        topBar = {
            if (state.isSelecting) {
                SelectionTopBar(state, actions.selection)
            } else {
                TopAppBar(
                    title = { ServerSwitcher(state.server, state.servers, actions.switchTo, actions.showServers) },
                    actions = {
                        if (state.items.any { !it.isViewed }) {
                            IconButton(onClick = actions.markAllViewed, shapes = IconButtonDefaults.shapes()) {
                                Icon(
                                    Symbols.Check,
                                    contentDescription = stringResource(R.string.events_mark_all_short),
                                )
                            }
                        }
                        if (state.canManage) {
                            IconButton(onClick = actions.openSaved, shapes = IconButtonDefaults.shapes()) {
                                Icon(Symbols.Star, contentDescription = stringResource(R.string.saved_title))
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (state.isSelecting) SelectionToolbar(state, actions.selection)
        },
        floatingActionButtonPosition = FabPosition.Center,
    ) { innerPadding ->
        RefreshableContent(
            isRefreshing = state.isRefreshing,
            onRefresh = actions.refresh,
            modifier = Modifier.padding(innerPadding),
        ) {
            EventList(state, actions, onChooseMoment = { picking = true })
        }
    }
}

@Composable
private fun EventList(
    state: EventsState,
    actions: EventsActions,
    onChooseMoment: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // Room below for the toolbar of the selection.
        contentPadding =
            PaddingValues(
                start = Spacing.lg,
                end = Spacing.lg,
                top = Spacing.sm,
                bottom = if (state.isSelecting) 96.dp else Spacing.sm,
            ),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        item(key = "filters") { Filters(state, actions, onChooseMoment) }
        if (state.isOffline) item(key = "offline") { OfflineNotice() }
        state.problem?.takeUnless { state.isOffline }?.let { problem ->
            item(key = "problem") {
                NoticeCard(
                    message = problemText(problem),
                    actionLabel = stringResource(R.string.retry),
                    onAction = actions.refresh,
                    announce = true,
                )
            }
        }
        if (state.isLoading) {
            item(key = "loading") { Waiting() }
        } else if (state.items.isEmpty() && state.problem == null && !state.isOffline) {
            item(key = "empty") {
                MessageState(
                    icon = Symbols.Notifications,
                    title = stringResource(R.string.events_empty),
                    message = stringResource(R.string.events_empty_hint),
                    modifier = Modifier.fillParentMaxHeight(0.7f),
                )
            }
        }
        itemsIndexed(state.items, key = { _, moment -> moment.id }) { index, moment ->
            val name =
                state.cameras.firstOrNull { it.ref == moment.camera }?.displayName
                    ?: moment.camera.cameraName
            EventRow(
                moment = moment,
                cameraName = name,
                index = index,
                count = state.items.size,
                isSelecting = state.isSelecting,
                isSelected = moment.id in state.selected,
                actions = actions,
            )
        }
        if (state.hasMore) {
            item(key = "more") {
                // Reached the end of the list: ask for what came before.
                LaunchedEffect(state.items.size) { actions.loadMore() }
                Waiting()
            }
        }
    }
}

@Composable
private fun Waiting() {
    Box(modifier = Modifier.fillMaxWidth().padding(Spacing.xl), contentAlignment = Alignment.Center) {
        LoadingIndicator()
    }
}

/** The name of what was seen: translated for the common labels, as Frigate writes it otherwise. */
@Composable
internal fun labelName(label: String): String {
    val known =
        mapOf(
            "person" to R.string.label_person,
            // Frigate's name for a clip recorded by hand.
            "on_demand" to R.string.label_on_demand,
            "car" to R.string.label_car,
            "dog" to R.string.label_dog,
            "cat" to R.string.label_cat,
            "bicycle" to R.string.label_bicycle,
            "motorcycle" to R.string.label_motorcycle,
            "truck" to R.string.label_truck,
            "bus" to R.string.label_bus,
            "bird" to R.string.label_bird,
            "package" to R.string.label_package,
        )
    return known[label]?.let { stringResource(it) }
        ?: label.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/** How long a moment lasted, or that it is still going on. */
@Composable
internal fun momentLength(moment: ReviewItem): String =
    moment.end?.let { stringResource(R.string.events_seconds, (it - moment.start).toInt()) }
        ?: stringResource(R.string.events_in_progress)

@Composable
private fun EventRow(
    moment: ReviewItem,
    cameraName: String,
    index: Int,
    count: Int,
    isSelecting: Boolean,
    isSelected: Boolean,
    actions: EventsActions,
) {
    val colors = MaterialTheme.colorScheme
    val shape = ListItemDefaults.segmentedShapes(index = index, count = count).shape
    val haptics = LocalHapticFeedback.current
    val select = stringResource(R.string.events_select)
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .combinedClickable(
                    onLongClickLabel = select,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        actions.selection.toggle(moment)
                    },
                    // While choosing, a tap chooses instead of opening.
                    onClick = { if (isSelecting) actions.selection.toggle(moment) else actions.open(moment) },
                ).semantics(mergeDescendants = true) { if (isSelecting) selected = isSelected },
        shape = shape,
        color = if (isSelected) colors.secondaryContainer else colors.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Thumbnail(moment.id) { actions.thumbnail(moment) }
                if (isSelected) {
                    ShapedIcon(
                        Symbols.Check,
                        size = 40.dp,
                        containerColor = colors.primary,
                        contentColor = colors.onPrimary,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val seen = moment.labels.map { labelName(it) }
                Text(
                    seen.joinToString().ifEmpty { cameraName },
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(cameraName, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                Text(
                    "${clockTime(moment.start)} · ${momentLength(moment)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            if (!moment.isViewed) {
                val label = stringResource(R.string.events_new)
                Box(
                    modifier =
                        Modifier
                            .size(10.dp)
                            .background(
                                if (moment.severity == ReviewSeverity.Alert) colors.error else colors.tertiary,
                                CircleShape,
                            ).semantics { contentDescription = label },
                )
            }
        }
    }
}

@Preview
@Composable
private fun EventsScreenPreview() {
    val moment =
        ReviewItem(
            serverId = "home",
            id = "moment",
            camera = CameraRef("home", "front_door"),
            start = 1_800_000_000,
            end = 1_800_000_025,
            severity = ReviewSeverity.Alert,
            labels = listOf("person"),
            zones = emptyList(),
            thumbnailPath = "",
            isViewed = false,
        )
    FrigateBridgeTheme {
        EventsScreen(
            state = EventsState(items = listOf(moment), isLoading = false, today = "2027-01-15"),
            actions = EventsActions(),
        )
    }
}
