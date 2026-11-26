package it.federicoraponi.frigatebridge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.federicoraponi.frigatebridge.camera.CameraFeature
import it.federicoraponi.frigatebridge.camera.CameraRef
import it.federicoraponi.frigatebridge.camera.LiveStream
import it.federicoraponi.frigatebridge.frigate.FrigateError
import it.federicoraponi.frigatebridge.server.ConnectionState
import it.federicoraponi.frigatebridge.server.HttpsLiveStream
import it.federicoraponi.frigatebridge.server.ServerRegistry
import it.federicoraponi.frigatebridge.server.ServerSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CameraArgs(
    val camera: CameraRef,
    val mode: VideoMode = VideoMode.Live,
)

enum class VideoMode {
    Live,
    Recording,
}

/** Why a video cannot be played. [detail] is the server's or player's own explanation, for diagnosis. */
data class VideoFailure(
    val problem: ConnectionProblem? = null,
    val detail: String? = null,
)

/** How the live view reaches the phone. */
enum class LiveTransport {
    /**
     * Frigate's MP4 stream, as its web interface plays it: starts quickly and goes wherever the rest of the app
     * goes, with a delay of about a second.
     */
    Https,

    /** The second choice, because it needs the server's WebRTC port to be reachable. */
    WebRtc,
}

sealed interface LiveState {
    data object Connecting : LiveState

    data object Playing : LiveState

    data class Failed(
        val failure: VideoFailure,
    ) : LiveState
}

data class CameraState(
    val title: String = "",
    val mode: VideoMode = VideoMode.Live,
    /** Empty until the camera is known: there is nothing to connect to before. */
    val streams: List<LiveStream> = emptyList(),
    val selectedStream: String? = null,
    val live: LiveState = LiveState.Connecting,
    /** Grows on every retry, so that the screen builds a new connection. */
    val liveAttempt: Int = 0,
    val transport: LiveTransport = LiveTransport.Https,
    /** What to play when [transport] is HTTPS; `null` while it is being prepared. */
    val httpsStream: HttpsLiveStream? = null,
    val controls: CameraControls = CameraControls(),
    /** `false` when the configuration file disables recording: there is nothing recorded to offer. */
    val canRecord: Boolean = true,
    /** `null` for a camera that cannot be moved. */
    val ptz: PtzControls? = null,
    /** The server or the camera does not exist any more: go back. */
    val isGone: Boolean = false,
)

