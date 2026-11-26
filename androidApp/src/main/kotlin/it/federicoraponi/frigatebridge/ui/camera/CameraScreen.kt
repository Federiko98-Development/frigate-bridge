package it.federicoraponi.frigatebridge.ui.camera

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import it.federicoraponi.frigatebridge.R
import it.federicoraponi.frigatebridge.camera.CameraRef
import it.federicoraponi.frigatebridge.ui.CameraArgs
import it.federicoraponi.frigatebridge.ui.CameraState
import it.federicoraponi.frigatebridge.ui.CameraViewModel
import it.federicoraponi.frigatebridge.ui.ClipState
import it.federicoraponi.frigatebridge.ui.LiveState
import it.federicoraponi.frigatebridge.ui.LiveTransport
import it.federicoraponi.frigatebridge.ui.PlaybackState
import it.federicoraponi.frigatebridge.ui.TimelineArgs
import it.federicoraponi.frigatebridge.ui.TimelineState
import it.federicoraponi.frigatebridge.ui.TimelineViewModel
import it.federicoraponi.frigatebridge.ui.VideoFailure
import it.federicoraponi.frigatebridge.ui.VideoMode
import it.federicoraponi.frigatebridge.ui.capture.ClipButton
import it.federicoraponi.frigatebridge.ui.capture.LocalFrameCapture
import it.federicoraponi.frigatebridge.ui.capture.PhotoButton
import it.federicoraponi.frigatebridge.ui.capture.RecordingBadge
import it.federicoraponi.frigatebridge.ui.capture.rememberCaptures
import it.federicoraponi.frigatebridge.ui.designsystem.ConnectedChoice
import it.federicoraponi.frigatebridge.ui.designsystem.NoticeCard
import it.federicoraponi.frigatebridge.ui.designsystem.ScreenTitle
import it.federicoraponi.frigatebridge.ui.designsystem.Spacing
import it.federicoraponi.frigatebridge.ui.designsystem.problemText
import it.federicoraponi.frigatebridge.ui.recordings.RecordingsPanel
import it.federicoraponi.frigatebridge.ui.recordings.TimelineBar
import it.federicoraponi.frigatebridge.ui.recordings.TimelineZoom
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/** One camera: its live view, or the last minute it recorded, with its controls. */
@Composable
fun CameraRoute(
    camera: CameraRef,
    onClose: () -> Unit,
    mode: VideoMode = VideoMode.Live,
    /** The moment of the recordings to start from. */
    startTime: Long? = null,
    viewModel: CameraViewModel = koinViewModel { parametersOf(CameraArgs(camera, mode)) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var muted by rememberSaveable { mutableStateOf(false) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var measures by remember { mutableStateOf(LiveMeasures()) }
    LaunchedEffect(state.isGone) {
        if (state.isGone) onClose()
    }
    LifecycleStartEffect(viewModel) { onStopOrDispose { viewModel.releaseControls() } }
    // The screen stays on while a camera is being watched.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    // Created only when the recordings are first shown.
    val timeline: TimelineViewModel? =
        if (state.mode == VideoMode.Recording) {
            koinViewModel { parametersOf(TimelineArgs(camera, startTime)) }
        } else {
            null
        }
    val recordings = timeline?.state?.collectAsStateWithLifecycle()?.value
    var speed by rememberSaveable { mutableFloatStateOf(1f) }
    val playable = recordings?.playback as? PlaybackState.Ready
    val player =
        if (timeline != null && playable != null) {
            rememberRecordingPlayer(
                clip = playable.clip,
                seek = playable.seek,
                speed = speed,
                onPosition = timeline::playerAt,
                onEnded = timeline::playerEnded,
                onError = timeline::playerFailed,
            )
        } else {
            null
        }
    val captures = rememberCaptures()
    val canTakePhoto =
        if (state.mode == VideoMode.Live) state.live == LiveState.Playing else player != null
    // A recording is named after the moment on screen, the live view after now.
    val takePhoto = {
        val live = state.mode == VideoMode.Live
        val time = if (live) System.currentTimeMillis() / 1_000 else recordings?.time ?: 0
        captures.takePhoto(state.title, time)
    }
    // A finished clip is saved as soon as the server has it.
    val ready = state.clip as? ClipState.Ready
    LaunchedEffect(ready) {
        if (ready != null) {
            captures.saveClip(ready.file, state.title, ready.startedAt)
            viewModel.clearClip()
        }
    }
    // The video moves between the page and full screen without being rebuilt, so it keeps playing.
    val video =
        remember {
            movableContentOf { content: VideoContent ->
                CompositionLocalProvider(LocalFrameCapture provides captures.frames) { Video(content, viewModel) }
            }
        }
    val content =
        VideoContent(
            state = state,
            muted = muted,
            player = player,
            playback = recordings?.playback,
            onRetryRecording = { timeline?.retry() },
            onSkipRecording = { timeline?.skip(it) },
            onMeasures = { measures = it },
        )

    if (fullscreen) {
        BackHandler { fullscreen = false }
        LandscapeFullscreen()
        FullscreenCamera(
            state = state,
            muted = muted,
            onMutedChange = { muted = it },
            onExit = { fullscreen = false },
            viewModel = viewModel,
            hasAudio = measures.hasAudio != false,
            messages = captures.messages,
            onPhoto = takePhoto.takeIf { canTakePhoto },
            recordings = {
                if (timeline != null && recordings != null) {
                    FullscreenTimeline(recordings, timeline, player)
                }
            },
        ) { video(content) }
        return
    }
    Scaffold(
        snackbarHost = { SnackbarHost(captures.messages) },
        topBar = {
            TopAppBar(
                title = { ScreenTitle(state.title) },
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
            // A screen opened on the recordings, from an event, keeps the way back to them.
            if (state.canRecord || mode == VideoMode.Recording) ModeSelector(state.mode, viewModel::selectMode)
            VideoFrame { video(content) }
            if (state.mode == VideoMode.Live) {
                LiveControls(
                    state = state,
                    muted = muted,
                    onMutedChange = { muted = it },
                    onSelectStream = viewModel::selectStream,
                    onFullscreen = { fullscreen = true },
                    measures = measures,
                    onPhoto = takePhoto.takeIf { canTakePhoto },
                    onStartClip = viewModel::startClip,
                    onStopClip = viewModel::stopClip,
                )
                ClipStatus(state.clip, onDismiss = viewModel::clearClip)
                state.ptz?.let {
                    PtzPanel(it, viewModel::startMoving, viewModel::stopMoving, viewModel::goToPreset)
                }
            } else if (timeline != null && recordings != null) {
                FullscreenButton { fullscreen = true }
                RecordingsPanel(
                    recordings,
                    timeline,
                    player,
                    speed,
                    onSpeed = { speed = it },
                    onPhoto = takePhoto.takeIf { canTakePhoto },
                )
                // A clip still being prepared is followed from here too.
                ClipStatus(state.clip, onDismiss = viewModel::clearClip)
            }
            // Only administrators may change camera settings, and they do not belong with the recordings.
            if (state.mode == VideoMode.Live && state.controls.isAllowed && state.streams.isNotEmpty()) {
                FeaturePanel(state.controls, viewModel::setFeature)
            }
        }
    }
}

/** What the video area needs to know. */
private data class VideoContent(
    val state: CameraState,
    val muted: Boolean,
    val player: Player?,
    val playback: PlaybackState?,
    val onRetryRecording: () -> Unit,
    /** Jumps back or forward by that many seconds. */
    val onSkipRecording: (Int) -> Unit,
    val onMeasures: (LiveMeasures) -> Unit,
)

/** The picture, live or recorded, with its loading and failure messages. */
@Composable
private fun Video(
    content: VideoContent,
    viewModel: CameraViewModel,
) {
    val state = content.state
    // The WebRTC picture is drawn on a surface of its own, which cannot be magnified this way.
    val zoomable =
        if (state.mode == VideoMode.Live) {
            state.live == LiveState.Playing && state.httpsStream != null
        } else {
            content.playback is PlaybackState.Ready
        }
    ZoomableVideo(resetKey = state.mode to state.selectedStream, enabled = zoomable) { picture ->
        when (state.mode) {
            VideoMode.Live -> {
                val stream = state.selectedStream
                val live = state.live
                val httpsStream = state.httpsStream
                if (httpsStream != null && live !is LiveState.Failed) {
                    HttpsLiveVideo(
                        source = httpsStream,
                        connectionKey = stream to state.liveAttempt,
                        muted = content.muted,
                        onPlaying = viewModel::livePlaying,
                        onFailed = { viewModel.liveFailed(VideoFailure(detail = it)) },
                        onMeasures = content.onMeasures,
                        modifier = picture,
                    )
                } else if (stream != null &&
                    live !is LiveState.Failed &&
                    state.transport == LiveTransport.WebRtc
                ) {
                    LiveVideo(
                        connectionKey = stream to state.liveAttempt,
                        muted = content.muted,
                        onConnect = {
                            content.onMeasures(LiveMeasures())
                            viewModel.connectLive(it)
                        },
                        onDisconnect = viewModel::stopLive,
                        onPlaying = viewModel::livePlaying,
                        onLost = { viewModel.liveFailed() },
                        onMeasures = content.onMeasures,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                when (live) {
                    LiveState.Connecting -> ContainedLoadingIndicator()
                    is LiveState.Failed ->
                        Failure(live.failure, R.string.video_failed, viewModel::retry, state.isOffline)
                    LiveState.Playing -> Unit
                }
            }
            VideoMode.Recording ->
                when (val playback = content.playback) {
                    is PlaybackState.Ready ->
                        content.player?.let {
                            RecordingPicture(it, picture)
                            BufferingOverlay(it)
                        }
                    PlaybackState.Empty -> OverVideo(stringResource(R.string.video_no_recording))
                    is PlaybackState.Gap -> RecordingGap(playback, content.onSkipRecording)
                    is PlaybackState.Failed ->
                        Failure(playback.failure, R.string.video_failed, content.onRetryRecording, state.isOffline)
                    PlaybackState.Loading, null -> ContainedLoadingIndicator()
                }
        }
    }
}

/** Said where nothing was recorded, with the ways back to a recording. */
@Composable
private fun RecordingGap(
    gap: PlaybackState.Gap,
    onSkip: (Int) -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        OverVideo(stringResource(R.string.video_gap))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            // One second is enough to leave the pause on that side.
            if (gap.hasEarlier) {
                FilledTonalButton(onClick = { onSkip(-1) }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.video_gap_earlier))
                }
            }
            if (gap.hasLater) {
                FilledTonalButton(onClick = { onSkip(1) }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.video_gap_later))
                }
            }
        }
    }
}

