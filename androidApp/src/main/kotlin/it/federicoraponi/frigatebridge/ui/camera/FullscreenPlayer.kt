package it.federicoraponi.frigatebridge.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.media3.common.Player
import it.federicoraponi.frigatebridge.R
import it.federicoraponi.frigatebridge.ui.capture.PhotoButton
import it.federicoraponi.frigatebridge.ui.designsystem.Spacing

/** A single video over the whole sideways screen. */
@Composable
fun FullscreenPlayer(
    /** What is being watched, said by screen readers when full screen opens. */
    title: String,
    player: Player?,
    messages: SnackbarHostState,
    onPhoto: (() -> Unit)?,
    onExit: () -> Unit,
    video: @Composable () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black).semantics { paneTitle = title }) {
        video()
        Box(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(Spacing.lg)) {
            Row(
                modifier = Modifier.align(Alignment.TopEnd),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                onPhoto?.let { PhotoButton(it) }
                ExitFullscreenButton(onExit)
            }
            SnackbarHost(messages, Modifier.align(Alignment.Center))
            PlayButton(player, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
internal fun ExitFullscreenButton(onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, shapes = IconButtonDefaults.shapes()) {
        Icon(
            painterResource(R.drawable.ic_fullscreen_exit),
            contentDescription = stringResource(R.string.video_exit_fullscreen),
        )
    }
}
