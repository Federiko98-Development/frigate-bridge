package it.federicoraponi.frigatebridge.ui.camera

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.media3.common.Player
import kotlinx.coroutines.delay

// A wait shorter than this would only make the picture blink.
private const val PATIENCE_MILLIS = 200L

/** The loading indicator, see-through, over the picture of a [player] that is waiting for more video. */
@Composable
fun BufferingOverlay(player: Player) {
    var waiting by remember(player) { mutableStateOf(player.playbackState == Player.STATE_BUFFERING) }
    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    waiting = playbackState == Player.STATE_BUFFERING
                }
            }
        player.addListener(listener)
        // It may have changed between composing and listening.
        waiting = player.playbackState == Player.STATE_BUFFERING
        onDispose { player.removeListener(listener) }
    }
    var shown by remember(player) { mutableStateOf(false) }
    LaunchedEffect(player, waiting) {
        if (waiting) delay(PATIENCE_MILLIS)
        shown = waiting
    }
    AnimatedVisibility(visible = shown, enter = fadeIn(), exit = fadeOut()) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)),
            contentAlignment = Alignment.Center,
        ) {
            ContainedLoadingIndicator(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
            )
        }
    }
}
