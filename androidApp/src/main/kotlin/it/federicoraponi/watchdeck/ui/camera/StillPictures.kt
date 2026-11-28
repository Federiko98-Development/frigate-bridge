package it.federicoraponi.watchdeck.ui.camera

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import it.federicoraponi.watchdeck.R
import it.federicoraponi.watchdeck.ui.designsystem.decodePicture
import kotlinx.coroutines.delay

private const val SNAPSHOT_INTERVAL_MILLIS = 1_000L

/** The live view's last resort: the camera's latest picture, asked again while the screen is in front. */
@Composable
internal fun SnapshotVideo(
    connectionKey: Any,
    load: suspend () -> ByteArray?,
    modifier: Modifier = Modifier,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val source by rememberUpdatedState(load)
    var picture by remember(connectionKey) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(connectionKey, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                source()?.let { bytes -> decodePicture(bytes)?.let { picture = it } }
                delay(SNAPSHOT_INTERVAL_MILLIS)
            }
        }
    }
    Still(picture, modifier)
}

/** The recorded picture of [time], in place of a video that does not play. */
@Composable
internal fun RecordingStill(
    time: Long,
    load: suspend (Long) -> ByteArray?,
    modifier: Modifier = Modifier,
) {
    val source by rememberUpdatedState(load)
    var picture by remember { mutableStateOf<ImageBitmap?>(null) }
    var isMissing by remember { mutableStateOf(false) }
    LaunchedEffect(time) {
        val loaded = source(time)?.let { decodePicture(it) }
        // The picture of the moment before is not left to stand for this one.
        picture = loaded
        isMissing = loaded == null
    }
    if (isMissing) {
        OverVideo(
            stringResource(R.string.video_still_missing),
            announce = true,
        )
    } else {
        Still(picture, modifier)
    }
}

@Composable
private fun Still(
    picture: ImageBitmap?,
    modifier: Modifier,
) {
    if (picture == null) {
        ContainedLoadingIndicator()
    } else {
        Image(picture, contentDescription = null, contentScale = ContentScale.Fit, modifier = modifier.fillMaxSize())
    }
}
