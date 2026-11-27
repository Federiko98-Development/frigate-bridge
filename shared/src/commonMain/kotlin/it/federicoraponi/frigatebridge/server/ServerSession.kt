package it.federicoraponi.frigatebridge.server

import io.ktor.client.HttpClient
import it.federicoraponi.frigatebridge.camera.CameraCatalog
import it.federicoraponi.frigatebridge.camera.CameraFeature
import it.federicoraponi.frigatebridge.camera.CameraRef
import it.federicoraponi.frigatebridge.camera.EditableGroup
import it.federicoraponi.frigatebridge.camera.cameraCatalog
import it.federicoraponi.frigatebridge.camera.groupChange
import it.federicoraponi.frigatebridge.camera.groupOrderChange
import it.federicoraponi.frigatebridge.camera.groupRemoval
import it.federicoraponi.frigatebridge.frigate.Credentials
import it.federicoraponi.frigatebridge.frigate.ExportDto
import it.federicoraponi.frigatebridge.frigate.FrigateApi
import it.federicoraponi.frigatebridge.frigate.FrigateError
import it.federicoraponi.frigatebridge.frigate.FrigateSocket
import it.federicoraponi.frigatebridge.frigate.PtzInfoDto
import it.federicoraponi.frigatebridge.frigate.ReviewDto
import it.federicoraponi.frigatebridge.frigate.TokenStore
import it.federicoraponi.frigatebridge.frigate.isSupportedFrigateVersion
import it.federicoraponi.frigatebridge.recordings.ExportItem
import it.federicoraponi.frigatebridge.recordings.RecordingDay
import it.federicoraponi.frigatebridge.recordings.RecordingSegment
import it.federicoraponi.frigatebridge.recordings.ReviewItem
import it.federicoraponi.frigatebridge.recordings.ReviewSeverity
import it.federicoraponi.frigatebridge.recordings.recordingDay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlin.math.ceil

data class ServerSnapshot(
    /** The address that answered. */
    val url: String,
    val version: String,
    val isSupportedVersion: Boolean,
    val catalog: CameraCatalog,
    /** Who the app is signed in as. */
    val account: Account = Account("", "viewer"),
    /** `false` when the server does not sign users in. */
    val authEnabled: Boolean = true,
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
    val serverId: String,
    /** Where the server keeps it, to download it with. */
    val path: String,
    val url: String,
    val fileName: String,
    val headers: Map<String, String>,
    val certificateSha256: String?,
)

// Bad gateway and gateway timeout.
private val GATEWAY_FAILURES = setOf(502, 504)

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
        detections = data.detections,
    )

