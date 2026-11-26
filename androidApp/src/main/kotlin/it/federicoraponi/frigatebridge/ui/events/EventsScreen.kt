package it.federicoraponi.frigatebridge.ui.events

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.federicoraponi.frigatebridge.R
import it.federicoraponi.frigatebridge.recordings.ReviewItem
import it.federicoraponi.frigatebridge.recordings.ReviewSeverity
import it.federicoraponi.frigatebridge.ui.EventsState
import it.federicoraponi.frigatebridge.ui.EventsViewModel
import it.federicoraponi.frigatebridge.ui.cameras.CameraGroupBar
import it.federicoraponi.frigatebridge.ui.cameras.ServerSwitcher
import it.federicoraponi.frigatebridge.ui.designsystem.ConnectedChoice
import it.federicoraponi.frigatebridge.ui.designsystem.MessageState
import it.federicoraponi.frigatebridge.ui.designsystem.NoticeCard
import it.federicoraponi.frigatebridge.ui.designsystem.OfflineNotice
import it.federicoraponi.frigatebridge.ui.designsystem.RefreshableContent
import it.federicoraponi.frigatebridge.ui.designsystem.Spacing
import it.federicoraponi.frigatebridge.ui.designsystem.problemText
import it.federicoraponi.frigatebridge.ui.recordings.clockTime
import it.federicoraponi.frigatebridge.ui.recordings.dayLabel
import it.federicoraponi.frigatebridge.ui.recordings.minuteLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel

/** Alerts and detections of the active server. */
@Composable
fun EventsRoute(
    onShowServers: () -> Unit,
    onOpen: (ReviewItem) -> Unit,
    onOpenSaved: () -> Unit,
    bottomBar: @Composable () -> Unit,
    viewModel: EventsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.hasNoServer) {
        if (state.hasNoServer) onShowServers()
    }
    EventsScreen(
        state = state,
        viewModel = viewModel,
        onShowServers = onShowServers,
        onOpenSaved = onOpenSaved,
        onOpen = {
            viewModel.markViewed(it)
            onOpen(it)
        },
        bottomBar = bottomBar,
    )
}

@Composable
private fun EventsScreen(
    state: EventsState,
    viewModel: EventsViewModel,
    onShowServers: () -> Unit,
    onOpen: (ReviewItem) -> Unit,
    onOpenSaved: () -> Unit,
    bottomBar: @Composable () -> Unit,
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
                viewModel.setMoment(date, fromMinute)
            },
        )
    }
    Scaffold(
        bottomBar = bottomBar,
        topBar = {
            TopAppBar(
                title = { ServerSwitcher(state.server, state.servers, viewModel::switchTo, onShowServers) },
                actions = {
                    if (state.canManage) {
                        IconButton(onClick = onOpenSaved, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Filled.Star, contentDescription = stringResource(R.string.saved_title))
                        }
                    }
                    IconButton(onClick = { picking = true }, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.Filled.DateRange, contentDescription = stringResource(R.string.events_choose_moment))
                    }
                },
            )
        },
    ) { innerPadding ->
        RefreshableContent(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.padding(innerPadding),
        ) {
            EventList(state, viewModel, onOpen, onChooseMoment = { picking = true })
        }
    }
}

@Composable
private fun EventList(
    state: EventsState,
    viewModel: EventsViewModel,
    onOpen: (ReviewItem) -> Unit,
    onChooseMoment: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        item(key = "filters") { Filters(state, viewModel) }
        item(key = "moment") { ShownMoment(state, viewModel, onChooseMoment) }
        if (state.isOffline) item(key = "offline") { OfflineNotice() }
        state.problem?.takeUnless { state.isOffline }?.let { problem ->
            item(key = "problem") {
                NoticeCard(
                    message = problemText(problem),
                    actionLabel = stringResource(R.string.retry),
                    onAction = viewModel::refresh,
                )
            }
        }
        if (state.isLoading) {
            item(key = "loading") { Waiting() }
        } else if (state.items.isEmpty() && state.problem == null && !state.isOffline) {
            item(key = "empty") {
                MessageState(
                    icon = Icons.Filled.Notifications,
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
            EventRow(moment, name, index, state.items.size, viewModel, onClick = { onOpen(moment) })
        }
        if (state.hasMore) {
            item(key = "more") {
                // Reached the end of the list: ask for what came before.
                LaunchedEffect(state.items.size) { viewModel.loadMore() }
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

@Composable
private fun Filters(
    state: EventsState,
    viewModel: EventsViewModel,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        ConnectedChoice(
            labels = listOf(stringResource(R.string.events_alerts), stringResource(R.string.events_detections)),
            selected = state.filter.severity.ordinal,
            onSelect = { viewModel.setSeverity(ReviewSeverity.entries[it]) },
        )
        if (state.choices.isNotEmpty()) {
            CameraGroupBar(
                choices = state.choices,
                selected = state.filter.group,
                onSelect = viewModel::setGroup,
                onShow = viewModel::showCameras,
            )
        }
        // The chosen label stays on offer even if the list it filtered no longer mentions others.
        val labels = (state.labels + listOfNotNull(state.filter.label)).distinct()
        if (labels.isNotEmpty()) {
            val names = listOf(stringResource(R.string.events_all_labels)) + labels.map { labelName(it) }
            ConnectedChoice(
                labels = names,
                selected = labels.indexOf(state.filter.label) + 1,
                onSelect = { viewModel.setLabel(if (it == 0) null else labels[it - 1]) },
            )
        }
    }
}

/** The day on screen, with the time it starts from, and what can be done with it. */
@Composable
private fun ShownMoment(
    state: EventsState,
    viewModel: EventsViewModel,
    onChoose: () -> Unit,
) {
    if (state.date.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val day = dayLabel(state.date)
        val from = state.filter.fromMinute?.let { stringResource(R.string.events_from, minuteLabel(it)) }
        FilledTonalButton(onClick = onChoose, shapes = ButtonDefaults.shapes()) {
            Text(listOfNotNull(day, from).joinToString(" · "))
        }
        if (state.filter.date != null || state.filter.fromMinute != null) {
            IconButton(onClick = viewModel::showToday, shapes = IconButtonDefaults.shapes()) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.events_today_again))
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        if (state.items.any { !it.isViewed }) {
            TextButton(onClick = viewModel::markAllViewed, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.events_mark_all_short))
            }
        }
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

@Composable
private fun EventRow(
    moment: ReviewItem,
    cameraName: String,
    index: Int,
    count: Int,
    viewModel: EventsViewModel,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        shape = ListItemDefaults.segmentedShapes(index = index, count = count).shape,
        color = colors.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Thumbnail(moment, viewModel)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val seen = moment.labels.map { labelName(it) }
                Text(
                    seen.joinToString().ifEmpty { cameraName },
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(cameraName, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                val length =
                    moment.end?.let { stringResource(R.string.events_seconds, (it - moment.start).toInt()) }
                        ?: stringResource(R.string.events_in_progress)
                Text(
                    "${clockTime(moment.start)} · $length",
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

@Composable
private fun Thumbnail(
    moment: ReviewItem,
    viewModel: EventsViewModel,
) {
    val picture by
        produceState<ImageBitmap?>(null, moment.id) {
            val bytes = viewModel.cachedThumbnail(moment) ?: viewModel.loadThumbnail(moment) ?: return@produceState
            value =
                withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }
        }
    Surface(
        modifier = Modifier.width(112.dp).aspectRatio(16f / 9f),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        val shown = picture
        if (shown != null) {
            Image(shown, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Warning, contentDescription = null) }
        }
    }
}
