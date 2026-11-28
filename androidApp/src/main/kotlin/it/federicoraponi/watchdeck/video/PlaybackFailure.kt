package it.federicoraponi.watchdeck.video

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import it.federicoraponi.watchdeck.frigate.FrigateError
import it.federicoraponi.watchdeck.ui.VideoFailure
import it.federicoraponi.watchdeck.ui.VideoProblem
import it.federicoraponi.watchdeck.ui.toProblem

private val HTTP_REFUSAL = Regex("(HTTP |Response code: )(30[12378]|401|403)")

/** Why a player stopped, as far as its error tells. */
@OptIn(UnstableApi::class)
fun playbackFailure(error: PlaybackException): VideoFailure {
    val causes = generateSequence(error.cause) { it.cause }.take(MAX_CAUSES).toList()
    // Signing a request failed: the reason is about the account, not about the video.
    causes.filterIsInstance<FrigateError>().firstOrNull()?.let {
        return VideoFailure(problem = it.toProblem(), detail = it.message)
    }
    val messages = causes.mapNotNull { it.message }.joinToString(": ")
    val reason =
        when {
            // Parsing, decoding and audio output errors: what arrived cannot be played on this phone.
            error.errorCode >= PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED &&
                error.errorCode < PlaybackException.ERROR_CODE_DRM_UNSPECIFIED -> VideoProblem.UnsupportedFormat
            // go2rtc's way of saying that the camera sends nothing that was asked for.
            "codecs" in messages -> VideoProblem.UnsupportedFormat
            HTTP_REFUSAL.containsMatchIn(messages) -> VideoProblem.Refused
            "No data from the server" in messages -> VideoProblem.NoPicture
            else -> VideoProblem.Connection
        }
    val detail = listOf(error.errorCodeName, messages).filter { it.isNotEmpty() }.joinToString(": ")
    return VideoFailure(detail = detail, reason = reason)
}

private const val MAX_CAUSES = 8
