package it.federicoraponi.frigatebridge.ui.events

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import it.federicoraponi.frigatebridge.ui.Keeping
import it.federicoraponi.frigatebridge.ui.PlaybackState
import it.federicoraponi.frigatebridge.ui.camera.BufferingOverlay
import it.federicoraponi.frigatebridge.ui.camera.Failure
import it.federicoraponi.frigatebridge.ui.camera.FullscreenButton
import it.federicoraponi.frigatebridge.ui.camera.FullscreenPlayer
import it.federicoraponi.frigatebridge.ui.camera.LandscapeFullscreen
import it.federicoraponi.frigatebridge.ui.camera.PlayButton
import it.federicoraponi.frigatebridge.ui.camera.RecordingPicture
import it.federicoraponi.frigatebridge.ui.camera.SkipButton
import it.federicoraponi.frigatebridge.ui.camera.VideoFrame
import it.federicoraponi.frigatebridge.ui.camera.VideoSeekBar
import it.federicoraponi.frigatebridge.ui.camera.ZoomableVideo
import it.federicoraponi.frigatebridge.ui.camera.rememberRecordingPlayer
import it.federicoraponi.frigatebridge.ui.camera.seekBy
import it.federicoraponi.frigatebridge.ui.capture.LocalFrameCapture
import it.federicoraponi.frigatebridge.ui.capture.PhotoButton
import it.federicoraponi.frigatebridge.ui.capture.rememberCaptures
import it.federicoraponi.frigatebridge.ui.designsystem.BackButton
import it.federicoraponi.frigatebridge.ui.designsystem.KeepScreenOn
import it.federicoraponi.frigatebridge.ui.designsystem.NoticeCard
import it.federicoraponi.frigatebridge.ui.designsystem.PrimaryButton
import it.federicoraponi.frigatebridge.ui.designsystem.ScreenTitle
import it.federicoraponi.frigatebridge.ui.designsystem.Spacing
import it.federicoraponi.frigatebridge.ui.designsystem.StatusText
import it.federicoraponi.frigatebridge.ui.designsystem.Symbols
import it.federicoraponi.frigatebridge.ui.designsystem.failureText
import it.federicoraponi.frigatebridge.ui.recordings.ExportSection
import it.federicoraponi.frigatebridge.ui.recordings.clockTime
import it.federicoraponi.frigatebridge.ui.recordings.dayAndTime
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
    onOpenKept: () -> Unit,
    viewModel: EventViewModel = koinViewModel { parametersOf(EventArgs(serverId, reviewId)) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.isGone) {
        if (state.isGone) onClose()
    }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
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
    KeepScreenOn()
    val captures = rememberCaptures()
    val takePhoto =
        player?.let { current ->
            {
                // The video starts a little before the moment itself.
                val time = ready.clip.startTime + current.currentPosition / 1_000
                captures.takePhoto(state.cameraName, time)
            }
        }
    // The video moves between the page and full screen without being rebuilt, so it keeps playing.
    val video =
        remember {
            movableContentOf { shown: EventState, current: Player?, retry: () -> Unit ->
                CompositionLocalProvider(LocalFrameCapture provides captures.frames) {
                    EventVideo(shown, current, retry)
                }
            }
        }
    val picture: @Composable () -> Unit = { video(state, player, viewModel::retry) }
    if (fullscreen) {
        BackHandler { fullscreen = false }
        LandscapeFullscreen()
        FullscreenPlayer(
            state.cameraName,
            player,
            captures.messages,
            takePhoto,
            onExit = { fullscreen = false },
        ) { picture() }
    } else {
        EventScreen(
            state = state,
            player = player,
            onClose = onClose,
            onFullscreen = { fullscreen = true },
            onOpenTimeline = { onOpenTimeline(it.camera, it.start - LEAD_SECONDS) },
            onExport = viewModel::export,
            onKeep = viewModel::keep,
            onOpenKept = onOpenKept,
            onExportDone = viewModel::clearExport,
            messages = captures.messages,
            onPhoto = takePhoto,
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
    onKeep: (title: String) -> Unit = {},
    onOpenKept: () -> Unit = {},
    messages: SnackbarHostState = remember { SnackbarHostState() },
    onPhoto: (() -> Unit)? = null,
) {
    Scaffold(
        snackbarHost = { SnackbarHost(messages) },
        topBar = {
            TopAppBar(
                // The camera is not known yet, or the server no longer has the moment.
                title = { ScreenTitle(state.cameraName.ifEmpty { stringResource(R.string.event_title) }) },
                navigationIcon = {
                    BackButton(onClose)
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
            VideoSeekBar(player)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                SkipButton(-SKIP_SECONDS) { player?.seekBy(-SKIP_SECONDS) }
                PlayButton(player)
                SkipButton(SKIP_SECONDS) { player?.seekBy(SKIP_SECONDS) }
                onPhoto?.let { PhotoButton(it) }
            }
            FullscreenButton(onFullscreen)
            state.item?.let { item ->
                Details(item)
                PrimaryButton(
                    text = stringResource(R.string.event_open_timeline),
                    onClick = { onOpenTimeline(item) },
                    modifier = Modifier.fillMaxWidth(),
                )
                state.keeping?.let { keeping ->
                    val seen = item.labels.map { labelName(it) }.joinToString()
                    val moment = "${dayLabel(dayOf(item.start))} ${clockTime(item.start)}"
                    // The name the kept video gets, also in Frigate's web interface.
                    val title = listOf(seen, state.cameraName, moment).filter { it.isNotEmpty() }.joinToString(" · ")
                    KeepSection(keeping, onKeep = { onKeep(title) }, onOpenKept = onOpenKept)
                }
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

@Composable
private fun EventVideo(
    state: EventState,
    player: Player?,
    onRetry: () -> Unit,
) {
    ZoomableVideo(resetKey = null, enabled = state.playback is PlaybackState.Ready) { picture ->
        when (val playback = state.playback) {
            is PlaybackState.Ready ->
                player?.let {
                    RecordingPicture(it, picture)
                    BufferingOverlay(it)
                }
            is PlaybackState.Failed ->
                Failure(playback.failure, R.string.video_failed, onRetry, state.isOffline)
            // A moment's video is always asked for whole: it has no cursor to leave in a pause.
            PlaybackState.Loading, PlaybackState.Empty, is PlaybackState.Gap -> ContainedLoadingIndicator()
        }
    }
}

/** The button that keeps a moment on the server, then how it went. */
@Composable
private fun KeepSection(
    keeping: Keeping,
    onKeep: () -> Unit,
    onOpenKept: () -> Unit,
) {
    when (keeping) {
        Keeping.NotKept -> KeepButton(R.string.keep_event, onKeep)
        Keeping.Working -> {
            StatusText(stringResource(R.string.keep_working))
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Keeping.Kept -> KeepButton(R.string.keep_open, onOpenKept)
        is Keeping.Failed ->
            NoticeCard(
                message = failureText(keeping.failure, R.string.keep_failed),
                actionLabel = stringResource(R.string.retry),
                onAction = onKeep,
                announce = true,
            )
    }
}

@Composable
private fun KeepButton(
    label: Int,
    onClick: () -> Unit,
) {
    FilledTonalButton(onClick = onClick, shapes = ButtonDefaults.shapes()) {
        Icon(Symbols.Star, contentDescription = null)
        Text(stringResource(label), modifier = Modifier.padding(start = Spacing.sm))
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
    val length = momentLength(item)
    val zones = item.zones.joinToString { zone -> zone.replace('_', ' ') }
    val rows =
        listOfNotNull(
            (stringResource(R.string.event_what) to seen).takeIf { seen.isNotEmpty() },
            stringResource(R.string.event_kind) to kind,
            stringResource(R.string.event_when) to dayAndTime(item.start),
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
