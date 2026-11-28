package it.federicoraponi.watchdeck.ui

/** One way of getting the live picture: a transport, for one of the camera's streams. */
data class LiveAttempt(
    val transport: LiveTransport,
    val stream: String,
)

/** A way that did not work, and why. */
data class LiveSetback(
    val attempt: LiveAttempt,
    val failure: VideoFailure,
)

/** What another stream of the same camera may not suffer from, e.g. a lighter one in a format the phone plays. */
private val STREAM_RELATED =
    setOf(VideoProblem.UnsupportedFormat, VideoProblem.NoPicture, VideoProblem.Stalled, VideoProblem.ServerError)

/**
 * The way to try after the [setbacks], or `null` when there is none left. In order: the chosen stream over HTTPS,
 * then over WebRTC; another stream over HTTPS, if the trouble may be the stream's; at last still pictures, which
 * work wherever the server answers.
 */
internal fun nextLiveAttempt(
    streams: List<String>,
    selected: String,
    setbacks: List<LiveSetback>,
): LiveAttempt? {
    val tried = setbacks.map { it.attempt }
    val isStreamRelated =
        setbacks.any { it.attempt == LiveAttempt(LiveTransport.Https, selected) && it.failure.reason in STREAM_RELATED }
    val other = streams.firstOrNull { it != selected }?.takeIf { isStreamRelated }
    return listOfNotNull(
        LiveAttempt(LiveTransport.Https, selected),
        LiveAttempt(LiveTransport.WebRtc, selected),
        other?.let { LiveAttempt(LiveTransport.Https, it) },
        LiveAttempt(LiveTransport.Snapshots, selected),
    ).firstOrNull { it !in tried }
}
