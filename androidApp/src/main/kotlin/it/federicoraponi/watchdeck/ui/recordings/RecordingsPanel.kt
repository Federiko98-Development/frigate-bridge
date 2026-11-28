package it.federicoraponi.watchdeck.ui.recordings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.media3.common.Player
import it.federicoraponi.watchdeck.R
import it.federicoraponi.watchdeck.ui.ExportState
import it.federicoraponi.watchdeck.ui.TimelineState
import it.federicoraponi.watchdeck.ui.camera.FullscreenButton
import it.federicoraponi.watchdeck.ui.camera.RecordingTransport
import it.federicoraponi.watchdeck.ui.camera.VideoSeekBar
import it.federicoraponi.watchdeck.ui.capture.TakePhotoButton
import it.federicoraponi.watchdeck.ui.designsystem.ActionRow
import it.federicoraponi.watchdeck.ui.designsystem.ConnectedChoice
import it.federicoraponi.watchdeck.ui.designsystem.ControlGroup
import it.federicoraponi.watchdeck.ui.designsystem.PrimaryButton
import it.federicoraponi.watchdeck.ui.designsystem.SheetTitle
import it.federicoraponi.watchdeck.ui.designsystem.Spacing
import it.federicoraponi.watchdeck.ui.designsystem.rememberFullSheetState

/** The playback speeds offered. */
val playbackSpeeds = listOf(1f, 2f, 4f, 8f)

private val exportLengths = listOf(30 to R.string.export_30s, 60 to R.string.export_1m, 300 to R.string.export_5m)

/** What the controls under the recordings can ask for. */
data class RecordingsActions(
    val selectDay: (String) -> Unit = {},
    val seekTo: (Long) -> Unit = {},
    val skip: (Int) -> Unit = {},
    /** The video was dragged to this position of the stretch being played. */
    val scrubbed: (Long) -> Unit = {},
    val export: (start: Long, seconds: Int) -> Unit = { _, _ -> },
    val clearExport: () -> Unit = {},
    val setSpeed: (Float) -> Unit = {},
    /** `null` while there is no picture to take. */
    val takePhoto: (() -> Unit)? = null,
    val fullscreen: () -> Unit = {},
)

/** Everything below the video of the recordings: date and full screen, timeline, transport, photo and export. */
@Composable
fun RecordingsPanel(
    state: TimelineState,
    player: Player?,
    speed: Float,
    actions: RecordingsActions,
) {
    var zoom by rememberSaveable { mutableStateOf(TimelineZoom.Hour) }
    var choosingExport by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        // Full screen is there before the days with recordings are known, the day only once they are.
        ActionRow {
            state.day?.let { day ->
                DaySelector(
                    selected = day.date,
                    available = state.days.map { it.date },
                    onSelect = actions.selectDay,
                )
            }
            FullscreenButton(actions.fullscreen)
        }
        state.day?.let { day ->
            // Where the video is in the day, and how much of the day the strip shows.
            ControlGroup {
                TimelineBar(
                    day = day,
                    ranges = state.ranges,
                    markers = state.markers,
                    time = state.time,
                    zoom = zoom,
                    onSeek = actions.seekTo,
                )
                ConnectedChoice(
                    labels =
                        listOf(
                            stringResource(R.string.zoom_minutes),
                            stringResource(R.string.zoom_hour),
                            stringResource(R.string.zoom_hours),
                        ),
                    selected = zoom.ordinal,
                    onSelect = { zoom = TimelineZoom.entries[it] },
                )
            }
            // Nothing to move through on a day without recordings.
            if (state.ranges.isNotEmpty()) {
                ControlGroup {
                    VideoSeekBar(player, onSeeked = actions.scrubbed)
                    RecordingTransport(player, onSkip = actions.skip)
                    ConnectedChoice(
                        labels = playbackSpeeds.map { "${it.toInt()}×" },
                        selected = playbackSpeeds.indexOf(speed),
                        onSelect = { actions.setSpeed(playbackSpeeds[it]) },
                    )
                }
                // What can be kept from the video. An export under way takes the place of its button, underneath.
                val canExport = state.export == ExportState.None
                if (actions.takePhoto != null || canExport) {
                    ActionRow {
                        actions.takePhoto?.let { TakePhotoButton(it) }
                        if (canExport) ExportButton(onChoose = { choosingExport = true })
                    }
                }
                ExportStatus(state.export, onDone = actions.clearExport)
            }
        }
    }
    if (choosingExport) {
        ExportSheet(
            start = state.time,
            onDismiss = { choosingExport = false },
            onExport = { start, seconds ->
                choosingExport = false
                actions.export(start, seconds)
            },
        )
    }
}

@Composable
private fun ExportSheet(
    start: Long,
    onDismiss: () -> Unit,
    onExport: (start: Long, seconds: Int) -> Unit,
) {
    // The moment is fixed when the sheet opens: the video goes on behind it.
    val from = remember { start }
    var chosen by remember { mutableIntStateOf(1) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberFullSheetState(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xl).padding(bottom = Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            SheetTitle(stringResource(R.string.export_clip))
            Text(stringResource(R.string.export_from, clockTime(from)), textAlign = TextAlign.Center)
            ConnectedChoice(
                labels = exportLengths.map { stringResource(it.second) },
                selected = chosen,
                onSelect = { chosen = it },
            )
            Text(
                stringResource(R.string.export_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            PrimaryButton(
                text = stringResource(R.string.export_start),
                onClick = { onExport(from, exportLengths[chosen].first) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
