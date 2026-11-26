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
import androidx.compose.ui.unit.IntSize
import it.federicoraponi.frigatebridge.R
import it.federicoraponi.frigatebridge.ui.designsystem.Spacing

private const val MAXIMUM_SCALE = 5f
private const val DOUBLE_TAP_SCALE = 2.5f

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
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .clipToBounds()
                .onSizeChanged { zoom.area = it }
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
        if (enabled && zoom.isZoomed) {
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
