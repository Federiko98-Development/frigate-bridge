package it.federicoraponi.frigatebridge.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import it.federicoraponi.frigatebridge.R
import it.federicoraponi.frigatebridge.ui.CameraState
import it.federicoraponi.frigatebridge.ui.ClipState
import it.federicoraponi.frigatebridge.ui.TimelineState
import it.federicoraponi.frigatebridge.ui.VideoMode
import it.federicoraponi.frigatebridge.ui.capture.ClipButton
import it.federicoraponi.frigatebridge.ui.capture.PhotoButton
import it.federicoraponi.frigatebridge.ui.capture.RecordingBadge
import it.federicoraponi.frigatebridge.ui.designsystem.Spacing
import it.federicoraponi.frigatebridge.ui.recordings.TimelineBar
import it.federicoraponi.frigatebridge.ui.recordings.TimelineZoom

/** The video over the whole sideways screen, with the controls on top of it. */
@Composable
internal fun FullscreenCamera(
    state: CameraState,
    muted: Boolean,
    hasAudio: Boolean,
    messages: SnackbarHostState,
    actions: CameraActions,
    onExit: () -> Unit,
    recordings: @Composable () -> Unit,
    video: @Composable () -> Unit,
) {
    val overlay = Color.Black.copy(alpha = 0.45f)
    Box(modifier = Modifier.fillMaxSize().background(Color.Black).semantics { paneTitle = state.title }) {
        // The controls come after the drawer, so that they stay on top of it and of the video.
        if (state.mode == VideoMode.Recording) RecordingsDrawer(recordings, video) else video()
        Box(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(Spacing.lg)) {
            Row(
                modifier = Modifier.align(Alignment.TopEnd),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                // The button stays while a recording is on, also if the camera stops allowing new ones.
                if (state.mode == VideoMode.Live && (state.canRecordClip || state.clip is ClipState.Recording)) {
                    ClipButton(state.clip, actions.startClip, actions.stopClip)
                }
                actions.takePhoto?.let { PhotoButton(it) }
                if (state.mode == VideoMode.Live && hasAudio) MuteButton(muted, actions.setMuted)
                ExitFullscreenButton(onExit)
            }
            if (state.mode == VideoMode.Live && state.streams.size > 1) {
                StreamChoice(state, actions.selectStream, Modifier.align(Alignment.TopStart))
            }
            (state.clip as? ClipState.Recording)?.let {
                RecordingBadge(it.startedAt, Modifier.align(Alignment.TopCenter))
            }
            SnackbarHost(messages, Modifier.align(Alignment.Center))
            val ptz = state.ptz
            if (state.mode == VideoMode.Live && ptz != null) {
                if (ptz.canPanAndTilt) {
                    DirectionPad(
                        actions.startMoving,
                        actions.stopMoving,
                        modifier = Modifier.align(Alignment.BottomEnd),
                        buttonSize = 48.dp,
                        container = overlay,
                        content = Color.White,
                    )
                }
                if (ptz.canZoom) {
                    ZoomButtons(
                        actions.startMoving,
                        actions.stopMoving,
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

/**
 * The mark to pull the drawer with, in a lower strip than Material's own handle takes. The sheet makes it a button
 * that opens and closes the drawer, with the same actions for screen readers.
 */
@Composable
private fun DrawerHandle() {
    val description = stringResource(R.string.timeline_drawer)
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(DrawerPeekHeight)
                .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
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
internal fun FullscreenTimeline(
    state: TimelineState,
    player: Player?,
    onSeek: (Long) -> Unit,
    onScrubbed: (Long) -> Unit,
) {
    val day = state.day ?: return
    if (state.ranges.isNotEmpty()) {
        VideoSeekBar(player, Modifier.padding(horizontal = Spacing.lg), onSeeked = onScrubbed)
    }
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
            onSeek = onSeek,
            modifier = Modifier.weight(1f),
        )
    }
}
