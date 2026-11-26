package it.federicoraponi.frigatebridge.ui.camera

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import it.federicoraponi.frigatebridge.ui.LiveState
import it.federicoraponi.frigatebridge.ui.LiveTransport
import it.federicoraponi.frigatebridge.ui.RecordingState
import it.federicoraponi.frigatebridge.ui.VideoFailure
import it.federicoraponi.frigatebridge.ui.VideoMode
import it.federicoraponi.frigatebridge.ui.designsystem.ConnectedChoice
import it.federicoraponi.frigatebridge.ui.designsystem.PrimaryButton
import it.federicoraponi.frigatebridge.ui.designsystem.ScreenTitle
import it.federicoraponi.frigatebridge.ui.designsystem.Spacing
import it.federicoraponi.frigatebridge.ui.designsystem.problemText
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/** One camera: its live view, or the last minute it recorded, with its controls. */
@Composable
fun CameraRoute(
    camera: CameraRef,
    onClose: () -> Unit,
    mode: VideoMode = VideoMode.Live,
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
    val recording = state.recording
    val player =
        if (state.mode == VideoMode.Recording && recording is RecordingState.Ready) {
            rememberRecordingPlayer(recording.clip, onError = viewModel::recordingFailed)
        } else {
            null
        }
    // The video moves between the page and full screen without being rebuilt, so it keeps playing.
    val video =
        remember {
            movableContentOf { content: VideoContent ->
                Video(content, viewModel)
            }
        }
    val content =
        VideoContent(
            state = state,
            muted = muted,
            player = player,
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
        ) { video(content) }
        return
    }
    Scaffold(
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
            ModeSelector(state.mode, viewModel::selectMode)
            VideoFrame { video(content) }
            if (state.mode == VideoMode.Live) {
                LiveControls(
                    state = state,
                    muted = muted,
                    onMutedChange = { muted = it },
                    onSelectStream = viewModel::selectStream,
                    onFullscreen = { fullscreen = true },
                    measures = measures,
                )
                state.ptz?.let {
                    PtzPanel(it, viewModel::startMoving, viewModel::stopMoving, viewModel::goToPreset)
                }
            } else {
                if (player != null && recording is RecordingState.Ready) {
                    RecordingControls(recording.clip, player)
                }
                FullscreenButton { fullscreen = true }
            }
            if (state.streams.isNotEmpty()) FeaturePanel(state.controls, viewModel::setFeature)
        }
    }
}

/** What the video area needs to know. */
private data class VideoContent(
    val state: CameraState,
    val muted: Boolean,
    val player: Player?,
    val onMeasures: (LiveMeasures) -> Unit,
)

/** The picture, live or recorded, with its loading and failure messages. */
@Composable
private fun Video(
    content: VideoContent,
    viewModel: CameraViewModel,
) {
    val state = content.state
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                        modifier = Modifier.fillMaxSize(),
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
                    is LiveState.Failed -> Failure(live.failure, R.string.video_failed, viewModel::retry)
                    LiveState.Playing -> Unit
                }
            }
            VideoMode.Recording ->
                when (val recording = state.recording) {
                    is RecordingState.Ready ->
                        content.player?.let { RecordingPicture(it, Modifier.fillMaxSize()) }
                    RecordingState.Empty -> OverVideo(stringResource(R.string.video_no_recording))
                    is RecordingState.Failed -> Failure(recording.failure, R.string.video_failed, viewModel::retry)
                    RecordingState.Loading -> ContainedLoadingIndicator()
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
    video: @Composable () -> Unit,
) {
    val overlay = Color.Black.copy(alpha = 0.45f)
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        video()
        Box(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(Spacing.lg)) {
            Row(
                modifier = Modifier.align(Alignment.TopEnd),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                if (state.mode == VideoMode.Live) MuteButton(muted, onMutedChange, hasAudio)
                FilledTonalIconButton(onClick = onExit, shapes = IconButtonDefaults.shapes()) {
                    Icon(
                        painterResource(R.drawable.ic_fullscreen_exit),
                        contentDescription = stringResource(R.string.video_exit_fullscreen),
                    )
                }
            }
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

/** The black 16:9 area that every video and its messages share. */
@Composable
private fun VideoFrame(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(MaterialTheme.shapes.large)
                .background(Color.Black),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
private fun OverVideo(text: String) {
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(Spacing.lg),
    )
}

@Composable
private fun Failure(
    failure: VideoFailure,
    fallback: Int,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        val problem = failure.problem
        OverVideo(if (problem != null) problemText(problem) else stringResource(fallback))
        failure.detail?.let {
            Text(it, color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 3)
        }
        PrimaryButton(text = stringResource(R.string.retry), onClick = onRetry)
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
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (state.streams.size > 1) {
            ConnectedChoice(
                labels = state.streams.map { it.label },
                selected = state.streams.indexOfFirst { it.name == state.selectedStream },
                onSelect = { onSelectStream(state.streams[it].name) },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            MuteButton(muted, onMutedChange, measures.hasAudio != false)
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

@Composable
private fun MuteButton(
    muted: Boolean,
    onMutedChange: (Boolean) -> Unit,
    enabled: Boolean,
) {
    FilledTonalIconToggleButton(
        enabled = enabled,
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

@Composable
private fun FullscreenButton(onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, shapes = ButtonDefaults.shapes()) {
        Icon(painterResource(R.drawable.ic_fullscreen), contentDescription = null)
        Text(stringResource(R.string.video_fullscreen), modifier = Modifier.padding(start = Spacing.sm))
    }
}
