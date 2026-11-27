package it.federicoraponi.frigatebridge.ui.recordings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import it.federicoraponi.frigatebridge.R
import it.federicoraponi.frigatebridge.server.ExportDownload
import it.federicoraponi.frigatebridge.server.ExportDownloads
import it.federicoraponi.frigatebridge.server.ExportedFile
import it.federicoraponi.frigatebridge.ui.capture.newSharedFile
import it.federicoraponi.frigatebridge.ui.designsystem.NoticeCard
import it.federicoraponi.frigatebridge.ui.designsystem.StatusText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/** Downloads an export into the cache and offers it to other apps. */
private suspend fun Context.downloadAndShare(
    name: String,
    download: ExportDownload,
) {
    val file =
        withContext(Dispatchers.IO) {
            newSharedFile("exports", name) { output -> download.into { bytes, count -> output.write(bytes, 0, count) } }
        }
    val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
    val send =
        Intent(Intent.ACTION_SEND)
            .setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(send, null))
}

/** Downloads several exports into the cache and offers them together to other apps. */
private suspend fun Context.downloadAndShareAll(downloads: List<Pair<String, ExportDownload>>) {
    val uris =
        withContext(Dispatchers.IO) {
            downloads.map { (name, download) ->
                val file =
                    newSharedFile("exports", name) { output ->
                        download.into { bytes, count -> output.write(bytes, 0, count) }
                    }
                FileProvider.getUriForFile(this@downloadAndShareAll, "$packageName.files", file)
            }
        }
    val send =
        Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType("video/mp4")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // The permission to read reaches every file only through the clip data.
    send.clipData =
        ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    startActivity(Intent.createChooser(send, null))
}

/** Downloads exports to hand them to other apps, and tells how it is going. */
@Stable
class ExportSharing(
    private val context: Context,
    private val scope: CoroutineScope,
    private val downloads: ExportDownloads,
) {
    private var job: Job? = null

    /** A download is in progress: its buttons give way to its progress. */
    var isBusy by mutableStateOf(false)
        private set

    /** Why the last share failed, if it did. */
    var failure by mutableStateOf<String?>(null)
        private set

    fun share(export: ExportedFile) {
        failure = null
        val download = downloads.prepare(export)
        whileBusy {
            try {
                context.downloadAndShare(export.fileName, download)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                failure = error.message ?: error.javaClass.simpleName
            }
        }
    }

    /** Hands several files over together, then calls [onShared]. */
    fun shareAll(
        exports: List<ExportedFile>,
        onShared: () -> Unit,
    ) {
        failure = null
        val prepared = exports.map { it.fileName to downloads.prepare(it) }
        whileBusy {
            try {
                val only = prepared.singleOrNull()
                if (only != null) {
                    context.downloadAndShare(only.first, only.second)
                } else {
                    context.downloadAndShareAll(prepared)
                }
                onShared()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                failure = error.message ?: error.javaClass.simpleName
            }
        }
    }

    /** Runs [work], another download of the same file, showing the progress meanwhile. */
    fun whileBusy(work: suspend () -> Unit) {
        isBusy = true
        job =
            scope.launch {
                try {
                    work()
                } finally {
                    isBusy = false
                }
            }
    }

    /** Stops the download in progress. The export stays on the server. */
    fun cancel() {
        job?.cancel()
    }
}

@Composable
fun rememberExportSharing(key: Any? = null): ExportSharing {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloads = koinInject<ExportDownloads>()
    return remember(context, key) { ExportSharing(context, scope, downloads) }
}

/** Said while an export is being downloaded. */
@Composable
fun DownloadProgress(onCancel: () -> Unit) {
    StatusText(stringResource(R.string.export_downloading))
    LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
    TextButton(onClick = onCancel, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) }
}

/** Why the last share failed, if it did. */
@Composable
fun SharingFailure(failure: String?) {
    failure?.let { NoticeCard(stringResource(R.string.export_failed) + "\n" + it, announce = true) }
}
