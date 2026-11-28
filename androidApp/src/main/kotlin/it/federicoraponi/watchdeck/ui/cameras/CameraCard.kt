package it.federicoraponi.watchdeck.ui.cameras

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.federicoraponi.watchdeck.R
import it.federicoraponi.watchdeck.camera.Camera
import it.federicoraponi.watchdeck.ui.designsystem.Spacing

private val RecordingColor = Color(0xFFFF5449)

private val ScrimStops =
    arrayOf(
        0f to Color.Transparent,
        0.3f to Color.Black.copy(alpha = 0.7f),
        1f to Color.Black.copy(alpha = 0.85f),
    )

/** A camera: its picture fills the card, with the name and what it is doing written over the bottom. */
@Composable
fun CameraCard(
    camera: Camera,
    previews: CameraPreviews,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Offers the quick actions. */
    onHold: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.large
    val holdLabel = stringResource(R.string.quick_actions)
    val haptics = LocalHapticFeedback.current
    Surface(
        // A clickable surface keeps its clicks to itself, so holding it could not be heard.
        modifier =
            modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(shape)
                .combinedClickable(
                    role = Role.Button,
                    onLongClickLabel = holdLabel,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onHold()
                    },
                    onClick = onClick,
                ).semantics(mergeDescendants = true) {},
        shape = shape,
        color = colors.surfaceContainerHighest,
    ) {
        Box {
            val preview = if (camera.enabled) previewPicture(camera.ref, previews) else PreviewPicture()
            val picture = preview.image
            if (picture != null) {
                Image(
                    bitmap = picture,
                    // The camera's name is written over the picture.
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Surface(
                    modifier = Modifier.align(Alignment.Center),
                    shape = MaterialShapes.Cookie9Sided.toShape(),
                    color = if (camera.enabled) colors.primaryContainer else colors.surfaceContainer,
                    contentColor = if (camera.enabled) colors.onPrimaryContainer else colors.onSurfaceVariant,
                ) {
                    Icon(
                        painterResource(R.drawable.symbol_videocam),
                        contentDescription = null,
                        modifier = Modifier.padding(Spacing.sm).size(20.dp),
                    )
                }
            }
            if (preview.isFailing) {
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(Spacing.sm),
                    shape = MaterialTheme.shapes.small,
                    color = colors.errorContainer,
                    contentColor = colors.onErrorContainer,
                ) {
                    Text(
                        stringResource(
                            if (picture == null) R.string.preview_unavailable else R.string.preview_stale,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                    )
                }
            }
            Row(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        // Darkens the bottom of the picture so that white text stays readable on it, also over a
                        // white picture: dark enough already where the text starts.
                        .background(Brush.verticalGradient(*ScrimStops))
                        .padding(start = Spacing.md, end = Spacing.sm, top = Spacing.lg, bottom = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(
                    camera.displayName,
                    color = Color.White,
                    style = MaterialTheme.typography.labelLargeEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!camera.enabled) {
                    Text(
                        stringResource(R.string.camera_disabled),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
                } else {
                    if (camera.isDetecting) {
                        Icon(
                            painterResource(R.drawable.symbol_visibility),
                            contentDescription = stringResource(R.string.camera_detecting),
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    if (camera.isRecording) {
                        Icon(
                            painterResource(R.drawable.symbol_fiber_manual_record),
                            contentDescription = stringResource(R.string.camera_recording),
                            tint = RecordingColor,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}
