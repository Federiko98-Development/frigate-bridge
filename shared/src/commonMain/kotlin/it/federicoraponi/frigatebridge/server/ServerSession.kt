package it.federicoraponi.frigatebridge.server

import io.ktor.client.HttpClient
import it.federicoraponi.frigatebridge.camera.CameraCatalog
import it.federicoraponi.frigatebridge.camera.cameraCatalog
import it.federicoraponi.frigatebridge.frigate.Credentials
import it.federicoraponi.frigatebridge.frigate.FrigateApi
import it.federicoraponi.frigatebridge.frigate.FrigateError
import it.federicoraponi.frigatebridge.frigate.TokenStore
import it.federicoraponi.frigatebridge.frigate.isSupportedFrigateVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ServerSnapshot(
    /** The address that answered. */
    val url: String,
    val version: String,
    val isSupportedVersion: Boolean,
    val catalog: CameraCatalog,
)

/** What a player needs to show a live stream carried over HTTPS. */
data class HttpsLiveStream(
    val url: String,
    val headers: Map<String, String>,
    val certificateSha256: String?,
)

/** What a player needs to show a recorded interval, with times in seconds since the epoch. */
data class RecordingClip(
    val url: String,
    val headers: Map<String, String>,
    /** The certificate accepted for the server, which the player must accept too. */
    val certificateSha256: String?,
    val startTime: Long,
    val endTime: Long,
)

private const val CLIP_SECONDS = 60L
private const val SECONDS_PER_HOUR = 3_600L
private const val SECONDS_PER_DAY = 86_400L

sealed interface ConnectionState {
    data object Idle : ConnectionState

    data object Connecting : ConnectionState

    data class Connected(
        val snapshot: ServerSnapshot,
    ) : ConnectionState

    data class Failed(
        val error: FrigateError,
    ) : ConnectionState
}

/** The connection to one server, independent from the others: its own HTTP client, token and state. */
class ServerSession(
    val server: Server,
    private val http: HttpClient,
    credentials: suspend () -> Credentials?,
    tokens: TokenStore,
) {
    private val apis = server.addresses.map { url -> url to FrigateApi(http, url, credentials, tokens) }
    private val refreshMutex = Mutex()
    private val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = mutableState.asStateFlow()

    /** Connects and reads cameras and groups. Failures end up in [state] and are not thrown. */
    suspend fun refresh(): ConnectionState =
        refreshMutex.withLock {
            val previous = mutableState.value
            // Keep showing the cameras while an already connected server is being read again.
            if (previous !is ConnectionState.Connected) mutableState.value = ConnectionState.Connecting
            val result =
                try {
                    ConnectionState.Connected(load())
                } catch (cancellation: CancellationException) {
                    mutableState.value = previous
                    throw cancellation
                } catch (error: FrigateError) {
                    ConnectionState.Failed(error)
                }
            mutableState.value = result
            result
        }

    suspend fun webRtcAnswer(
        stream: String,
        offerSdp: String,
    ): String = connectedApi().webRtcAnswer(stream, offerSdp)

    suspend fun httpsLiveStream(stream: String): HttpsLiveStream {
        val api = connectedApi()
        return HttpsLiveStream(api.mp4StreamUrl(stream), api.authorizationHeaders(), server.certificateSha256)
    }

    /** The most recent picture of a camera, as a small JPEG. */
    suspend fun latestFrame(
        camera: String,
        height: Int,
    ): ByteArray = connectedApi().latestFrame(camera, height)

    /** The last minute recorded by a camera, or `null` if it recorded nothing in the past day. */
    suspend fun latestRecording(
        camera: String,
        now: Long,
    ): RecordingClip? {
        val api = connectedApi()
        // The past hour is usually enough and far smaller than a whole day of segments.
        val segments =
            api.recordings(camera, now - SECONDS_PER_HOUR, now).ifEmpty {
                api.recordings(camera, now - SECONDS_PER_DAY, now)
            }
        val last = segments.maxByOrNull { it.endTime } ?: return null
        val end = last.endTime.toLong()
        val start = maxOf(segments.minOf { it.startTime }.toLong(), end - CLIP_SECONDS)
        return RecordingClip(
            url = api.recordingPlaylistUrl(camera, start, end),
            headers = api.authorizationHeaders(),
            certificateSha256 = server.certificateSha256,
            startTime = start,
            endTime = end,
        )
    }

    fun close() {
        http.close()
    }

    /** The client of the address that answered, connecting first if needed. */
    private suspend fun connectedApi(): FrigateApi {
        val current = mutableState.value as? ConnectionState.Connected
        val state = current ?: refresh()
        if (state is ConnectionState.Failed) throw state.error
        val url = (state as ConnectionState.Connected).snapshot.url
        return apis.first { it.first == url }.second
    }

    private suspend fun load(): ServerSnapshot {
        var unreachable: FrigateError = FrigateError.Unreachable()
        for ((url, api) in apis) {
            api.resetLoginFailure()
            val version =
                try {
                    api.version()
                } catch (error: FrigateError.Unreachable) {
                    unreachable = error
                    continue
                }
            val catalog = cameraCatalog(server.id, api.config(), api.userProfile())
            return ServerSnapshot(url, version, isSupportedFrigateVersion(version), catalog)
        }
        throw unreachable
    }
}
