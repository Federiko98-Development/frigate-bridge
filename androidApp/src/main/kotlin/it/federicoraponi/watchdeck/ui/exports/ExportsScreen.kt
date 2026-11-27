package it.federicoraponi.watchdeck.ui.exports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.federicoraponi.watchdeck.R
import it.federicoraponi.watchdeck.camera.CameraRef
import it.federicoraponi.watchdeck.recordings.ExportItem
import it.federicoraponi.watchdeck.ui.ExportsArgs
import it.federicoraponi.watchdeck.ui.ExportsState
import it.federicoraponi.watchdeck.ui.ExportsViewModel
import it.federicoraponi.watchdeck.ui.designsystem.BackButton
import it.federicoraponi.watchdeck.ui.designsystem.LoadingContent
import it.federicoraponi.watchdeck.ui.designsystem.MessageState
import it.federicoraponi.watchdeck.ui.designsystem.NoticeCard
import it.federicoraponi.watchdeck.ui.designsystem.RefreshableContent
import it.federicoraponi.watchdeck.ui.designsystem.ScreenTitle
import it.federicoraponi.watchdeck.ui.designsystem.Spacing
import it.federicoraponi.watchdeck.ui.designsystem.Symbols
import it.federicoraponi.watchdeck.ui.designsystem.Thumbnail
import it.federicoraponi.watchdeck.ui.designsystem.failureText
import it.federicoraponi.watchdeck.ui.recordings.dayAndTime
import it.federicoraponi.watchdeck.ui.theme.WatchdeckTheme
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/** The kept moments of the active server, or all its exports. */
@Composable
fun ExportsRoute(
    savedOnly: Boolean,
    onClose: () -> Unit,
    onOpen: (ExportItem) -> Unit,
    viewModel: ExportsViewModel = koinViewModel { parametersOf(ExportsArgs(savedOnly)) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.isGone) {
        if (state.isGone) onClose()
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.load()
        onPauseOrDispose {}
    }
    ExportsScreen(
        state = state,
        actions =
            ExportsActions(
                close = onClose,
                open = onOpen,
                refresh = viewModel::refresh,
                thumbnail = { viewModel.cachedThumbnail(it) ?: viewModel.loadThumbnail(it) },
            ),
    )
}

/** What a list of exports can ask for. */
data class ExportsActions(
    val close: () -> Unit = {},
    val open: (ExportItem) -> Unit = {},
    val refresh: () -> Unit = {},
    val thumbnail: suspend (ExportItem) -> ByteArray? = { null },
)

@Composable
fun ExportsScreen(
    state: ExportsState,
    actions: ExportsActions,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    ScreenTitle(stringResource(if (state.savedOnly) R.string.saved_title else R.string.exports_title))
                },
                navigationIcon = {
                    BackButton(actions.close)
                },
            )
        },
    ) { innerPadding ->
        RefreshableContent(
            isRefreshing = state.isRefreshing,
            onRefresh = actions.refresh,
            modifier = Modifier.padding(innerPadding),
        ) {
            if (state.isLoading) {
                LoadingContent()
                return@RefreshableContent
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                state.failure?.let { failure ->
                    item(key = "failure") {
                        NoticeCard(
                            message = failureText(failure, R.string.exports_failed),
                            actionLabel = stringResource(R.string.retry),
                            onAction = actions.refresh,
                            announce = true,
                        )
                    }
                }
                if (state.items.isEmpty() && state.failure == null) {
                    item(key = "empty") {
                        MessageState(
                            icon = if (state.savedOnly) Symbols.Star else Symbols.PlayArrow,
                            title =
                                stringResource(
                                    if (state.savedOnly) R.string.saved_empty else R.string.exports_empty,
                                ),
                            message =
                                stringResource(
                                    if (state.savedOnly) R.string.saved_empty_hint else R.string.exports_empty_hint,
                                ),
                            modifier = Modifier.fillParentMaxHeight(0.8f),
                        )
                    }
                }
                itemsIndexed(state.items, key = { _, item -> item.id }) { index, item ->
                    ExportRow(item, state, index, actions)
                }
            }
        }
    }
}

@Composable
private fun ExportRow(
    item: ExportItem,
    state: ExportsState,
    index: Int,
    actions: ExportsActions,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = { actions.open(item) },
        enabled = !item.inProgress,
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        shape = ListItemDefaults.segmentedShapes(index = index, count = state.items.size).shape,
        color = colors.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Thumbnail(item.id, item.inProgress) { actions.thumbnail(item) }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    item.title.replace('_', ' '),
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    state.cameraNames[item.camera.cameraName] ?: item.camera.cameraName,
                    style = MaterialTheme.typography.bodySmall,
                )
                val made = dayAndTime(item.date)
                Text(
                    if (item.inProgress) stringResource(R.string.exports_preparing) else made,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Preview
@Composable
private fun ExportsScreenPreview() {
    val item = ExportItem("home", "a", CameraRef("home", "front_door"), "Front door clip", 1_800_000_000, "", "", false)
    WatchdeckTheme {
        ExportsScreen(
            state = ExportsState(items = listOf(item), isLoading = false),
            actions = ExportsActions(),
        )
    }
}
