package it.federicoraponi.frigatebridge.ui.recordings

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import it.federicoraponi.frigatebridge.server.ExportedFile
import it.federicoraponi.frigatebridge.server.acceptingCertificate
import kotlinx.coroutines.Dispatchers
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
