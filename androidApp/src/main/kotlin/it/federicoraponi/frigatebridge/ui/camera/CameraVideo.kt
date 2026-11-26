package it.federicoraponi.frigatebridge.ui.camera

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.media3.common.Player
import it.federicoraponi.frigatebridge.R
import it.federicoraponi.frigatebridge.ui.CameraState
import it.federicoraponi.frigatebridge.ui.CameraViewModel
import it.federicoraponi.frigatebridge.ui.LiveState
import it.federicoraponi.frigatebridge.ui.LiveTransport
import it.federicoraponi.frigatebridge.ui.PlaybackState
import it.federicoraponi.frigatebridge.ui.VideoFailure
import it.federicoraponi.frigatebridge.ui.VideoMode
import it.federicoraponi.frigatebridge.ui.designsystem.Spacing

/** What the video area needs to know. */
internal data class VideoContent(
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
