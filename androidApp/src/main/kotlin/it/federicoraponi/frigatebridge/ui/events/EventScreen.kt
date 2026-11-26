package it.federicoraponi.frigatebridge.ui.events

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledTonalIconButton
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import it.federicoraponi.frigatebridge.R
import it.federicoraponi.frigatebridge.camera.CameraRef
import it.federicoraponi.frigatebridge.recordings.ReviewItem
import it.federicoraponi.frigatebridge.recordings.ReviewSeverity
import it.federicoraponi.frigatebridge.ui.EventArgs
import it.federicoraponi.frigatebridge.ui.EventState
import it.federicoraponi.frigatebridge.ui.EventViewModel
import it.federicoraponi.frigatebridge.ui.ExportState
import it.federicoraponi.frigatebridge.ui.PlaybackState
import it.federicoraponi.frigatebridge.ui.camera.Failure
import it.federicoraponi.frigatebridge.ui.camera.FullscreenButton
import it.federicoraponi.frigatebridge.ui.camera.LandscapeFullscreen
import it.federicoraponi.frigatebridge.ui.camera.PlayButton
import it.federicoraponi.frigatebridge.ui.camera.RecordingPicture
import it.federicoraponi.frigatebridge.ui.camera.SkipButton
import it.federicoraponi.frigatebridge.ui.camera.VideoFrame
import it.federicoraponi.frigatebridge.ui.camera.rememberRecordingPlayer
import it.federicoraponi.frigatebridge.ui.designsystem.PrimaryButton
import it.federicoraponi.frigatebridge.ui.designsystem.Spacing
import it.federicoraponi.frigatebridge.ui.recordings.ExportSection
import it.federicoraponi.frigatebridge.ui.recordings.clockTime
import it.federicoraponi.frigatebridge.ui.recordings.dayLabel
import it.federicoraponi.frigatebridge.ui.recordings.dayOf
import it.federicoraponi.frigatebridge.ui.theme.FrigateBridgeTheme
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

// How long before its start the timeline is opened.
private const val LEAD_SECONDS = 2
private const val SKIP_SECONDS = 10

/** One alert or detection with its video. */
@Composable
fun EventRoute(
    serverId: String,
    reviewId: String,
    onClose: () -> Unit,
    onOpenTimeline: (CameraRef, startTime: Long) -> Unit,
    viewModel: EventViewModel = koinViewModel { parametersOf(EventArgs(serverId, reviewId)) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.isGone) {
        if (state.isGone) onClose()
    }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val view = LocalView.current
    val ready = state.playback as? PlaybackState.Ready
    val player =
        ready?.let {
            rememberRecordingPlayer(
                clip = it.clip,
                seek = it.seek,
                speed = 1f,
                onPosition = {},
                onEnded = {},
                onError = viewModel::playerFailed,
            )
        }
    // The screen stays on while a moment is being watched.
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    // The video moves between the page and full screen without being rebuilt, so it keeps playing.
    val video =
        remember {
            movableContentOf { playback: PlaybackState, current: Player?, retry: () -> Unit ->
                EventVideo(playback, current, retry)
            }
        }
    val picture: @Composable () -> Unit = { video(state.playback, player, viewModel::retry) }
    if (fullscreen) {
        BackHandler { fullscreen = false }
        LandscapeFullscreen()
        FullscreenEvent(player, onExit = { fullscreen = false }) { picture() }
    } else {
        EventScreen(
            state = state,
            player = player,
            onClose = onClose,
            onFullscreen = { fullscreen = true },
            onOpenTimeline = { onOpenTimeline(it.camera, it.start - LEAD_SECONDS) },
            onExport = viewModel::export,
            onExportDone = viewModel::clearExport,
            video = { picture() },
        )
    }
}

