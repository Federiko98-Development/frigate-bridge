package it.federicoraponi.frigatebridge.ui.camera

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
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
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.federicoraponi.frigatebridge.camera.CameraRef
import it.federicoraponi.frigatebridge.ui.CameraArgs
import it.federicoraponi.frigatebridge.ui.CameraViewModel
import it.federicoraponi.frigatebridge.ui.ClipState
import it.federicoraponi.frigatebridge.ui.LiveState
import it.federicoraponi.frigatebridge.ui.PlaybackState
import it.federicoraponi.frigatebridge.ui.TimelineArgs
import it.federicoraponi.frigatebridge.ui.TimelineViewModel
import it.federicoraponi.frigatebridge.ui.VideoMode
import it.federicoraponi.frigatebridge.ui.capture.LocalFrameCapture
import it.federicoraponi.frigatebridge.ui.capture.rememberCaptures
import it.federicoraponi.frigatebridge.ui.designsystem.BackButton
import it.federicoraponi.frigatebridge.ui.designsystem.KeepScreenOn
import it.federicoraponi.frigatebridge.ui.designsystem.ScreenTitle
import it.federicoraponi.frigatebridge.ui.designsystem.Spacing
import it.federicoraponi.frigatebridge.ui.recordings.RecordingsPanel
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
    val ready = state.clip as? ClipState.Ready
    LaunchedEffect(ready) {
        if (ready != null) {
            // Seen through even if the page goes meanwhile, e.g. because the app locks: once, and not again.
            withContext(NonCancellable) {
                captures.saveClip(ready.file, state.title, ready.startedAt)
                viewModel.clearClip()
            }
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
