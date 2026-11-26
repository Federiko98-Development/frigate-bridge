package it.federicoraponi.frigatebridge.video

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The codecs asked for, written the way go2rtc recognises them. `flac` is how go2rtc delivers G.711 camera
 * audio; it needs [LiveRenderersFactory], because Android's own FLAC decoders cannot play it.
 */
private const val CODECS = "avc1.640029,hvc1.1.6.L153.B0,mp4a.40.2,opus,flac"
private const val SILENCE_TIMEOUT_SECONDS = 10L
private const val NORMAL_CLOSURE = 1000

private sealed interface Incoming {
    class Data(
        val bytes: ByteArray,
    ) : Incoming

    class Failure(
        val reason: String,
    ) : Incoming
}

/**
 * Reads the fragmented MP4 that go2rtc sends over a WebSocket as one endless file, which a progressive media
 * source can play. A live stream cannot be resumed: any interruption is an error.
 */
@OptIn(UnstableApi::class)
class WebSocketDataSource(
    private val http: OkHttpClient,
    private val headers: Map<String, String>,
) : BaseDataSource(true) {
    private val incoming = LinkedBlockingQueue<Incoming>()
    private var socket: WebSocket? = null
    private var uri: Uri? = null
    private var chunk = ByteArray(0)
    private var chunkOffset = 0

    override fun open(dataSpec: DataSpec): Long {
        if (dataSpec.position != 0L) throw IOException("A live stream cannot be resumed")
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val request = Request.Builder().url(dataSpec.uri.toString())
        headers.forEach { (name, value) -> request.header(name, value) }
        socket = http.newWebSocket(request.build(), Listener())
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        while (chunkOffset == chunk.size) {
            val next =
                try {
                    incoming.poll(SILENCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    throw InterruptedIOException()
                }
            when (next) {
                is Incoming.Data -> {
                    chunk = next.bytes
                    chunkOffset = 0
                }
                is Incoming.Failure -> throw IOException(next.reason)
                null -> throw IOException("No data from the server for $SILENCE_TIMEOUT_SECONDS seconds")
            }
        }
        val count = minOf(length, chunk.size - chunkOffset)
        chunk.copyInto(buffer, offset, chunkOffset, chunkOffset + count)
        chunkOffset += count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        socket?.close(NORMAL_CLOSURE, null)
        socket?.cancel()
        socket = null
        incoming.clear()
        if (uri != null) {
            uri = null
            transferEnded()
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            webSocket.send(JSONObject().put("type", "mse").put("value", CODECS).toString())
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            // The content type needs no action; an error ends the stream with go2rtc's explanation.
            val message = runCatching { JSONObject(text) }.getOrNull() ?: return
            if (message.optString("type") == "error") {
                incoming.add(Incoming.Failure(message.optString("value")))
            }
        }

        override fun onMessage(
            webSocket: WebSocket,
            bytes: ByteString,
        ) {
            incoming.add(Incoming.Data(bytes.toByteArray()))
        }

        override fun onClosing(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            incoming.add(Incoming.Failure("The server closed the stream ($code)"))
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
        ) {
            val status = response?.let { "HTTP ${it.code}: " }.orEmpty()
            incoming.add(Incoming.Failure(status + (t.message ?: t.javaClass.simpleName)))
        }
    }

    class Factory(
        private val http: OkHttpClient,
        private val headers: Map<String, String>,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = WebSocketDataSource(http, headers)
    }
}
