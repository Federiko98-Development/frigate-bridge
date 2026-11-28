package it.federicoraponi.watchdeck.ui

/** What a player can tell about why a video does not play. */
enum class VideoProblem {
    /** The connection was there, but no picture arrived in time. */
    NoPicture,

    /** The picture appeared and then stopped moving. */
    Stalled,

    /** The phone cannot play the camera's video or audio format. */
    UnsupportedFormat,

    /** The server, or what is in front of it, refused the request for the video. */
    Refused,

    /** The connection carrying the video failed. */
    Connection,

    /** WebRTC video comes on its own port, which does not pass through a web proxy. */
    WebRtcUnreachable,

    /** The server could not set the video up. */
    ServerError,
}

/** Why a video cannot be played. [detail] is the server's or player's own explanation, for diagnosis. */
data class VideoFailure(
    val problem: ConnectionProblem? = null,
    val detail: String? = null,
    val reason: VideoProblem? = null,
)

/**
 * Whether another way of getting the video would fail the same way: the server is out of reach, or it does not accept
 * who is asking. These are reported at once instead of being tried around.
 */
val VideoFailure.stopsEveryWay: Boolean
    get() = problem != null && problem != ConnectionProblem.UnexpectedResponse
