package it.federicoraponi.frigatebridge.ui.capture

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val FOLDER = "Frigate Bridge"

enum class MediaKind(
    val mimeType: String,
    val extension: String,
) {
    Photo("image/jpeg", "jpg"),
    Clip("video/mp4", "mp4"),
}

/** A photo or clip kept on the phone. */
data class SavedMedia(
    val uri: Uri,
    val kind: MediaKind,
    /** `false` where the app cannot add to the gallery: the file is only there to be shared. */
    val isInGallery: Boolean,
)

/** A name that tells the camera and the moment, e.g. `Front door 2026-01-31 18.05.09.jpg`. */
fun mediaName(
    camera: String,
    time: Long,
    kind: MediaKind,
): String {
    val moment =
        DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH.mm.ss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochSecond(time))
    return "${camera.replace('/', '-')} $moment.${kind.extension}"
}

/**
 * Keeps what [write] produces: in the phone's gallery from Android 10, which needs no permission; before that, in a
 * file that can be shared.
 */
suspend fun Context.saveMedia(
    kind: MediaKind,
    name: String,
    write: suspend (OutputStream) -> Unit,
): SavedMedia =
    withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            addToGallery(kind, name, write)
        } else {
            val file = newSharedFile("captures", name, write)
            val uri = FileProvider.getUriForFile(this@saveMedia, "$packageName.files", file)
            SavedMedia(uri, kind, isInGallery = false)
        }
    }

// Long after any app that was handed a file has read it.
private const val KEEP_SHARED_MILLIS = 24 * 60 * 60 * 1_000L

/**
 * A new file of the cache to hand to other apps, made by [write] under a folder of its own, so that one still being
 * read is never touched. Older ones are removed: videos are large. Call it off the main thread.
 */
internal suspend fun Context.newSharedFile(
    folder: String,
    name: String,
    write: suspend (OutputStream) -> Unit,
): File {
    val root = File(cacheDir, folder)
    val limit = System.currentTimeMillis() - KEEP_SHARED_MILLIS
    root.listFiles()?.filter { it.lastModified() < limit }?.forEach { it.deleteRecursively() }
    val own = File(root, System.nanoTime().toString()).apply { mkdirs() }
    val target = File(own, File(name).name)
    val partial = File(own, "${target.name}.part")
    var complete = false
    try {
        partial.outputStream().use { write(it) }
        complete = partial.renameTo(target)
        if (!complete) throw IOException("The file could not be saved")
    } finally {
        if (!complete) own.deleteRecursively()
    }
    return target
}

@RequiresApi(Build.VERSION_CODES.Q)
private suspend fun Context.addToGallery(
    kind: MediaKind,
    name: String,
    write: suspend (OutputStream) -> Unit,
): SavedMedia {
    val volume = MediaStore.VOLUME_EXTERNAL_PRIMARY
    val photo = kind == MediaKind.Photo
    val collection =
        if (photo) MediaStore.Images.Media.getContentUri(volume) else MediaStore.Video.Media.getContentUri(volume)
    val directory = if (photo) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_MOVIES
    val values =
        ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, kind.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$directory/$FOLDER")
            // Hidden from the gallery until it is complete.
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    val uri = contentResolver.insert(collection, values) ?: throw IOException("The gallery refused the file")
    var complete = false
    try {
        val output = contentResolver.openOutputStream(uri) ?: throw IOException("The gallery refused the file")
        output.use { write(it) }
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        contentResolver.update(uri, done, null, null)
        complete = true
    } finally {
        if (!complete) contentResolver.delete(uri, null, null)
    }
    return SavedMedia(uri, kind, isInGallery = true)
}

/** Offers a saved photo or clip to other apps. */
fun Context.share(media: SavedMedia) {
    val send =
        Intent(Intent.ACTION_SEND)
            .setType(media.kind.mimeType)
            .putExtra(Intent.EXTRA_STREAM, media.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(send, null))
}
