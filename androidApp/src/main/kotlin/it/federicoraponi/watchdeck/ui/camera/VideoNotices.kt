package it.federicoraponi.watchdeck.ui.camera

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import it.federicoraponi.watchdeck.R
import it.federicoraponi.watchdeck.ui.CameraState
import it.federicoraponi.watchdeck.ui.LiveSetback
import it.federicoraponi.watchdeck.ui.LiveState
import it.federicoraponi.watchdeck.ui.LiveTransport
import it.federicoraponi.watchdeck.ui.VideoFailure
import it.federicoraponi.watchdeck.ui.designsystem.NoticeCard
import it.federicoraponi.watchdeck.ui.designsystem.failureText

/** What did not work in the live view, why, and what is shown instead. Nothing while the first way works. */
@Composable
internal fun LiveNotice(
    state: CameraState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.setbacks.isEmpty()) return
    val title =
        when {
            state.live is LiveState.Failed -> R.string.live_notice_failed
            state.live == LiveState.Connecting -> R.string.live_notice_trying
            state.transport == LiveTransport.Snapshots -> R.string.live_notice_stills
            state.transport == LiveTransport.WebRtc -> R.string.live_notice_webrtc
            else -> R.string.live_notice_other_stream
        }
    val lines = state.setbacks.map { setbackText(it, state) }
    NoticeCard(
        message = lines.joinToString("\n\n"),
        modifier = modifier,
        title = stringResource(title),
        actionLabel = stringResource(R.string.live_notice_retry),
        onAction = onRetry,
        announce = true,
    )
}

@Composable
private fun setbackText(
    setback: LiveSetback,
    state: CameraState,
): String {
    val way =
        stringResource(
            when (setback.attempt.transport) {
                LiveTransport.Https -> R.string.transport_https
                LiveTransport.WebRtc -> R.string.transport_webrtc
                LiveTransport.Snapshots -> R.string.transport_stills
            },
        )
    // The stream is named only when the camera has more than one.
    val stream =
        state.streams
            .takeIf { it.size > 1 }
            ?.firstOrNull { it.name == setback.attempt.stream }
            ?.label
    val name = if (stream == null) way else "$way ($stream)"
    return "$name: ${failureText(setback.failure, R.string.video_failed)}"
}

/** Said under a recording shown as still pictures. */
@Composable
internal fun StillsNotice(
    failure: VideoFailure,
    onRetry: () -> Unit,
) {
    NoticeCard(
        message =
            failureText(failure, R.string.video_failed) + "\n\n" + stringResource(R.string.stills_notice_hint),
        title = stringResource(R.string.stills_notice_title),
        actionLabel = stringResource(R.string.stills_notice_retry),
        onAction = onRetry,
        announce = true,
    )
}
