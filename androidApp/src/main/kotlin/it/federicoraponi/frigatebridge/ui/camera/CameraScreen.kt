package it.federicoraponi.frigatebridge.ui.camera

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.federicoraponi.frigatebridge.camera.CameraFeature
import it.federicoraponi.frigatebridge.camera.CameraRef
import it.federicoraponi.frigatebridge.ui.CameraArgs
import it.federicoraponi.frigatebridge.ui.CameraState
import it.federicoraponi.frigatebridge.ui.CameraViewModel
import it.federicoraponi.frigatebridge.ui.ClipState
import it.federicoraponi.frigatebridge.ui.LiveState
import it.federicoraponi.frigatebridge.ui.PlaybackState
import it.federicoraponi.frigatebridge.ui.PtzMove
import it.federicoraponi.frigatebridge.ui.TimelineArgs
import it.federicoraponi.frigatebridge.ui.TimelineViewModel
import it.federicoraponi.frigatebridge.ui.VideoMode
import it.federicoraponi.frigatebridge.ui.capture.LocalFrameCapture
import it.federicoraponi.frigatebridge.ui.capture.rememberCaptures
import it.federicoraponi.frigatebridge.ui.designsystem.BackButton
import it.federicoraponi.frigatebridge.ui.designsystem.KeepScreenOn
import it.federicoraponi.frigatebridge.ui.designsystem.ScreenTitle
import it.federicoraponi.frigatebridge.ui.designsystem.Spacing
import it.federicoraponi.frigatebridge.ui.recordings.RecordingsActions
import it.federicoraponi.frigatebridge.ui.recordings.RecordingsPanel
import it.federicoraponi.frigatebridge.ui.theme.FrigateBridgeTheme
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
    KeepScreenOn()
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
    val isClipReady = state.clip is ClipState.Ready
    LaunchedEffect(isClipReady) {
        // Seen through even if the page goes meanwhile, e.g. because the app locks.
        withContext(NonCancellable) {
            val clip = viewModel.takeClip() ?: return@withContext
            captures.saveClip(clip.file, state.title, clip.startedAt)
            viewModel.clipSaved()
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

    val actions =
        CameraActions(
            close = onClose,
            selectMode = viewModel::selectMode,
            selectStream = viewModel::selectStream,
            setMuted = { muted = it },
            fullscreen = { fullscreen = true },
            takePhoto = takePhoto.takeIf { canTakePhoto },
            startClip = viewModel::startClip,
            stopClip = viewModel::stopClip,
            clearClip = viewModel::clearClip,
            startMoving = viewModel::startMoving,
            stopMoving = viewModel::stopMoving,
            goToPreset = viewModel::goToPreset,
            setFeature = viewModel::setFeature,
        )
    if (fullscreen) {
        BackHandler { fullscreen = false }
        LandscapeFullscreen()
        FullscreenCamera(
            state = state,
            muted = muted,
            hasAudio = measures.hasAudio != false,
            messages = captures.messages,
            actions = actions,
            onExit = { fullscreen = false },
            recordings = {
                if (timeline != null && recordings != null) {
                    FullscreenTimeline(recordings, player, onSeek = timeline::seekTo)
                }
            },
        ) { video(content) }
        return
    }
    CameraScreen(
        state = state,
        muted = muted,
        measures = measures,
        openedOnRecordings = mode == VideoMode.Recording,
        messages = captures.messages,
        actions = actions,
        recordings =
            if (timeline != null && recordings != null) {
                {
                    FullscreenButton { fullscreen = true }
                    RecordingsPanel(
                        state = recordings,
                        player = player,
                        speed = speed,
                        actions =
                            RecordingsActions(
                                selectDay = timeline::selectDay,
                                seekTo = timeline::seekTo,
                                skip = timeline::skip,
                                export = timeline::export,
                                clearExport = timeline::clearExport,
                                setSpeed = { speed = it },
                                takePhoto = actions.takePhoto,
                            ),
                    )
                }
            } else {
                null
            },
        video = { video(content) },
    )
}

/** What the page of a camera can ask for. */
data class CameraActions(
    val close: () -> Unit = {},
    val selectMode: (VideoMode) -> Unit = {},
    val selectStream: (String) -> Unit = {},
    val setMuted: (Boolean) -> Unit = {},
    val fullscreen: () -> Unit = {},
    /** `null` while there is no picture to take. */
    val takePhoto: (() -> Unit)? = null,
    val startClip: () -> Unit = {},
    val stopClip: () -> Unit = {},
    val clearClip: () -> Unit = {},
    val startMoving: (PtzMove) -> Unit = {},
    val stopMoving: () -> Unit = {},
    val goToPreset: (String) -> Unit = {},
    val setFeature: (CameraFeature, Boolean) -> Unit = { _, _ -> },
)

/** The upright page of a camera: the video with what can be done with it underneath. */
@Composable
fun CameraScreen(
    state: CameraState,
    muted: Boolean,
    measures: LiveMeasures,
    /** The page was opened on the recordings, and keeps the way back to them. */
    openedOnRecordings: Boolean,
    messages: SnackbarHostState,
    actions: CameraActions,
    modifier: Modifier = Modifier,
    /** What goes under the video of the recordings; `null` until they can be shown. */
    recordings: (@Composable ColumnScope.() -> Unit)? = null,
    video: @Composable BoxScope.() -> Unit,
) {
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(messages) },
        topBar = {
            TopAppBar(
                title = { ScreenTitle(state.title) },
                navigationIcon = { BackButton(actions.close) },
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
            if (state.canRecord || openedOnRecordings) ModeSelector(state.mode, actions.selectMode)
            VideoFrame(video)
            if (state.mode == VideoMode.Live) {
                LiveControls(
                    state = state,
                    muted = muted,
                    onMutedChange = actions.setMuted,
                    onSelectStream = actions.selectStream,
                    onFullscreen = actions.fullscreen,
                    measures = measures,
                    onPhoto = actions.takePhoto,
                    onStartClip = actions.startClip,
                    onStopClip = actions.stopClip,
                )
                ClipStatus(state.clip, onDismiss = actions.clearClip)
                state.ptz?.let { PtzPanel(it, actions.startMoving, actions.stopMoving, actions.goToPreset) }
            } else if (recordings != null) {
                recordings()
                // A clip still being prepared is followed from here too.
                ClipStatus(state.clip, onDismiss = actions.clearClip)
            }
            // Only administrators may change camera settings, and they do not belong with the recordings.
            if (state.mode == VideoMode.Live && state.controls.isAllowed && state.streams.isNotEmpty()) {
                FeaturePanel(state.controls, actions.setFeature)
            }
        }
    }
}

@Preview
@Composable
private fun CameraScreenPreview() {
    FrigateBridgeTheme {
        CameraScreen(
            state = CameraState(title = "Front door", live = LiveState.Playing),
            muted = false,
            measures = LiveMeasures(),
            openedOnRecordings = false,
            messages = SnackbarHostState(),
            actions = CameraActions(),
            video = {},
        )
    }
}
