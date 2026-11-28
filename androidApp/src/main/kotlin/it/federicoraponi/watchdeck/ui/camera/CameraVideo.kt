package it.federicoraponi.watchdeck.ui.camera

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.media3.common.Player
import it.federicoraponi.watchdeck.R
import it.federicoraponi.watchdeck.ui.CameraState
import it.federicoraponi.watchdeck.ui.CameraViewModel
import it.federicoraponi.watchdeck.ui.LiveState
import it.federicoraponi.watchdeck.ui.LiveTransport
import it.federicoraponi.watchdeck.ui.PlaybackState
import it.federicoraponi.watchdeck.ui.VideoMode
import it.federicoraponi.watchdeck.ui.designsystem.Spacing

/** What the video area needs to know. */
internal data class VideoContent(
    val state: CameraState,
    val muted: Boolean,
    val player: Player?,
    val playback: PlaybackState?,
    val onRetryRecording: () -> Unit,
    /** The moment on screen, in recordings, and its still picture for when the video does not play. */
    val recordingTime: Long = 0,
    val loadStill: suspend (Long) -> ByteArray? = { null },
    /** Jumps back or forward by that many seconds. */
    val onSkipRecording: (Int) -> Unit,
    val onMeasures: (LiveMeasures) -> Unit,
)

/** The picture, live or recorded, with its loading and failure messages. */
@Composable
internal fun Video(
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
    ZoomableVideo(resetKey = state.mode to state.liveStream, enabled = zoomable) { picture ->
        when (state.mode) {
            VideoMode.Live -> {
                val stream = state.liveStream
                val live = state.live
                val httpsStream = state.httpsStream
                if (httpsStream != null && live !is LiveState.Failed) {
                    HttpsLiveVideo(
                        source = httpsStream,
                        connectionKey = stream to state.liveAttempt,
                        muted = content.muted,
                        onPlaying = viewModel::livePlaying,
                        onFailed = viewModel::liveFailed,
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
                } else if (live !is LiveState.Failed && state.transport == LiveTransport.Snapshots) {
                    // There is no sound in pictures.
                    LaunchedEffect(state.liveAttempt) { content.onMeasures(LiveMeasures(hasAudio = false)) }
                    SnapshotVideo(state.liveAttempt, viewModel::liveSnapshot, picture)
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
                    is PlaybackState.Stills -> RecordingStill(content.recordingTime, content.loadStill, picture)
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
