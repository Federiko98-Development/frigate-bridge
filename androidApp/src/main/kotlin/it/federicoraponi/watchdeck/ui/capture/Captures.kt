package it.federicoraponi.watchdeck.ui.capture

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import it.federicoraponi.watchdeck.R
import it.federicoraponi.watchdeck.server.ExportDownloads
import it.federicoraponi.watchdeck.server.ExportedFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private const val JPEG_QUALITY = 95

/** Saves photos and clips from a screen and tells the user, in [messages], how it went. */
class Captures(
    private val context: Context,
    private val scope: CoroutineScope,
    private val downloads: ExportDownloads,
) {
    val frames = FrameCapture()
    val messages = SnackbarHostState()

    /** Saves the picture on screen, which shows [camera] at [time]. */
    fun takePhoto(
        camera: String,
        time: Long,
    ) {
        scope.launch {
            report(R.string.photo_saved) {
                val frame = frames.source?.invoke() ?: error(context.getString(R.string.photo_no_picture))
                context.saveMedia(MediaKind.Photo, mediaName(camera, time, MediaKind.Photo)) {
                    frame.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
                }
            }
        }
    }

    /** Downloads a clip of [camera] recorded from [time] and saves it. */
    suspend fun saveClip(
        export: ExportedFile,
        camera: String,
        time: Long,
    ) {
        val download = downloads.prepare(export)
        report(R.string.clip_saved) {
            context.saveMedia(MediaKind.Clip, mediaName(camera, time, MediaKind.Clip)) { output ->
                download.into { bytes, count -> output.write(bytes, 0, count) }
            }
        }
    }

    private suspend fun report(
        saved: Int,
        save: suspend () -> SavedMedia,
    ) {
        val media =
            try {
                save()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                val reason = error.message ?: error.javaClass.simpleName
                scope.launch { show(context.getString(R.string.capture_failed, reason)) }
                return
            }
        if (!media.isInGallery) {
            context.share(media)
            return
        }
        // Shown apart, so that saving is over while the message is still there.
        scope.launch {
            if (show(context.getString(saved), context.getString(R.string.share)) == SnackbarResult.ActionPerformed) {
                context.share(media)
            }
        }
    }

    private suspend fun show(
        message: String,
        action: String? = null,
    ): SnackbarResult {
        messages.currentSnackbarData?.dismiss()
        return messages.showSnackbar(message, action, duration = SnackbarDuration.Long)
    }
}

@Composable
fun rememberCaptures(): Captures {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloads = koinInject<ExportDownloads>()
    return remember(context) { Captures(context, scope, downloads) }
}
