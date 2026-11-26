package it.federicoraponi.frigatebridge.ui.capture

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
import androidx.compose.ui.unit.dp
import it.federicoraponi.frigatebridge.R
import it.federicoraponi.frigatebridge.ui.ClipState
import it.federicoraponi.frigatebridge.ui.designsystem.Spacing
import kotlinx.coroutines.delay

@Composable
fun PhotoButton(onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, shapes = IconButtonDefaults.shapes()) {
        Icon(painterResource(R.drawable.ic_photo_camera), contentDescription = stringResource(R.string.photo_take))
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
        enabled = clip != ClipState.Starting && clip != ClipState.Preparing && clip !is ClipState.Ready,
        colors =
            IconButtonDefaults.filledTonalIconToggleButtonColors(
                checkedContainerColor = MaterialTheme.colorScheme.error,
                checkedContentColor = MaterialTheme.colorScheme.onError,
            ),
        shapes = IconButtonDefaults.toggleableShapes(),
    ) {
        Icon(
            painterResource(if (recording) R.drawable.ic_stop else R.drawable.ic_record),
            contentDescription = stringResource(if (recording) R.string.clip_stop else R.string.clip_start),
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
    Surface(
        modifier = modifier,
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