@Composable
private fun EventScreen(
    state: EventState,
    player: Player?,
    onClose: () -> Unit,
    onFullscreen: () -> Unit,
    onOpenTimeline: (ReviewItem) -> Unit,
    onExport: () -> Unit,
    onExportDone: () -> Unit,
    video: @Composable BoxScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(state.cameraName, style = MaterialTheme.typography.titleLargeEmphasized, maxLines = 1)
                },
                navigationIcon = {
                    IconButton(onClick = onClose, shapes = IconButtonDefaults.shapes()) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            VideoFrame(video)
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkipButton(R.string.video_back_10) { player?.seekBy(-SKIP_SECONDS) }
                PlayButton(player)
                SkipButton(R.string.video_forward_10) { player?.seekBy(SKIP_SECONDS) }
            }
            FullscreenButton(onFullscreen)
            state.item?.let { item ->
                Details(item)
                PrimaryButton(
                    text = stringResource(R.string.event_open_timeline),
                    onClick = { onOpenTimeline(item) },
                    modifier = Modifier.fillMaxWidth(),
                )
                ExportSection(state.export, onChoose = onExport, onDone = onExportDone)
                if (state.export == ExportState.None) {
                    Text(
                        stringResource(R.string.export_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun Player.seekBy(seconds: Int) = seekTo((currentPosition + seconds * 1_000L).coerceAtLeast(0))

@Composable
private fun EventVideo(
    playback: PlaybackState,
    player: Player?,
    onRetry: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (playback) {
            is PlaybackState.Ready -> player?.let { RecordingPicture(it, Modifier.fillMaxSize()) }
            is PlaybackState.Failed -> Failure(playback.failure, R.string.video_failed, onRetry)
            PlaybackState.Loading, PlaybackState.Empty -> ContainedLoadingIndicator()
        }
    }
}

@Composable
private fun FullscreenEvent(
    player: Player?,
    onExit: () -> Unit,
    video: @Composable () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        video()
        Box(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(Spacing.lg)) {
            FilledTonalIconButton(
                onClick = onExit,
                shapes = IconButtonDefaults.shapes(),
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Icon(
                    painterResource(R.drawable.ic_fullscreen_exit),
                    contentDescription = stringResource(R.string.video_exit_fullscreen),
                )
            }
            PlayButton(player, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** What was seen, when and where. */
@Composable
private fun Details(item: ReviewItem) {
    val seen = item.labels.map { labelName(it) }.joinToString()
    val kind =
        stringResource(
            if (item.severity == ReviewSeverity.Alert) R.string.event_alert else R.string.event_detection,
        )
    val length =
        item.end?.let { stringResource(R.string.events_seconds, (it - item.start).toInt()) }
            ?: stringResource(R.string.events_in_progress)
    val zones = item.zones.joinToString { zone -> zone.replace('_', ' ') }
    val rows =
        listOfNotNull(
            (stringResource(R.string.event_what) to seen).takeIf { seen.isNotEmpty() },
            stringResource(R.string.event_kind) to kind,
            stringResource(R.string.event_when) to "${dayLabel(dayOf(item.start))} · ${clockTime(item.start)}",
            stringResource(R.string.event_duration) to length,
            (stringResource(R.string.event_zones) to zones).takeIf { zones.isNotEmpty() },
        )
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        rows.forEachIndexed { index, (title, value) ->
            Surface(
                modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                shape = ListItemDefaults.segmentedShapes(index = index, count = rows.size).shape,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
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
}

@Preview
@Composable
private fun EventScreenPreview() {
    val camera = CameraRef("home", "front_door")
    val item =
        ReviewItem(
            serverId = "home",
            id = "moment",
            camera = camera,
            start = 1_800_000_000,
            end = 1_800_000_025,
            severity = ReviewSeverity.Alert,
            labels = listOf("person"),
            zones = listOf("porch"),
            thumbnailPath = "",
            isViewed = true,
        )
    FrigateBridgeTheme {
        EventScreen(
            state = EventState(item = item, cameraName = "Front door"),
            player = null,
            onClose = {},
            onFullscreen = {},
            onOpenTimeline = {},
            onExport = {},
            onExportDone = {},
            video = {},
        )
    }
}
