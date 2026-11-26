package it.federicoraponi.frigatebridge.server

import io.ktor.client.HttpClient
import it.federicoraponi.frigatebridge.camera.CameraCatalog
import it.federicoraponi.frigatebridge.camera.CameraFeature
import it.federicoraponi.frigatebridge.camera.CameraRef
import it.federicoraponi.frigatebridge.camera.cameraCatalog
import it.federicoraponi.frigatebridge.frigate.Credentials
import it.federicoraponi.frigatebridge.frigate.FrigateApi
import it.federicoraponi.frigatebridge.frigate.FrigateError
import it.federicoraponi.frigatebridge.frigate.FrigateSocket
import it.federicoraponi.frigatebridge.frigate.PtzInfoDto
import it.federicoraponi.frigatebridge.frigate.ReviewDto
import it.federicoraponi.frigatebridge.frigate.TokenStore
import it.federicoraponi.frigatebridge.frigate.isSupportedFrigateVersion
import it.federicoraponi.frigatebridge.recordings.RecordingDay
import it.federicoraponi.frigatebridge.recordings.RecordingSegment
import it.federicoraponi.frigatebridge.recordings.ReviewItem
import it.federicoraponi.frigatebridge.recordings.ReviewSeverity
import it.federicoraponi.frigatebridge.recordings.recordingDay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

/** An export ready to be downloaded. */
data class ExportedFile(
    val url: String,
    val fileName: String,
    val headers: Map<String, String>,
    val certificateSha256: String?,
)

private fun ReviewDto.toItem(serverId: String) =
    ReviewItem(
        serverId = serverId,
        id = id,
        camera = CameraRef(serverId, camera),
        start = startTime.toLong(),
        end = endTime?.toLong(),
        severity = if (severity == "alert") ReviewSeverity.Alert else ReviewSeverity.Detection,
        labels = (data.objects + data.audio).map { it.removeSuffix("-verified") }.distinct(),
        zones = data.zones,
        thumbnailPath = thumbPath,
        isViewed = hasBeenReviewed,
    )

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
    private val socketMutex = Mutex()
    private var socket: FrigateSocket? = null

    // Outlives the screens, so that a camera is stopped even while its screen is being destroyed.
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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

    /** Turns a camera function on or off and reads the server again. */
    suspend fun setCameraFeature(
        camera: String,
        feature: CameraFeature,
        enabled: Boolean,
    ) {
        connectedApi().setCameraFeature(camera, feature.apiName, enabled)
        refresh()
    }

    suspend fun ptzInfo(camera: String): PtzInfoDto = connectedApi().ptzInfo(camera)

    /** Sends a movement command such as `MOVE_LEFT`, `STOP` or `preset_<name>`. */
    suspend fun sendPtz(
        camera: String,
        command: String,
    ) = socketMutex.withLock {
        val open = socket?.takeIf { it.isOpen } ?: connectedApi().openSocket().also { socket = it }
        try {
            open.publish("$camera/ptz", command)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // The connection dropped while idle: one more try on a new one, so that a STOP is not lost.
            val fresh = connectedApi().openSocket().also { socket = it }
            fresh.publish("$camera/ptz", command)
        }
    }

    /**
     * Ends the use of the command connection, stopping [camera] first if a movement may still be running. Frigate
     * streams all its events on that connection, so it must not stay open.
     */
    fun finishPtz(
        camera: String,
        stop: Boolean,
    ) {
        background.launch {
            if (stop) {
                try {
                    sendPtz(camera, "STOP")
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    // Nothing else can be done once the server is unreachable.
                }
            }
            socketMutex.withLock {
                val open = socket
                socket = null
                open?.close()
            }
        }
    }

    /** The most recent picture of a camera, as a small JPEG. */
    suspend fun latestFrame(
        camera: String,
        height: Int,
    ): ByteArray = connectedApi().latestFrame(camera, height)

    /** The days with recordings of a camera, newest first. */
    suspend fun recordingDays(
        camera: String,
        zoneId: String,
    ): List<RecordingDay> =
        connectedApi()
            .recordingDays(camera, zoneId)
            .mapNotNull { recordingDay(it.day, zoneId) }
            .sortedByDescending { it.start }

    suspend fun recordingSegments(
        camera: String,
        after: Long,
        before: Long,
    ): List<RecordingSegment> =
        connectedApi().recordings(camera, after, before).map {
            RecordingSegment(it.startTime, it.endTime, it.duration ?: (it.endTime - it.startTime))
        }

    /** What a player needs for the video of an interval of recordings. */
    suspend fun recordingClip(
        camera: String,
        start: Long,
        end: Long,
    ): RecordingClip {
        val api = connectedApi()
        return RecordingClip(
            url = api.recordingPlaylistUrl(camera, start, end),
            headers = api.authorizationHeaders(),
            certificateSha256 = server.certificateSha256,
            startTime = start,
            endTime = end,
        )
    }

    suspend fun recordedFrame(
        camera: String,
        time: Long,
        height: Int,
    ): ByteArray = connectedApi().recordedFrame(camera, time, height)

    /** Review moments between two times, newest first. */
    suspend fun reviews(
        after: Long,
        before: Long,
        cameras: List<String>? = null,
        labels: List<String>? = null,
        severity: ReviewSeverity? = null,
        limit: Int? = null,
    ): List<ReviewItem> =
        connectedApi()
            .reviews(after, before, cameras, labels, severity?.name?.lowercase(), limit)
            .map { it.toItem(server.id) }
            .sortedByDescending { it.start }

    suspend fun review(id: String): ReviewItem = connectedApi().review(id).toItem(server.id)

    suspend fun markReviewed(ids: List<String>) = connectedApi().markReviewed(ids)

    suspend fun mediaFile(serverPath: String): ByteArray = connectedApi().mediaFile(serverPath)

    /** Asks the server for a video file of an interval and returns the identifier of the export. */
    suspend fun startExport(
        camera: String,
        start: Long,
        end: Long,
    ): String {
        val started = connectedApi().startExport(camera, start, end)
        return started.exportId?.takeIf { started.success }
            ?: throw FrigateError.UnexpectedResponse(started.message.ifBlank { "the export was not started" })
    }

    /** How to download an export, or `null` while the server is still making it. */
    suspend fun exportedFile(id: String): ExportedFile? {
        val api = connectedApi()
        val export = api.export(id)
        if (export.inProgress || export.videoPath.isBlank()) return null
        return ExportedFile(
            url = api.mediaUrl(export.videoPath),
            fileName = export.videoPath.substringAfterLast('/'),
            headers = api.authorizationHeaders(),
            certificateSha256 = server.certificateSha256,
        )
    }

    fun close() {
        socket?.cancel()
        socket = null
        background.cancel()
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