/** Live view and controls of one camera. */
class CameraViewModel(
    args: CameraArgs,
    private val registry: ServerRegistry,
) : ViewModel() {
    private val camera = args.camera
    private var liveJob: Job? = null
    private var httpsJob: Job? = null
    private var httpsFailure: VideoFailure? = null

    // One command at a time and in order, so that a STOP never overtakes its movement.
    private val ptzCommands = Channel<String>(Channel.UNLIMITED)
    private var ptzUsed = false

    // A movement was asked for and no STOP has reached the server since.
    private var stopPending = false
    private val mutableState = MutableStateFlow(CameraState(title = camera.cameraName, mode = args.mode))
    val state: StateFlow<CameraState> = mutableState.asStateFlow()

    init {
        loadCamera()
        viewModelScope.launch {
            for (command in ptzCommands) {
                try {
                    session()?.sendPtz(camera.cameraName, command)
                    if (command == STOP) stopPending = false
                } catch (error: FrigateError) {
                    mutableState.update { it.copy(controls = it.controls.copy(failure = error.toVideoFailure())) }
                }
            }
        }
    }

    fun setFeature(
        feature: CameraFeature,
        enabled: Boolean,
    ) {
        val controls = mutableState.value.controls
        if (!controls.isAllowed || controls.isApplying || feature !in controls.switchable) return
        val wanted = if (enabled) controls.enabled + feature else controls.enabled - feature
        // Shown at once; the server's answer then replaces it.
        mutableState.update {
            it.copy(controls = controls.copy(enabled = wanted, isApplying = true, failure = null))
        }
        viewModelScope.launch {
            val session = session() ?: return@launch
            var failure: VideoFailure? = null
            try {
                session.setCameraFeature(camera.cameraName, feature, enabled)
            } catch (error: FrigateError) {
                failure = error.toVideoFailure()
            }
            val snapshot = (session.state.value as? ConnectionState.Connected)?.snapshot
            val current = snapshot?.catalog?.cameras?.firstOrNull { it.ref == camera }
            mutableState.update {
                val read = current?.controls(snapshot.catalog.canChangeSettings)
                val shown = if (failure != null) controls else read ?: it.controls
                it.copy(controls = shown.copy(isApplying = false, failure = failure))
            }
        }
    }

    /** Starts moving the camera; it keeps going until [stopMoving]. */
    fun startMoving(move: PtzMove) {
        ptzUsed = true
        stopPending = true
        ptzCommands.trySend(move.command)
    }

    fun stopMoving() {
        ptzCommands.trySend(STOP)
    }

    fun goToPreset(name: String) {
        ptzUsed = true
        ptzCommands.trySend("preset_$name")
    }

    /** Call it when the screen leaves the foreground: stops the camera and closes the command connection. */
    fun releaseControls() {
        if (!ptzUsed) return
        ptzUsed = false
        registry.session(camera.serverId)?.finishPtz(camera.cameraName, stop = stopPending)
        stopPending = false
    }

    override fun onCleared() {
        releaseControls()
    }

    private fun loadPtz(session: ServerSession) {
        viewModelScope.launch {
            val info =
                try {
                    session.ptzInfo(camera.cameraName)
                } catch (_: FrigateError) {
                    // Without it the camera simply shows no movement controls.
                    return@launch
                }
            val ptz =
                PtzControls(
                    canPanAndTilt = "pt" in info.features,
                    canZoom = "zoom" in info.features,
                    presets = info.presets,
                )
            if (ptz.canPanAndTilt || ptz.canZoom || ptz.presets.isNotEmpty()) {
                mutableState.update { it.copy(ptz = ptz) }
            }
        }
    }

    fun selectMode(mode: VideoMode) {
        if (mode == mutableState.value.mode) return
        mutableState.update { it.copy(mode = mode) }
        // The recordings have their own ViewModel.
        if (mode == VideoMode.Live) startLive() else stopLive()
    }

    fun selectStream(name: String) {
        mutableState.update { it.copy(selectedStream = name) }
        startLive()
    }

    /** Negotiates a connection for the selected stream through [peer]. */
    fun connectLive(peer: LivePeer) {
        val stream = mutableState.value.selectedStream ?: return
        liveJob?.cancel()
        mutableState.update { it.copy(live = LiveState.Connecting) }
        liveJob =
            viewModelScope.launch {
                try {
                    val session = session() ?: return@launch
                    val answer = session.webRtcAnswer(stream, peer.createOffer())
                    peer.acceptAnswer(answer)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: FrigateError) {
                    liveFailed(error.toVideoFailure())
                } catch (error: Exception) {
                    // The platform's WebRTC library refused the offer or the answer.
                    liveFailed(VideoFailure(detail = error.message))
                }
            }
    }

    /** Call it before closing the peer given to [connectLive]. */
    fun stopLive() {
        liveJob?.cancel()
        liveJob = null
    }

    /** The first frame arrived. */
    fun livePlaying() {
        mutableState.update { it.copy(live = LiveState.Playing) }
    }

    /** The connection could not be established, or was lost. If the HTTPS stream fails, WebRTC is tried. */
    fun liveFailed(failure: VideoFailure = VideoFailure()) {
        val current = mutableState.value
        // A first explanation is more useful than the "connection failed" that may follow it.
        if (current.live is LiveState.Failed) return
        if (current.transport == LiveTransport.Https && current.selectedStream != null) {
            httpsFailure = failure
            useWebRtc()
        } else {
            // The reason the first choice failed usually explains more.
            val shown = httpsFailure?.takeIf { failure == VideoFailure() } ?: failure
            mutableState.update { it.copy(live = LiveState.Failed(shown)) }
        }
    }

    fun retry() {
        val current = mutableState.value
        when {
            current.streams.isEmpty() -> loadCamera()
            current.mode == VideoMode.Live -> startLive()
        }
    }

    private fun startLive() {
        val stream = mutableState.value.selectedStream ?: return
        stopLive()
        httpsJob?.cancel()
        httpsFailure = null
        mutableState.update {
            it.copy(
                transport = LiveTransport.Https,
                httpsStream = null,
                live = LiveState.Connecting,
                liveAttempt = it.liveAttempt + 1,
            )
        }
        httpsJob =
            viewModelScope.launch {
                try {
                    val session = session() ?: return@launch
                    val source = session.httpsLiveStream(stream)
                    mutableState.update { it.copy(httpsStream = source) }
                } catch (error: FrigateError) {
                    // WebRTC asks the same server, so it would fail the same way.
                    mutableState.update { it.copy(live = LiveState.Failed(error.toVideoFailure())) }
                }
            }
    }

    private fun useWebRtc() {
        httpsJob?.cancel()
        mutableState.update {
            it.copy(
                transport = LiveTransport.WebRtc,
                httpsStream = null,
                live = LiveState.Connecting,
                liveAttempt = it.liveAttempt + 1,
            )
        }
    }

    private fun loadCamera() {
        viewModelScope.launch {
            registry.load()
            val session = session() ?: return@launch
            val connection = session.state.value as? ConnectionState.Connected ?: session.refresh()
            when (connection) {
                is ConnectionState.Connected -> {
                    val found =
                        connection.snapshot.catalog.cameras
                            .firstOrNull { it.ref == camera }
                    mutableState.update {
                        if (found == null) {
                            it.copy(isGone = true)
                        } else {
                            it.copy(
                                title = found.displayName,
                                streams = found.liveStreams,
                                selectedStream = found.liveStreams.firstOrNull()?.name,
                                controls = found.controls(connection.snapshot.catalog.canChangeSettings),
                                canRecord = found.canRecord,
                            )
                        }
                    }
                    if (found != null) {
                        if (found.hasPtz) loadPtz(session)
                        if (mutableState.value.mode == VideoMode.Live) startLive()
                    }
                }
                is ConnectionState.Failed -> liveFailed(connection.error.toVideoFailure())
                ConnectionState.Idle, ConnectionState.Connecting -> Unit
            }
        }
    }

    private fun session(): ServerSession? {
        val session = registry.session(camera.serverId)
        if (session == null) mutableState.update { it.copy(isGone = true) }
        return session
    }
}

private const val STOP = "STOP"
