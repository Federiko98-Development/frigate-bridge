package it.federicoraponi.frigatebridge.ui.recordings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearWavyProgressIndicator
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
import it.federicoraponi.frigatebridge.server.ExportedFile
import it.federicoraponi.frigatebridge.server.acceptingCertificate
import it.federicoraponi.frigatebridge.ui.designsystem.NoticeCard
import it.federicoraponi.frigatebridge.ui.designsystem.StatusText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** Downloads an export from the server into the cache and offers it to other apps. */
suspend fun Context.downloadAndShare(export: ExportedFile) {
    val file =
        withContext(Dispatchers.IO) {
            val folder = File(cacheDir, "exports").apply { mkdirs() }
            // Only the last export is kept: videos are large.
            folder.listFiles()?.forEach { it.delete() }
            val target = File(folder, File(export.fileName).name)
            target.outputStream().use { download(export, it) }
            target
        }
    val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
    val send =
        Intent(Intent.ACTION_SEND)
            .setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(send, null))
}

/** Copies an export from the server into [output]. Blocks: call it off the main thread. */
fun download(
    export: ExportedFile,
    output: OutputStream,
) {
    val request = Request.Builder().url(export.url)
    export.headers.forEach { (name, value) -> request.header(name, value) }
    val http = OkHttpClient.Builder().acceptingCertificate(export.certificateSha256).build()
    http.newCall(request.build()).execute().use { response ->
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        response.body.byteStream().copyTo(output)
    }
}

/** Downloads exports to hand them to other apps, and tells how it is going. */
@Stable
class ExportSharing(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    /** A download is in progress: its buttons give way to its progress. */
    var isBusy by mutableStateOf(false)
        private set

    /** Why the last share failed, if it did. */
    var failure by mutableStateOf<String?>(null)
        private set

    fun share(export: ExportedFile) {
        failure = null
        whileBusy {
            try {
                context.downloadAndShare(export)
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
        scope.launch {
            work()
            isBusy = false
        }
    }
}

@Composable
fun rememberExportSharing(key: Any? = null): ExportSharing {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(context, key) { ExportSharing(context, scope) }
}

/** Said while an export is being downloaded. */
@Composable
fun DownloadProgress() {
    StatusText(stringResource(R.string.export_downloading))
    LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
}

/** Why the last share failed, if it did. */
@Composable
fun SharingFailure(sharing: ExportSharing) {
    sharing.failure?.let { NoticeCard(stringResource(R.string.export_failed) + "\n" + it, announce = true) }
}
