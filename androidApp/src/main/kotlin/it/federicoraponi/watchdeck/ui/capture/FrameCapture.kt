package it.federicoraponi.watchdeck.ui.capture

import android.graphics.Bitmap
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import androidx.core.view.children
import androidx.media3.common.Player

/** Takes a picture of the video on screen. Whichever video is showing says how. */
class FrameCapture {
    var source: (suspend () -> Bitmap?)? = null
}

val LocalFrameCapture = compositionLocalOf<FrameCapture?> { null }

/** While on screen, the pictures of [LocalFrameCapture] come from [source]. */
@Composable
fun ProvidesFrames(source: suspend () -> Bitmap?) {
    val capture = LocalFrameCapture.current ?: return
    val latest by rememberUpdatedState(source)
    DisposableEffect(capture) {
        val provider: suspend () -> Bitmap? = { latest() }
        capture.source = provider
        onDispose { if (capture.source === provider) capture.source = null }
    }
}

/** The pictures of a [player] drawing on a texture view, at the size of its video rather than of the screen. */
@Composable
fun ProvidesPlayerFrames(player: Player) {
    val view = LocalView.current
    ProvidesFrames {
        val picture = view.rootView.findTextureView()
        val size = player.videoSize
        // Some cameras send pixels that are not square.
        val width = (size.width * size.pixelWidthHeightRatio).toInt()
        if (width > 0 && size.height > 0) picture?.getBitmap(width, size.height) else picture?.bitmap
    }
}

// The player's own view is not handed out: it is the one texture view on screen.
private fun View.findTextureView(): TextureView? =
    when (this) {
        is TextureView -> takeIf { it.isShown && it.isAvailable }
        is ViewGroup -> children.firstNotNullOfOrNull { it.findTextureView() }
        else -> null
    }
