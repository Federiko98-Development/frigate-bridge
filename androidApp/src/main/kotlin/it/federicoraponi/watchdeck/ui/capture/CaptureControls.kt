package it.federicoraponi.watchdeck.ui.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.dp
import it.federicoraponi.watchdeck.R
import it.federicoraponi.watchdeck.ui.ClipState
import it.federicoraponi.watchdeck.ui.designsystem.Spacing
import kotlinx.coroutines.delay

@Composable
fun PhotoButton(onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, shapes = IconButtonDefaults.shapes()) {
        Icon(painterResource(R.drawable.symbol_photo_camera), contentDescription = stringResource(R.string.photo_take))
    }
}

/** Starts and stops recording a clip. It waits while the server starts or prepares one. */
@Composable
fun ClipButton(
    clip: ClipState,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val recording = clip is ClipState.Recording
    FilledTonalIconToggleButton(
        checked = recording,
        onCheckedChange = { if (it) onStart() else onStop() },
        enabled =
            clip != ClipState.Starting &&
                clip != ClipState.Preparing &&
                clip !is ClipState.Ready &&
                clip != ClipState.Saving,
        colors =
            IconButtonDefaults.filledTonalIconToggleButtonColors(
                checkedContainerColor = MaterialTheme.colorScheme.error,
                checkedContentColor = MaterialTheme.colorScheme.onError,
            ),
        shapes = IconButtonDefaults.toggleableShapes(),
    ) {
        Icon(
            painterResource(if (recording) R.drawable.symbol_stop else R.drawable.symbol_fiber_manual_record),
            // The state is said apart, as on or off.
            contentDescription = stringResource(R.string.clip_start),
            tint = if (recording) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.error,
        )
    }
}

/** A red dot with the time recorded so far. */
@Composable
fun RecordingBadge(
    startedAt: Long,
    modifier: Modifier = Modifier,
) {
    val seconds by
        produceState(0L, startedAt) {
            while (true) {
                value = (System.currentTimeMillis() / 1_000 - startedAt).coerceAtLeast(0)
                delay(500)
            }
        }
    val announcement = stringResource(R.string.clip_recording_now)
    Surface(
        // Said once when it appears: the time going by would be said again every second.
        modifier =
            modifier.clearAndSetSemantics {
                contentDescription = announcement
                liveRegion = LiveRegionMode.Polite
            },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Box(modifier = Modifier.size(8.dp).background(MaterialTheme.colorScheme.error, CircleShape))
            Text(
                stringResource(R.string.clip_recording, "%d:%02d".format(seconds / 60, seconds % 60)),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