/** The video over the whole sideways screen, with the controls on top of it. */
@Composable
private fun FullscreenCamera(
    state: CameraState,
    muted: Boolean,
    onMutedChange: (Boolean) -> Unit,
    onExit: () -> Unit,
    viewModel: CameraViewModel,
    hasAudio: Boolean,
    messages: SnackbarHostState,
    onPhoto: (() -> Unit)?,
    recordings: @Composable () -> Unit,
    video: @Composable () -> Unit,
) {
    val overlay = Color.Black.copy(alpha = 0.45f)
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // The controls come after the drawer, so that they stay on top of it and of the video.
        if (state.mode == VideoMode.Recording) RecordingsDrawer(recordings, video) else video()
        Box(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(Spacing.lg)) {
            Row(
                modifier = Modifier.align(Alignment.TopEnd),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                // The button stays while a recording is on, also if the camera stops allowing new ones.
                if (state.mode == VideoMode.Live && (state.canRecordClip || state.clip is ClipState.Recording)) {
                    ClipButton(state.clip, viewModel::startClip, viewModel::stopClip)
                }
                onPhoto?.let { PhotoButton(it) }
                if (state.mode == VideoMode.Live && hasAudio) MuteButton(muted, onMutedChange)
                FilledTonalIconButton(onClick = onExit, shapes = IconButtonDefaults.shapes()) {
                    Icon(
                        painterResource(R.drawable.ic_fullscreen_exit),
                        contentDescription = stringResource(R.string.video_exit_fullscreen),
                    )
                }
            }
            if (state.mode == VideoMode.Live && state.streams.size > 1) {
                StreamChoice(state, viewModel::selectStream, Modifier.align(Alignment.TopStart))
            }
            (state.clip as? ClipState.Recording)?.let {
                RecordingBadge(it.startedAt, Modifier.align(Alignment.TopCenter))
            }
            SnackbarHost(messages, Modifier.align(Alignment.Center))
            val ptz = state.ptz
            if (state.mode == VideoMode.Live && ptz != null) {
                if (ptz.canPanAndTilt) {
                    DirectionPad(
                        viewModel::startMoving,
                        viewModel::stopMoving,
                        modifier = Modifier.align(Alignment.BottomEnd),
                        buttonSize = 48.dp,
                        container = overlay,
                        content = Color.White,
                    )
                }
                if (ptz.canZoom) {
                    ZoomButtons(
                        viewModel::startMoving,
                        viewModel::stopMoving,
                        modifier = Modifier.align(Alignment.BottomStart),
                        buttonSize = 48.dp,
                        container = overlay,
                        content = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeSelector(
    selected: VideoMode,
    onSelect: (VideoMode) -> Unit,
) {
    val options =
        listOf(
            VideoMode.Live to R.string.video_live,
            VideoMode.Recording to R.string.video_recording,
        )
    ConnectedChoice(
        labels = options.map { stringResource(it.second) },
        selected = options.indexOfFirst { it.first == selected },
        onSelect = { onSelect(options[it].first) },
    )
}

@Composable
private fun LiveControls(
    state: CameraState,
    muted: Boolean,
    onMutedChange: (Boolean) -> Unit,
    onSelectStream: (String) -> Unit,
    onFullscreen: () -> Unit,
    measures: LiveMeasures,
    onPhoto: (() -> Unit)?,
    onStartClip: () -> Unit,
    onStopClip: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (state.streams.size > 1) StreamChoice(state, onSelectStream)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            if (measures.hasAudio != false) MuteButton(muted, onMutedChange)
            onPhoto?.let { PhotoButton(it) }
            if (state.canRecordClip || state.clip is ClipState.Recording) {
                ClipButton(state.clip, onStartClip, onStopClip)
            }
            FullscreenButton(onFullscreen)
        }
        if (state.live == LiveState.Playing) {
            val transport =
                if (state.transport == LiveTransport.WebRtc) R.string.video_via_webrtc else R.string.video_via_https
            val lines =
                listOfNotNull(
                    stringResource(transport),
                    stringResource(R.string.video_no_audio).takeIf { measures.hasAudio == false },
                    measures.firstFrameMillis?.let { stringResource(R.string.video_first_frame, it) },
                    measures.roundTripMillis?.let { stringResource(R.string.video_round_trip, it) },
                )
            Text(
                lines.joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** What is happening with the clip being recorded. */
@Composable
private fun ClipStatus(
    clip: ClipState,
    onDismiss: () -> Unit,
) {
    when (clip) {
        is ClipState.Recording -> RecordingBadge(clip.startedAt)
        ClipState.Preparing, is ClipState.Ready -> {
            Text(stringResource(R.string.clip_preparing), style = MaterialTheme.typography.bodyMedium)
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        is ClipState.Failed -> {
            val problem = clip.failure.problem
            val text = if (problem != null) problemText(problem) else stringResource(R.string.clip_failed)
            NoticeCard(
                message = listOfNotNull(text, clip.failure.detail).joinToString("\n"),
                actionLabel = stringResource(R.string.close),
                onAction = onDismiss,
            )
        }
        ClipState.None, ClipState.Starting -> Unit
    }
}

/** The video with the timeline in a drawer at the bottom, pulled up to use it and down to see the whole picture. */
@Composable
private fun RecordingsDrawer(
    timeline: @Composable () -> Unit,
    video: @Composable () -> Unit,
) {
    val sheet =
        rememberBottomSheetState(
            initialValue = SheetValue.Expanded,
            enabledValues = setOf(SheetValue.PartiallyExpanded, SheetValue.Expanded),
        )
    BottomSheetScaffold(
        scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheet),
        sheetPeekHeight = DrawerPeekHeight,
        sheetDragHandle = { DrawerHandle() },
        sheetShape = MaterialTheme.shapes.large.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize),
        // A veil over the picture, with the text colour that goes with the surface under it.
        sheetContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        sheetContentColor = MaterialTheme.colorScheme.onSurface,
        containerColor = Color.Black,
        sheetContent = { timeline() },
    ) {
        // The picture keeps the whole screen; the drawer goes over it.
        Box(modifier = Modifier.fillMaxSize()) { video() }
    }
}

// Just the handle stays in sight when the drawer is down: a thin strip, to leave the picture almost whole.
private val DrawerPeekHeight = 20.dp

/** The mark to pull the drawer with, in a lower strip than Material's own handle takes. */
@Composable
private fun DrawerHandle() {
    Box(modifier = Modifier.fillMaxWidth().height(DrawerPeekHeight), contentAlignment = Alignment.Center) {
        Box(
            modifier =
                Modifier
                    .size(width = 32.dp, height = 4.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
        )
    }
}

/** What the full screen drawer holds: play and pause beside the timeline. */
@Composable
private fun FullscreenTimeline(
    state: TimelineState,
    viewModel: TimelineViewModel,
    player: Player?,
) {
    val day = state.day ?: return
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg).padding(bottom = Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        PlayButton(player)
        TimelineBar(
            day = day,
            ranges = state.ranges,
            markers = state.markers,
            time = state.time,
            zoom = TimelineZoom.Minutes,
            onSeek = viewModel::seekTo,
            modifier = Modifier.weight(1f),
        )
    }
}

/** The camera's streams, e.g. high and low quality. */
@Composable
private fun StreamChoice(
    state: CameraState,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    ConnectedChoice(
        labels = state.streams.map { it.label },
        selected = state.streams.indexOfFirst { it.name == state.selectedStream },
        onSelect = { onSelect(state.streams[it].name) },
        modifier = modifier,
    )
}

@Composable
private fun MuteButton(
    muted: Boolean,
    onMutedChange: (Boolean) -> Unit,
) {
    FilledTonalIconToggleButton(
        checked = muted,
        onCheckedChange = onMutedChange,
        shapes = IconButtonDefaults.toggleableShapes(),
    ) {
        Icon(
            painterResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_on),
            contentDescription = stringResource(if (muted) R.string.video_unmute else R.string.video_mute),
        )
    }
}
