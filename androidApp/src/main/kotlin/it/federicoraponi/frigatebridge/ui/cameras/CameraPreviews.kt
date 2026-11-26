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

// Often enough to look alive, rare enough for a grid of cameras on mobile data.
private const val REFRESH_MILLIS = 1_500L

/** Where the pictures of the camera grid come from. */
class CameraPreviews(
    val cached: (CameraRef) -> ByteArray?,
    val load: suspend (CameraRef) -> ByteArray?,
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
        produceState<ImageBitmap?>(null, camera, lifecycle) {
            source.cached(camera)?.let { value = decode(it) }
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    val bytes = source.load(camera)
                    val loaded = bytes?.let { decode(it) }
                    if (loaded != null) value = loaded
                    delay(REFRESH_MILLIS)
                }
            }
        }
    return picture
}

private suspend fun decode(bytes: ByteArray): ImageBitmap? =
    withContext(Dispatchers.Default) {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }
