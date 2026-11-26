package it.federicoraponi.frigatebridge.ui.camera

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import it.federicoraponi.frigatebridge.R
import it.federicoraponi.frigatebridge.ui.designsystem.Spacing

private const val MAXIMUM_SCALE = 5f
private const val DOUBLE_TAP_SCALE = 2.5f

// One step of the zoom offered to screen readers, which cannot pinch.
private const val ACCESSIBLE_ZOOM_STEP = 1.5f

// One step of their way around a magnified picture, as a part of its area.
private const val ACCESSIBLE_MOVE_STEP = 0.25f

/** The magnification of a picture on the phone: nothing to do with the camera's own zoom. */
@Stable
private class DigitalZoom {
    var scale by mutableStateOf(1f)
        private set

    /** How far the centre of the picture is from the centre of its area, in pixels. */
    var offset by mutableStateOf(Offset.Zero)
        private set

    var area = IntSize.Zero
        set(value) {
            // Entering or leaving full screen changes everything the offset was measured on.
            if (value != field) reset()
            field = value
        }

    val isZoomed: Boolean get() = scale > 1.01f

    val canZoomIn: Boolean get() = scale < MAXIMUM_SCALE - 0.01f

    private val centre get() = Offset(area.width / 2f, area.height / 2f)

    /** Magnifies by [factor] keeping the point under [at] where it is, then moves by [pan]. */
    fun change(
        at: Offset,
        pan: Offset,
        factor: Float,
    ) {
        val next = (scale * factor).coerceIn(1f, MAXIMUM_SCALE)
        val from = at - centre
        val moved = from - (from - offset) * (next / scale) + pan
        // The picture cannot be dragged away from the edges of its area.
        val room = Offset(area.width.toFloat(), area.height.toFloat()) * ((next - 1f) / 2f)
        scale = next
        offset = Offset(moved.x.coerceIn(-room.x, room.x), moved.y.coerceIn(-room.y, room.y))
    }

    /** Magnifies by [factor] around the point in the middle of the area. */
    fun zoomBy(factor: Float) = change(centre, Offset.Zero, factor)

    /** Moves the view by these parts of the area: negative looks further right or down. */
    fun move(
        across: Float,
        down: Float,
    ) = change(centre, Offset(area.width * across, area.height * down), 1f)

    fun toggle(at: Offset) {
        if (isZoomed) reset() else change(at, Offset.Zero, DOUBLE_TAP_SCALE / scale)
    }

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }
}

/**
 * The area of a video that can be pinched, or tapped twice, to look closer, and then dragged around. [content]
 * gives the modifier it receives to the picture; whatever else it draws keeps its size. A new [resetKey] shows the
 * whole picture again.
 */
@Composable
fun ZoomableVideo(
    resetKey: Any?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(picture: Modifier) -> Unit,
) {
    val zoom = remember(resetKey) { DigitalZoom() }
    val gestures =
        if (enabled) {
            Modifier
                .pointerInput(zoom) { detectTapGestures(onDoubleTap = zoom::toggle) }
                .pointerInput(zoom) { detectZoom(zoom) }
        } else {
            Modifier
        }
    val description = stringResource(R.string.picture_description)
    val zoomIn = stringResource(R.string.picture_zoom_in)
    val zoomOut = stringResource(R.string.picture_zoom_out)
    val reset = stringResource(R.string.picture_zoom_reset)
    // The way to look, with how the picture moves under the view to get there.
    val moves =
        listOf(
            stringResource(R.string.picture_look_left) to Offset(ACCESSIBLE_MOVE_STEP, 0f),
            stringResource(R.string.picture_look_right) to Offset(-ACCESSIBLE_MOVE_STEP, 0f),
            stringResource(R.string.picture_look_up) to Offset(0f, ACCESSIBLE_MOVE_STEP),
            stringResource(R.string.picture_look_down) to Offset(0f, -ACCESSIBLE_MOVE_STEP),
        )
    // Read here, so that the actions offered change with them; they change far less often than the scale.
    val zoomedState = remember(zoom) { derivedStateOf { zoom.isZoomed } }
    val canZoomInState = remember(zoom) { derivedStateOf { zoom.canZoomIn } }
    val zoomed = zoomedState.value
    val canZoomIn = canZoomInState.value
    // Not merged: a failure message and its button over the picture stay reachable on their own.
    val accessible =
        Modifier.semantics {
            contentDescription = description
            if (enabled) {
                customActions =
                    listOfNotNull(
                        CustomAccessibilityAction(zoomIn) {
                            zoom.zoomBy(ACCESSIBLE_ZOOM_STEP)
                            true
                        }.takeIf { canZoomIn },
                        CustomAccessibilityAction(zoomOut) {
                            zoom.zoomBy(1f / ACCESSIBLE_ZOOM_STEP)
                            true
                        }.takeIf { zoomed },
                        CustomAccessibilityAction(reset) {
                            zoom.reset()
                            true
                        }.takeIf { zoomed },
                    ) +
                    moves.takeIf { zoomed }.orEmpty().map { (label, by) ->
                        CustomAccessibilityAction(label) {
                            zoom.move(by.x, by.y)
                            true
                        }
                    }
            }
        }
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .clipToBounds()
                .onSizeChanged { zoom.area = it }
                .then(accessible)
                .then(gestures),
        contentAlignment = Alignment.Center,
    ) {
        content(
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoom.scale
                scaleY = zoom.scale
                translationX = zoom.offset.x
                translationY = zoom.offset.y
            },
        )
        if (enabled && zoomed) {
            ZoomLevel(zoom.scale, onReset = zoom::reset, Modifier.align(Alignment.CenterStart).padding(Spacing.sm))
        }
    }
}

// One finger is left to the page while the picture is whole, so that the page still scrolls over the video.
private suspend fun PointerInputScope.detectZoom(zoom: DigitalZoom) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var travelled = Offset.Zero
        do {
            val event = awaitPointerEvent()
            val pinching = event.changes.count { it.pressed } > 1
            if (pinching || zoom.isZoomed) {
                val pan = event.calculatePan()
                travelled += pan
                val centroid = event.calculateCentroid()
                // A finger that barely moves may be tapping.
                val meant = pinching || travelled.getDistance() > viewConfiguration.touchSlop
                if (meant && centroid.isSpecified) {
                    zoom.change(centroid, pan, event.calculateZoom())
                    event.changes.filter { it.positionChanged() }.forEach { it.consume() }
                }
            }
        } while (event.changes.any { it.pressed })
    }
}

/** How much the picture is magnified; tapping shows it whole again. */
@Composable
private fun ZoomLevel(
    scale: Float,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = LocalConfiguration.current.locales[0]
    FilledTonalButton(onClick = onReset, shapes = ButtonDefaults.shapes(), modifier = modifier) {
        Text("%.1f×".format(locale, scale))
        Icon(
            Icons.Filled.Close,
            contentDescription = stringResource(R.string.picture_zoom_reset),
            modifier = Modifier.padding(start = Spacing.xs),
        )
    }
}
