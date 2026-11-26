package it.federicoraponi.frigatebridge.ui.recordings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
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
import it.federicoraponi.frigatebridge.R
import it.federicoraponi.frigatebridge.ui.TimelineState
import it.federicoraponi.frigatebridge.ui.TimelineViewModel
import it.federicoraponi.frigatebridge.ui.camera.RecordingTransport
import it.federicoraponi.frigatebridge.ui.designsystem.ConnectedChoice
import it.federicoraponi.frigatebridge.ui.designsystem.PrimaryButton
import it.federicoraponi.frigatebridge.ui.designsystem.Spacing

/** The playback speeds offered. */
val playbackSpeeds = listOf(1f, 2f, 4f, 8f)

private val exportLengths = listOf(30 to R.string.export_30s, 60 to R.string.export_1m, 300 to R.string.export_5m)

/** Everything below the video of the recordings: day, timeline, transport, speed and export. */
@Composable
fun RecordingsPanel(
    state: TimelineState,
    viewModel: TimelineViewModel,
    player: Player?,
    speed: Float,
    onSpeed: (Float) -> Unit,
) {
    var zoom by rememberSaveable { mutableStateOf(TimelineZoom.Hour) }
    var choosingExport by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        // Nothing to show until the days with recordings are known.
        state.day?.let { day ->
            if (state.days.size > 1) {
                ConnectedChoice(
                    labels = state.days.map { dayLabel(it.date) },
                    selected = state.days.indexOfFirst { it.date == day.date },
                    onSelect = { viewModel.selectDay(state.days[it].date) },
                )
            }
            TimelineBar(
                day = day,
                ranges = state.ranges,
                markers = state.markers,
                time = state.time,
                zoom = zoom,
                onSeek = viewModel::seekTo,
                preview = viewModel::preview,
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
            // Nothing to move through on a day without recordings.
            if (state.ranges.isNotEmpty()) {
                RecordingTransport(player, onSkip = viewModel::skip)
                ConnectedChoice(
                    labels = playbackSpeeds.map { "${it.toInt()}×" },
                    selected = playbackSpeeds.indexOf(speed),
                    onSelect = { onSpeed(playbackSpeeds[it]) },
                )
                ExportSection(state.export, onChoose = { choosingExport = true }, onDone = viewModel::clearExport)
            }
        }
    }
    if (choosingExport) {
        ExportSheet(
            start = state.time,
            onDismiss = { choosingExport = false },
            onExport = { start, seconds ->
                choosingExport = false
                viewModel.export(start, seconds)
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
        sheetState =
            rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xl).padding(bottom = Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Text(stringResource(R.string.export_clip), style = MaterialTheme.typography.titleLargeEmphasized)
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
