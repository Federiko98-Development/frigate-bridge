package it.federicoraponi.watchdeck.ui.designsystem

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Turns the bytes of a JPEG or PNG into a picture, away from the main thread. `null` if they are not one. */
suspend fun decodePicture(bytes: ByteArray): ImageBitmap? =
    withContext(Dispatchers.Default) {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }

/**
 * The small 16:9 picture at the start of a row, loaded by [load] again whenever [keys] change. Decorative: the
 * row says what it shows.
 */
@Composable
fun Thumbnail(
    vararg keys: Any?,
    load: suspend () -> ByteArray?,
) {
    val picture by
        produceState<ImageBitmap?>(null, *keys) {
            val bytes = load() ?: return@produceState
            value = decodePicture(bytes)
        }
    Surface(
        modifier = Modifier.width(112.dp).aspectRatio(16f / 9f),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        val shown = picture
        if (shown != null) {
            Image(shown, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(contentAlignment = Alignment.Center) { Icon(Symbols.Warning, contentDescription = null) }
        }
    }
}
