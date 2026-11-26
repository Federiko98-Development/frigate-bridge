package it.federicoraponi.frigatebridge.ui.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearWavyProgressIndicator
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.federicoraponi.frigatebridge.R
import it.federicoraponi.frigatebridge.server.CameraStatistics
import it.federicoraponi.frigatebridge.server.DiskUse
import it.federicoraponi.frigatebridge.server.ServerStatistics
import it.federicoraponi.frigatebridge.ui.ServerStatusState
import it.federicoraponi.frigatebridge.ui.ServerStatusViewModel
import it.federicoraponi.frigatebridge.ui.designsystem.BackButton
import it.federicoraponi.frigatebridge.ui.designsystem.ListGroup
import it.federicoraponi.frigatebridge.ui.designsystem.LoadingContent
import it.federicoraponi.frigatebridge.ui.designsystem.NoticeCard
import it.federicoraponi.frigatebridge.ui.designsystem.RefreshableContent
import it.federicoraponi.frigatebridge.ui.designsystem.ScreenTitle
import it.federicoraponi.frigatebridge.ui.designsystem.Spacing
import it.federicoraponi.frigatebridge.ui.designsystem.failureText
import it.federicoraponi.frigatebridge.ui.theme.FrigateBridgeTheme
import org.koin.androidx.compose.koinViewModel

@Composable
fun ServerStatusRoute(
    onClose: () -> Unit,
    viewModel: ServerStatusViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.isGone) {
        if (state.isGone) onClose()
    }
    ServerStatusScreen(state, onClose = onClose, onRefresh = viewModel::refresh)
}

@Composable
fun ServerStatusScreen(
    state: ServerStatusState,
    onClose: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { ScreenTitle(stringResource(R.string.status_title)) },
                navigationIcon = {
                    BackButton(onClose)
                },
            )
        },
    ) { innerPadding ->
        RefreshableContent(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.padding(innerPadding),
        ) {
            val statistics = state.statistics
            if (statistics == null && state.failure == null) {
                LoadingContent()
            } else {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.lg),
                ) {
                    state.failure?.let { failure ->
                        NoticeCard(
                            message = failureText(failure, R.string.status_failed),
                            actionLabel = stringResource(R.string.retry),
                            onAction = onRefresh,
                            announce = true,
                        )
                    }
                    if (statistics != null) Statistics(statistics, state)
                }
            }
        }
    }
}

@Composable
private fun Statistics(
    statistics: ServerStatistics,
    state: ServerStatusState,
) {
    statistics.newerVersion?.let { NoticeCard(stringResource(R.string.status_update, it)) }
    statistics.disk?.let { Disk(it) }
    val system =
        listOfNotNull(
            stringResource(R.string.status_server) to state.serverName,
            stringResource(R.string.status_version) to statistics.version,
            stringResource(R.string.status_uptime) to uptime(statistics.uptimeSeconds),
            statistics.processorPercent?.let { stringResource(R.string.status_processor) to percent(it) },
            statistics.memoryPercent?.let { stringResource(R.string.status_memory) to percent(it) },
        ) +
            statistics.detectors.map {
                stringResource(R.string.status_detector, it.name) to
                    stringResource(R.string.status_milliseconds, it.inferenceMillis)
            }
    ListGroup(title = stringResource(R.string.status_system)) {
        system.forEachIndexed { index, (title, value) -> Figure(title, value, index, system.size) }
    }
    if (statistics.cameras.isNotEmpty()) {
        ListGroup(title = stringResource(R.string.settings_cameras)) {
            statistics.cameras.forEachIndexed { index, camera ->
                Figure(
                    title = state.cameraNames[camera.name] ?: camera.name,
                    value = cameraSummary(camera),
                    index = index,
                    count = statistics.cameras.size,
                )
            }
        }
    }
}

/** The disk of the recordings, the one thing worth a card of its own. */
@Composable
private fun Disk(disk: DiskUse) {
    Surface(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(stringResource(R.string.status_disk), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.status_free, size(disk.free)),
                style = MaterialTheme.typography.titleLargeEmphasized,
            )
            val used = if (disk.total > 0) (disk.used / disk.total).toFloat().coerceIn(0f, 1f) else 0f
            LinearWavyProgressIndicator(progress = { used }, modifier = Modifier.fillMaxWidth())
            Text(
                stringResource(R.string.status_used, size(disk.used), size(disk.total)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun cameraSummary(camera: CameraStatistics): String {
    val frames =
        if (camera.framesPerSecond > 0) {
            stringResource(R.string.status_frames, camera.framesPerSecond, camera.skippedPerSecond)
        } else {
            stringResource(R.string.status_no_frames)
        }
    val storage = camera.storage?.let { stringResource(R.string.status_recordings, size(it)) }
    return listOfNotNull(frames, storage).joinToString("\n")
}

@Composable
private fun Figure(
    title: String,
    value: String,
    index: Int,
    count: Int,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        shape = ListItemDefaults.segmentedShapes(index = index, count = count).shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(value, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** A size given in mebibytes, in the unit that reads best. */
@Composable
private fun size(mebibytes: Double): String =
    when {
        mebibytes >= 1024 * 1024 -> stringResource(R.string.size_terabytes, mebibytes / (1024 * 1024))
        mebibytes >= 1024 -> stringResource(R.string.size_gigabytes, mebibytes / 1024)
        else -> stringResource(R.string.size_megabytes, mebibytes)
    }

@Composable
private fun percent(value: Double) = stringResource(R.string.status_percent, value)

@Composable
private fun uptime(seconds: Long): String {
    val days = (seconds / 86_400).toInt()
    val hours = (seconds % 86_400 / 3_600).toInt()
    val minutes = (seconds % 3_600 / 60).toInt()
    return if (days > 0) {
        stringResource(R.string.status_days, days, hours)
    } else {
        stringResource(R.string.status_hours, hours, minutes)
    }
}

@Preview
@Composable
private fun ServerStatusScreenPreview() {
    FrigateBridgeTheme {
        ServerStatusScreen(
            state =
                ServerStatusState(
                    serverName = "Home",
                    statistics =
                        ServerStatistics(
                            version = "0.18.3",
                            newerVersion = null,
                            uptimeSeconds = 93_000,
                            disk = DiskUse(1_000_000.0, 400_000.0, 600_000.0),
                            cameras = listOf(CameraStatistics("front_door", 5.0, 0.0, 20_480.0, 2.0)),
                            detectors = emptyList(),
                            processorPercent = 12.0,
                            memoryPercent = 40.0,
                        ),
                ),
            onClose = {},
            onRefresh = {},
        )
    }
}