private fun ExportDto.toItem(serverId: String) =
    ExportItem(
        serverId = serverId,
        id = id,
        camera = CameraRef(serverId, camera),
        name = name,
        date = date.toLong(),
        videoPath = videoPath,
        thumbnailPath = thumbPath,
        inProgress = inProgress,
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
    private val tokens: TokenStore,
    /** Saves a new password of the signed-in user, for the logins to come. */
    private val storePassword: suspend (String) -> Unit = {},
) {
    private val apis = server.addresses.map { url -> url to FrigateApi(http, url, credentials, tokens) }
    private val refreshMutex = Mutex()
    private val socketMutex = Mutex()
    private var socket: FrigateSocket? = null

    // Outlives the screens, so that a camera is stopped even while its screen is being destroyed.
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = mutableState.asStateFlow()

    /** The names of the cameras as shown to the user, by their name on the server; empty until connected. */
    val cameraNames: Map<String, String>
        get() =
            (state.value as? ConnectionState.Connected)
                ?.snapshot
                ?.catalog
                ?.cameras
                ?.associate { it.ref.cameraName to it.displayName }
                .orEmpty()

    /** Who the app is signed in as, or `null` while not connected. */
    val account: Account?
        get() = (state.value as? ConnectionState.Connected)?.snapshot?.account

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

    /**
     * Switches several functions of a camera, by Frigate's names and in the order given, then reads the server, also
     * after a failure, which leaves only some switched.
     */
    suspend fun setCameraFeatures(
        camera: String,
        changes: List<Pair<String, Boolean>>,
    ) {
        val api = connectedApi()
        try {
            changes.forEach { (feature, enabled) -> api.setCameraFeature(camera, feature, enabled) }
        } finally {
            refresh()
        }
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

    /** Removes review moments, with the recordings of those intervals. */
    suspend fun deleteReviews(ids: List<String>) = connectedApi().deleteReviews(ids)

    suspend fun mediaFile(serverPath: String): ByteArray = connectedApi().mediaFile(serverPath)

    /** Starts recording a camera on demand and returns the identifier of the event, to end it with. */
    suspend fun startManualRecording(camera: String): String {
        val created = connectedApi().startManualEvent(camera)
        return created.eventId?.takeIf { created.success }
            ?: throw FrigateError.UnexpectedResponse(created.message.ifBlank { "the recording was not started" })
    }

    /**
     * Ends a recording started on demand. It goes on in the background, so that it is ended even while the screen
     * that started it is being destroyed. [onEnded] runs there too, once the server has accepted: ending an event
     * again would move its end to that later moment, so whoever remembers it must forget it at once. The result is
     * why the server did not end it, or `null`.
     */
    fun endManualRecording(
        eventId: String,
        onEnded: suspend () -> Unit = {},
    ): Deferred<FrigateError?> =
        background.async {
            try {
                connectedApi().endEvent(eventId)
                onEnded()
                null
            } catch (error: FrigateError) {
                error
            }
        }

    /** The first and last second of a recorded event, or `null` while the server has not closed it yet. */
    suspend fun eventInterval(eventId: String): LongRange? {
        val event = connectedApi().event(eventId)
        val end = event.endTime ?: return null
        return event.startTime.toLong()..ceil(end).toLong()
    }

    /** Asks the server for a video file of an interval and returns the identifier of the export. */
    suspend fun startExport(
        camera: String,
        start: Long,
        end: Long,
        name: String? = null,
    ): String {
        val started = connectedApi().startExport(camera, start, end, name)
        return started.exportId?.takeIf { started.success }
            ?: throw FrigateError.UnexpectedResponse(started.message.ifBlank { "the export was not started" })
    }

    /** How to download an export, or `null` while the server is still making it. */
    suspend fun exportedFile(id: String): ExportedFile? {
        val api = connectedApi()
        val export = api.export(id)
        if (export.inProgress || export.videoPath.isBlank()) return null
        return ExportedFile(
            serverId = server.id,
            path = export.videoPath,
            url = api.mediaUrl(export.videoPath),
            fileName = export.videoPath.substringAfterLast('/'),
            headers = api.authorizationHeaders(),
            certificateSha256 = server.certificateSha256,
        )
    }

    suspend fun statistics(): ServerStatistics {
        val api = connectedApi()
        val stats = api.stats()
        // The figures about the disk are an extra: the others are worth showing without them.
        val storage =
            try {
                api.recordingStorage()
            } catch (_: FrigateError.UnexpectedResponse) {
                emptyMap()
            }
        return serverStatistics(stats, storage)
    }

    suspend fun exports(): List<ExportItem> = connectedApi().exports().map { it.toItem(server.id) }

    /** Where to play or download an export from. */
    suspend fun fileOf(export: ExportItem): ExportedFile {
        val api = connectedApi()
        return ExportedFile(
            serverId = server.id,
            path = export.videoPath,
            url = api.mediaUrl(export.videoPath),
            fileName = export.videoPath.substringAfterLast('/'),
            headers = api.authorizationHeaders(),
            certificateSha256 = server.certificateSha256,
        )
    }

    /** Reads an exported file piece by piece, see [FrigateApi.download]. */
    suspend fun download(
        file: ExportedFile,
        receive: suspend (bytes: ByteArray, count: Int) -> Unit,
    ) = connectedApi().download(file.path, receive)

    suspend fun deleteExport(id: String) = connectedApi().deleteExports(listOf(id))

    /** Marks the objects of a review moment as ones to keep, or no longer. Those the server forgot are passed over. */
    suspend fun setRetained(
        review: ReviewItem,
        retained: Boolean,
    ) {
        val api = connectedApi()
        review.detections.forEach { event ->
            try {
                api.setRetained(event, retained)
            } catch (_: FrigateError.UnexpectedResponse) {
                // The object is gone: there is nothing of it left to keep.
            }
        }
    }

    /** Reads the configuration again, bypassing the proxy cache. */
    suspend fun adminConfig(): AdminConfig = adminConfig(connectedApi().config())

    /** Every user of the server. Needs the administrator role, as the other changes to users do. */
    suspend fun users(): List<FrigateUser> = connectedApi().users().map { FrigateUser(it.username, it.role) }

    suspend fun createUser(
        username: String,
        password: String,
        role: String,
    ) = connectedApi().createUser(username, password, role)

    suspend fun deleteUser(username: String) = connectedApi().deleteUser(username)

    suspend fun setUserRole(
        username: String,
        role: String,
    ) = connectedApi().setUserRole(username, role)

    /** Sets another user's password, as an administrator. Throws IllegalArgumentException for the signed-in user. */
    suspend fun setUserPassword(
        username: String,
        password: String,
    ) {
        val api = connectedApi()
        require(username != account?.username) { "Use changeOwnPassword for the signed-in user" }
        api.setPassword(username, password, oldPassword = null)
    }

    /**
     * Changes the signed-in user's password. Sends [current] as `old_password` (Frigate checks it only for
     * non-administrators), then stores [new] as the saved password and the token the server returned, so the next
     * login still works.
     */
    suspend fun changeOwnPassword(
        current: String,
        new: String,
    ) {
        val api = connectedApi()
        val username = checkNotNull(account).username
        // Once sent, the server may apply the change even if the screen is left: the new password must be saved.
        withContext(NonCancellable) {
            val token = api.setPassword(username, new, current)
            storePassword(new)
            if (token != null) tokens.write(token)
            apis.forEach { it.second.resetLoginFailure() }
        }
    }

    /** Creates or replaces a custom role, which applies at once. Checks that it was saved and reads the server. */
    suspend fun saveRole(
        name: String,
        cameras: List<String>,
    ) = writeConfig(roleChange(name, cameras), ROLES_TOPIC) { config ->
        config.customRoles.any { it.name == name && it.cameras == cameras }
    }

    /** Moves [usersWithRole] to viewer one by one, then removes the role. A failure leaves the role in place. */
    suspend fun deleteRole(
        name: String,
        usersWithRole: List<String>,
    ) {
        val api = connectedApi()
        usersWithRole.forEach { api.setUserRole(it, "viewer") }
        writeConfig(roleRemoval(name), ROLES_TOPIC) { config -> config.customRoles.none { it.name == name } }
    }

    /** Creates ([originalName] `null`) or replaces a camera group, renaming it when the name changed. */
    suspend fun saveGroup(
        originalName: String?,
        group: EditableGroup,
    ) = writeConfig(groupChange(originalName, group)) { config ->
        group in config.groups &&
            (originalName == null || originalName == group.name || config.groups.none { it.name == originalName })
    }

    suspend fun deleteGroup(name: String) =
        writeConfig(groupRemoval(name)) { config -> config.groups.none { it.name == name } }

    /** Numbers the camera groups from 1, in the order of [names]. */
    suspend fun reorderGroups(names: List<String>) =
        writeConfig(groupOrderChange(names)) { config ->
            val orders = config.groups.associate { it.name to it.order }
            names.withIndex().all { (index, name) -> orders[name] == index + 1 }
        }

    /** Restarts Frigate. */
    suspend fun restart() {
        val api = connectedApi()
        // A server that is already down must not pass for one that is restarting.
        api.version()
        try {
            api.restart()
        } catch (_: FrigateError.Unreachable) {
            // Frigate stops itself before it answers, so the answer may never arrive.
        } catch (error: FrigateError.UnexpectedResponse) {
            // Or the proxy answers in its place, finding it gone.
            if (error.status !in GATEWAY_FAILURES) throw error
        }
    }

    /** Drops the session token and reads the server again, e.g. after this user's role changed. */
    suspend fun signInAgain() {
        tokens.write(null)
        apis.forEach { it.second.resetLoginFailure() }
        refresh()
    }

    /**
     * Changes the configuration, reads it again to check with [isSaved] that the change is there (Frigate reports
     * success even when it could not write its file), then reads the server.
     */
    private suspend fun writeConfig(
        data: JsonObject,
        updateTopic: String? = null,
        isSaved: (AdminConfig) -> Boolean,
    ) {
        connectedApi().setConfig(data, updateTopic)
        if (!isSaved(adminConfig())) throw AdminNotSaved()
        refresh()
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
            val config = api.config()
            val profile = api.userProfile()
            return ServerSnapshot(
                url = url,
                version = version,
                isSupportedVersion = isSupportedFrigateVersion(version),
                catalog = cameraCatalog(server.id, config, profile),
                account = Account(profile.username, profile.role),
                authEnabled = config.auth.enabled,
            )
        }
        throw unreachable
    }
}
