package it.federicoraponi.frigatebridge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.federicoraponi.frigatebridge.camera.CameraRef
import it.federicoraponi.frigatebridge.camera.LiveStream
import it.federicoraponi.frigatebridge.frigate.FrigateError
import it.federicoraponi.frigatebridge.server.ConnectionState
import it.federicoraponi.frigatebridge.server.HttpsLiveStream
import it.federicoraponi.frigatebridge.server.RecordingClip
import it.federicoraponi.frigatebridge.server.ServerRegistry
import it.federicoraponi.frigatebridge.server.ServerSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

data class CameraArgs(
    val camera: CameraRef,
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

sealed interface RecordingState {
    data object Loading : RecordingState

    data class Ready(
        val clip: RecordingClip,
    ) : RecordingState

    /** Nothing was recorded in the past day. */
    data object Empty : RecordingState

    data class Failed(
        val failure: VideoFailure,
    ) : RecordingState
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
    val recording: RecordingState = RecordingState.Loading,
    /** The server or the camera does not exist any more: go back. */
    val isGone: Boolean = false,
)

/** Live view and latest recording of one camera. */
class CameraViewModel(
    args: CameraArgs,
    private val registry: ServerRegistry,
) : ViewModel() {
    private val camera = args.camera
    private var liveJob: Job? = null
    private var recordingJob: Job? = null
    private var httpsJob: Job? = null
    private var httpsFailure: VideoFailure? = null
    private val mutableState = MutableStateFlow(CameraState(title = camera.cameraName))
    val state: StateFlow<CameraState> = mutableState.asStateFlow()

    init {
        loadCamera()
    }

    fun selectMode(mode: VideoMode) {
        if (mode == mutableState.value.mode) return
        mutableState.update { it.copy(mode = mode) }
        if (mode == VideoMode.Live) startLive() else loadRecording()
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
                    liveFailed(error.toFailure())
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
            else -> loadRecording()
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
                    mutableState.update { it.copy(live = LiveState.Failed(error.toFailure())) }
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
                            )
                        }
                    }
                    if (found != null) startLive()
                }
                is ConnectionState.Failed -> liveFailed(connection.error.toFailure())
                ConnectionState.Idle, ConnectionState.Connecting -> Unit
            }
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun loadRecording() {
        recordingJob?.cancel()
        mutableState.update { it.copy(recording = RecordingState.Loading) }
        recordingJob =
            viewModelScope.launch {
                val result =
                    try {
                        val session = session() ?: return@launch
                        val clip = session.latestRecording(camera.cameraName, Clock.System.now().epochSeconds)
                        if (clip == null) RecordingState.Empty else RecordingState.Ready(clip)
                    } catch (error: FrigateError) {
                        RecordingState.Failed(error.toFailure())
                    }
                mutableState.update { it.copy(recording = result) }
            }
    }

    /** The player could not play the clip. */
    fun recordingFailed(detail: String?) {
        mutableState.update { it.copy(recording = RecordingState.Failed(VideoFailure(detail = detail))) }
    }

    private fun session(): ServerSession? {
        val session = registry.session(camera.serverId)
        if (session == null) mutableState.update { it.copy(isGone = true) }
        return session
    }

    private fun FrigateError.toFailure() = VideoFailure(toProblem(), (this as? FrigateError.UnexpectedResponse)?.detail)
}
