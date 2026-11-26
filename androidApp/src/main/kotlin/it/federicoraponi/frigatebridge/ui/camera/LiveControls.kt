package it.federicoraponi.frigatebridge.ui.camera

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import it.federicoraponi.frigatebridge.R
import it.federicoraponi.frigatebridge.ui.CameraState
import it.federicoraponi.frigatebridge.ui.ClipState
import it.federicoraponi.frigatebridge.ui.LiveState
import it.federicoraponi.frigatebridge.ui.LiveTransport
import it.federicoraponi.frigatebridge.ui.VideoMode
import it.federicoraponi.frigatebridge.ui.capture.ClipButton
import it.federicoraponi.frigatebridge.ui.capture.PhotoButton
import it.federicoraponi.frigatebridge.ui.capture.RecordingBadge
import it.federicoraponi.frigatebridge.ui.designsystem.ConnectedChoice
import it.federicoraponi.frigatebridge.ui.designsystem.NoticeCard
import it.federicoraponi.frigatebridge.ui.designsystem.Spacing
import it.federicoraponi.frigatebridge.ui.designsystem.StatusText
import it.federicoraponi.frigatebridge.ui.designsystem.failureText

@Composable
internal fun ModeSelector(
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
internal fun LiveControls(
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
        // With large fonts the full screen button moves to a line of its own.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
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
internal fun ClipStatus(
    clip: ClipState,
    onDismiss: () -> Unit,
) {
    when (clip) {
        is ClipState.Recording -> RecordingBadge(clip.startedAt)
        ClipState.Preparing, is ClipState.Ready, ClipState.Saving -> {
            StatusText(stringResource(R.string.clip_preparing))
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        is ClipState.Failed ->
            NoticeCard(
                message = failureText(clip.failure, R.string.clip_failed),
                actionLabel = stringResource(R.string.close),
                onAction = onDismiss,
                announce = true,
            )
        ClipState.None, ClipState.Starting -> Unit
    }
}

/** The camera's streams, e.g. high and low quality. */
@Composable
internal fun StreamChoice(
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
internal fun MuteButton(
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
            // The state is said apart, as on or off.
            contentDescription = stringResource(R.string.video_mute),
        )
    }
}
