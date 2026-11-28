package it.federicoraponi.watchdeck.ui

import it.federicoraponi.watchdeck.camera.CameraRef
import it.federicoraponi.watchdeck.camera.LiveStream
import it.federicoraponi.watchdeck.server.HttpsLiveStream

data class CameraArgs(
    val camera: CameraRef,
    val mode: VideoMode = VideoMode.Live,
)

enum class VideoMode {
    Live,
    Recording,
}

/** How the live view reaches the phone. */
enum class LiveTransport {
    /**
     * Frigate's MP4 stream, as its web interface plays it: starts quickly and goes wherever the rest of the app
     * goes, with a delay of about a second.
     */
    Https,

    /** The second choice, because it needs the server's WebRTC port to be reachable. */
    WebRtc,

    /** The last resort: the camera's latest picture, again and again, without sound. */
    Snapshots,
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
    /** The stream being played or tried; another than the chosen one after a fallback. */
    val liveStream: String? = null,
    /** The ways that did not work since the live view was started, in order. The screen explains them. */
    val setbacks: List<LiveSetback> = emptyList(),
    /** What to play when [transport] is HTTPS; `null` while it is being prepared. */
    val httpsStream: HttpsLiveStream? = null,
    val controls: CameraControls = CameraControls(),
    /** `false` when the configuration file disables recording: there is nothing recorded to offer. */
    val canRecord: Boolean = true,
    /** `null` for a camera that cannot be moved. */
    val ptz: PtzControls? = null,
    val clip: ClipState = ClipState.None,
    /** Recording on demand needs an administrator, and a camera that is recording. */
    val canRecordClip: Boolean = false,
    /** The server or the camera does not exist any more: go back. */
    val isGone: Boolean = false,
    /** The phone has no network. */
    val isOffline: Boolean = false,
)
