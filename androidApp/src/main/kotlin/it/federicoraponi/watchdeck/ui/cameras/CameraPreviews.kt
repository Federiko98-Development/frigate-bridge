package it.federicoraponi.watchdeck.ui.cameras

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import it.federicoraponi.watchdeck.camera.CameraRef
import it.federicoraponi.watchdeck.ui.designsystem.decodePicture
import kotlinx.coroutines.delay

/** Where the pictures of the camera grid come from. */
class CameraPreviews(
    val cached: (CameraRef) -> ByteArray?,
    val load: suspend (CameraRef) -> ByteArray?,
    /** The pause before the next picture, or `null` to load only one. */
    val interval: () -> Long? = { null },
    /** Changing it starts loading again, e.g. after the refresh setting changed. */
    val refreshKey: Any = Unit,
) {
    companion object {
        val None = CameraPreviews({ null }, { null })
    }
}

/** What a card has to show: the last picture that arrived, and whether the pictures have stopped arriving. */
data class PreviewPicture(
    val image: ImageBitmap? = null,
    val isFailing: Boolean = false,
)

// A single failure is nothing to tell: the next picture usually arrives.
private const val FAILURES_TO_REPORT = 3

/**
 * The current picture of a camera, reloaded while its card is on screen and the screen is in front. Keeps the
 * previous picture when a reload fails, and says so when several in a row do.
 */
@Composable
fun previewPicture(
    camera: CameraRef,
    previews: CameraPreviews,
): PreviewPicture {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val source by rememberUpdatedState(previews)
    val picture by
        produceState(PreviewPicture(), camera, lifecycle, previews.refreshKey) {
            source.cached(camera)?.let { value = PreviewPicture(decodePicture(it)) }
            var failures = 0
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                do {
                    val next = source.load(camera)?.let { decodePicture(it) }
                    val pause = source.interval()
                    if (next != null) {
                        failures = 0
                        value = PreviewPicture(next)
                    } else {
                        // Loaded only once, there is no later try to wait for.
                        failures = if (pause == null) FAILURES_TO_REPORT else failures + 1
                        if (failures >= FAILURES_TO_REPORT) value = value.copy(isFailing = true)
                    }
                    if (pause != null) delay(pause)
                } while (pause != null)
            }
        }
    return picture
}
