package it.federicoraponi.frigatebridge.ui.camera

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import it.federicoraponi.frigatebridge.R
import it.federicoraponi.frigatebridge.camera.CameraFeature
import it.federicoraponi.frigatebridge.ui.CameraControls
import it.federicoraponi.frigatebridge.ui.PtzControls
import it.federicoraponi.frigatebridge.ui.PtzMove
import it.federicoraponi.frigatebridge.ui.designsystem.ListGroup
import it.federicoraponi.frigatebridge.ui.designsystem.NoticeCard
import it.federicoraponi.frigatebridge.ui.designsystem.Spacing
import it.federicoraponi.frigatebridge.ui.designsystem.problemText

/** Movement of a camera: arrows, zoom and the saved positions. */
@Composable
fun PtzPanel(
    ptz: PtzControls,
    onStart: (PtzMove) -> Unit,
    onStop: () -> Unit,
    onPreset: (String) -> Unit,
) {
    ListGroup(title = stringResource(R.string.ptz_title)) {
        if (ptz.canPanAndTilt || ptz.canZoom) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(
                    modifier = Modifier.padding(Spacing.lg),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (ptz.canPanAndTilt) DirectionPad(onStart, onStop)
                    if (ptz.canZoom) ZoomButtons(onStart, onStop)
                }
            }
        }
        if (ptz.presets.isNotEmpty()) {
            Text(
                stringResource(R.string.ptz_presets),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = Spacing.lg, top = Spacing.sm),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                ptz.presets.forEach { preset ->
                    FilledTonalButton(onClick = { onPreset(preset) }, shapes = ButtonDefaults.shapes()) {
                        Text(preset.replace('_', ' '))
                    }
                }
            }
        }
    }
}

private val features =
    listOf(
        CameraFeature.Detection to R.string.feature_detection,
        CameraFeature.Recording to R.string.feature_recording,
        CameraFeature.Snapshots to R.string.feature_snapshots,
        CameraFeature.Motion to R.string.feature_motion,
    )

/** The switches of a camera. */
@Composable
fun FeaturePanel(
    controls: CameraControls,
    onChange: (CameraFeature, Boolean) -> Unit,
) {
    ListGroup(title = stringResource(R.string.controls_title)) {
        features.forEachIndexed { index, (feature, label) ->
            val on = feature in controls.enabled
            val locked = feature !in controls.switchable
            val canToggle = controls.isAllowed && !controls.isApplying && !locked
            val explanation =
                when {
                    !locked -> null
                    feature == CameraFeature.Recording -> R.string.feature_recording_locked
                    else -> R.string.feature_motion_locked
                }
            SegmentedListItem(
                checked = on,
                onCheckedChange = { onChange(feature, it) },
                enabled = canToggle,
                shapes = ListItemDefaults.segmentedShapes(index = index, count = features.size),
                colors =
                    ListItemDefaults.segmentedColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        selectedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        selectedContentColor = MaterialTheme.colorScheme.onSurface,
                        selectedSupportingContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                supportingContent =
                    explanation?.let { { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) } },
                trailingContent = { Switch(checked = on, onCheckedChange = null, enabled = canToggle) },
            ) { Text(stringResource(label), style = MaterialTheme.typography.titleSmall) }
        }
    }
    val note = if (controls.isAllowed) R.string.controls_temporary else R.string.controls_admin_only
    Text(
        stringResource(note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.lg),
    )
    controls.failure?.let { failure ->
        val problem = failure.problem
        val text = if (problem != null) problemText(problem) else stringResource(R.string.video_failed)
        NoticeCard(message = listOfNotNull(text, failure.detail).joinToString("\n"))
    }
}
