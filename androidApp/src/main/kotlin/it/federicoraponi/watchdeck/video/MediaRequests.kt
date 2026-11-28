package it.federicoraponi.watchdeck.video

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import it.federicoraponi.watchdeck.server.MediaAuthorization
import it.federicoraponi.watchdeck.server.acceptingCertificate
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * The client the players use. It follows no redirect: one means that a sign-in is asked for, and the player must
 * report that instead of reading a login page as video.
 */
fun mediaClient(certificateSha256: String?): OkHttpClient =
    OkHttpClient
        .Builder()
        .acceptingCertificate(certificateSha256)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

/** The headers of this moment. Call it from a loading thread: renewing a token takes a request. */
fun MediaAuthorization.headersNow(): Map<String, String> =
    try {
        runBlocking { headers() }
    } catch (failure: Exception) {
        // A player reports only input and output failures; the message keeps the reason.
        throw IOException(failure.message, failure)
    }

/**
 * Downloads the media of [url], signing each request with the headers of that moment, since a video outlasts a
 * short-lived token. Requests to any other host go out unsigned.
 */
@OptIn(UnstableApi::class)
fun signedDataSource(
    url: String,
    authorization: MediaAuthorization,
    certificateSha256: String?,
): DataSource.Factory {
    val server = Uri.parse(url).authority
    return ResolvingDataSource.Factory(OkHttpDataSource.Factory(mediaClient(certificateSha256))) { spec ->
        if (spec.uri.authority == server) spec.withAdditionalHeaders(authorization.headersNow()) else spec
    }
}
