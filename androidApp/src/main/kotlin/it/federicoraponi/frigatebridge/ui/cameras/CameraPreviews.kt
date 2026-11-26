package it.federicoraponi.frigatebridge.ui.cameras

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import it.federicoraponi.frigatebridge.camera.CameraRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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

/**
 * The current picture of a camera, reloaded while its card is on screen and the screen is in front. Keeps the
 * previous picture when a reload fails.
 */
@Composable
fun previewPicture(
    camera: CameraRef,
    previews: CameraPreviews,
): ImageBitmap? {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val source by rememberUpdatedState(previews)
    val picture by
        produceState<ImageBitmap?>(null, camera, lifecycle, previews.refreshKey) {
            source.cached(camera)?.let { value = decode(it) }
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                do {
                    source.load(camera)?.let { bytes -> decode(bytes)?.let { value = it } }
                    val pause = source.interval()
                    if (pause != null) delay(pause)
                } while (pause != null)
            }
        }
    return picture
}

private suspend fun decode(bytes: ByteArray): ImageBitmap? =
    withContext(Dispatchers.Default) {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }
