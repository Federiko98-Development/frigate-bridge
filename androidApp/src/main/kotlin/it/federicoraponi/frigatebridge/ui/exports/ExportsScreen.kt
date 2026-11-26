package it.federicoraponi.frigatebridge.ui.exports

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.federicoraponi.frigatebridge.R
import it.federicoraponi.frigatebridge.recordings.ExportItem
import it.federicoraponi.frigatebridge.ui.ExportsArgs
import it.federicoraponi.frigatebridge.ui.ExportsState
import it.federicoraponi.frigatebridge.ui.ExportsViewModel
import it.federicoraponi.frigatebridge.ui.designsystem.LoadingContent
import it.federicoraponi.frigatebridge.ui.designsystem.MessageState
import it.federicoraponi.frigatebridge.ui.designsystem.NoticeCard
import it.federicoraponi.frigatebridge.ui.designsystem.RefreshableContent
import it.federicoraponi.frigatebridge.ui.designsystem.ScreenTitle
import it.federicoraponi.frigatebridge.ui.designsystem.Spacing
import it.federicoraponi.frigatebridge.ui.designsystem.problemText
import it.federicoraponi.frigatebridge.ui.recordings.clockTime
import it.federicoraponi.frigatebridge.ui.recordings.dayLabel
import it.federicoraponi.frigatebridge.ui.recordings.dayOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    ExportsScreen(state, viewModel, onClose, onOpen)
}

@Composable
private fun ExportsScreen(
    state: ExportsState,
    viewModel: ExportsViewModel,
    onClose: () -> Unit,
    onOpen: (ExportItem) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    ScreenTitle(stringResource(if (state.savedOnly) R.string.saved_title else R.string.exports_title))
                },
                navigationIcon = {
                    IconButton(onClick = onClose, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
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
                        val problem = failure.problem
                        val text =
                            if (problem !=
                                null
                            ) {
                                problemText(problem)
                            } else {
                                stringResource(R.string.exports_failed)
                            }
                        NoticeCard(
                            message = listOfNotNull(text, failure.detail).joinToString("\n"),
                            actionLabel = stringResource(R.string.retry),
                            onAction = viewModel::refresh,
                        )
                    }
                }
                if (state.items.isEmpty() && state.failure == null) {
                    item(key = "empty") {
                        MessageState(
                            icon = if (state.savedOnly) Icons.Filled.Star else Icons.Filled.PlayArrow,
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
                    ExportRow(item, state, index, viewModel, onOpen)
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
    viewModel: ExportsViewModel,
    onOpen: (ExportItem) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = { onOpen(item) },
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
            ExportPicture(item, viewModel)
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
                val made = "${dayLabel(dayOf(item.date))} · ${clockTime(item.date)}"
                Text(
                    if (item.inProgress) stringResource(R.string.exports_preparing) else made,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ExportPicture(
    item: ExportItem,
    viewModel: ExportsViewModel,
) {
    val picture by
        produceState<ImageBitmap?>(null, item.id, item.inProgress) {
            val bytes = viewModel.cachedThumbnail(item) ?: viewModel.loadThumbnail(item) ?: return@produceState
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
