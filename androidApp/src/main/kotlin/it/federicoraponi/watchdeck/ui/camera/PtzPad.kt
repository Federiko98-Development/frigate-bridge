package it.federicoraponi.watchdeck.ui.camera

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import it.federicoraponi.watchdeck.R
import it.federicoraponi.watchdeck.ui.PtzMove
import it.federicoraponi.watchdeck.ui.designsystem.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// A screen reader cannot hold a button down, so its activation moves the camera for this long.
private const val ACCESSIBLE_MOVE_MILLIS = 400L

/** Arrows in a cross: the camera moves while one is held. */
@Composable
fun DirectionPad(
    onStart: (PtzMove) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    buttonSize: Dp = 56.dp,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    @Composable
    fun Arrow(
        move: PtzMove,
        icon: Int,
        label: Int,
    ) = HoldButton(icon, label, { onStart(move) }, onStop, buttonSize, container, content)

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Arrow(PtzMove.Up, R.drawable.symbol_keyboard_arrow_up, R.string.ptz_up)
        Row(horizontalArrangement = Arrangement.spacedBy(buttonSize)) {
            Arrow(PtzMove.Left, R.drawable.symbol_keyboard_arrow_left, R.string.ptz_left)
            Arrow(PtzMove.Right, R.drawable.symbol_keyboard_arrow_right, R.string.ptz_right)
        }
        Arrow(PtzMove.Down, R.drawable.symbol_keyboard_arrow_down, R.string.ptz_down)
    }
}

@Composable
fun ZoomButtons(
    onStart: (PtzMove) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    buttonSize: Dp = 56.dp,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        HoldButton(
            R.drawable.symbol_zoom_in,
            R.string.ptz_zoom_in,
            { onStart(PtzMove.ZoomIn) },
            onStop,
            buttonSize,
            container,
            content,
        )
        HoldButton(
            R.drawable.symbol_zoom_out,
            R.string.ptz_zoom_out,
            { onStart(PtzMove.ZoomOut) },
            onStop,
            buttonSize,
            container,
            content,
        )
    }
}

/**
 * A button that acts while it is held: [onPress] when the finger goes down, [onRelease] when it leaves, also if
 * the gesture is interrupted or the button disappears. Its shape tightens while pressed.
 */
@Composable
private fun HoldButton(
    icon: Int,
    label: Int,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    size: Dp,
    container: Color,
    content: Color,
) {
    var held by remember { mutableStateOf(false) }
    val press by rememberUpdatedState(onPress)
    val release by rememberUpdatedState(onRelease)
    val scope = rememberCoroutineScope()
    val description = stringResource(label)

    fun end() {
        if (held) {
            held = false
            release()
        }
    }
    // Leaving the screen while moving must still stop the camera.
    DisposableEffect(Unit) { onDispose { end() } }
    Surface(
        modifier =
            Modifier
                .size(size)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown().consume()
                        held = true
                        press()
                        try {
                            waitForUpOrCancellation()?.consume()
                        } finally {
                            end()
                        }
                    }
                }.semantics(mergeDescendants = true) {
                    contentDescription = description
                    role = Role.Button
                    onClick {
                        scope.launch {
                            // Marked as held, so that leaving the screen meanwhile still releases it.
                            held = true
                            press()
                            delay(ACCESSIBLE_MOVE_MILLIS)
                            end()
                        }
                        true
                    }
                },
        shape = if (held) MaterialTheme.shapes.medium else MaterialTheme.shapes.extraExtraLarge,
        color = container,
        contentColor = content,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null)
        }
    }
}
